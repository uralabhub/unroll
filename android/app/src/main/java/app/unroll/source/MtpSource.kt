package app.unroll.source

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpEvent
import android.mtp.MtpObjectInfo
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.unroll.core.CardItem
import app.unroll.core.Pairing
import app.unroll.core.PairedShot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val TAG = "UnrollMtp"

/** The camera stopped answering one PTP transaction; the session is torn down and reopened. */
class CameraTimeoutException(message: String) : IOException(message)

/**
 * One open PTP/MTP camera. PTP is strictly one transaction at a time, so every call runs on one
 * dedicated thread, in order. Each call has a watchdog: if the camera stops answering, the USB
 * connection is closed (which unblocks the stuck transfer) and [onStuck] fires so the service can
 * reopen it — one stuck file can't hang an import run or the grid. A long download is split into
 * 4 MB GetPartialObject chunks so header reads for the grid can slip in between.
 */
class MtpCamera(val device: UsbDevice, private val connection: UsbDeviceConnection, val mtp: MtpDevice) {
    companion object {
        /** ObjectCompressedSize is 32-bit; 0xFFFFFFFF means "4 GB or more, ask again". */
        const val SIZE_UNKNOWN_32 = 0xFFFF_FFFFL
        private const val OP_GET_PARTIAL_OBJECT_64 = 0x95C1
        private const val CHUNK = 4 shl 20
        /** Hung partial reads per camera; kept across the reopen that follows each hang. */
        private val partialHangsByCamera = java.util.concurrent.ConcurrentHashMap<String, Int>()
        /** Objects whose partial read hung, per camera: not retried, or every reopen would rescan
         *  the same file, hang again and drop the session in a loop that never lets an import run. */
        private val hungObjects = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()
    }

    @Volatile var closed = false
        private set
    /** Called (on the watchdog's thread) when a transaction timed out and the camera was closed. */
    @Volatile var onStuck: (() -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mtp-io").apply { isDaemon = true } }

    val name: String
        get() = listOfNotNull(mtp.deviceInfo?.manufacturer, mtp.deviceInfo?.model)
            .joinToString(" ").ifBlank { device.productName ?: "USB camera" }

    private fun supports(op: Int): Boolean? = runCatching { mtp.deviceInfo?.isOperationSupported(op) }.getOrNull()

    /** GetPartialObject (0x101B). Without it we can still import (whole-object GetObject), not peek. */
    val partialSupported: Boolean by lazy { supports(MtpConstants.OPERATION_GET_PARTIAL_OBJECT) ?: true }
    private val partial64Supported: Boolean by lazy { supports(OP_GET_PARTIAL_OBJECT_64) ?: false }
    /** Set when a camera claimed GetPartialObject64 but refused it: retries use GetObject instead. */
    @Volatile private var partial64Broken = false
    /** Partial reads that hung. After two, imports use whole-object GetObject (some responders
     *  stall on GetPartialObject for certain files while GetObject works). */
    private val cameraKey = "${device.vendorId}:${device.productId}:${runCatching { device.serialNumber }.getOrNull()}"
    private val partialHangs: Int get() = partialHangsByCamera[cameraKey] ?: 0

    /**
     * Runs [block] on the PTP thread. [timeoutMs] counts from when the transaction *starts*, not
     * while it waits its turn. Cancelling the caller while it waits drops the transaction.
     */
    private suspend fun <T> op(what: String, timeoutMs: Long, block: () -> T): T {
        if (closed) throw IOException("Camera disconnected")
        val started = CompletableDeferred<Unit>()
        val fut = try {
            CompletableFuture.supplyAsync({
                if (closed) throw IOException("Camera disconnected")
                started.complete(Unit)
                block()
            }, executor)
        } catch (e: RejectedExecutionException) {
            throw IOException("Camera disconnected")
        }
        try {
            select<Unit> {
                started.onAwait { }
                fut.asDeferred().onAwait { }
            }
            return withTimeout(timeoutMs) { fut.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "$what timed out after ${timeoutMs}ms; closing the session")
            stuck()
            throw CameraTimeoutException("The camera stopped responding.")
        } catch (e: CancellationException) {
            fut.cancel(false)
            throw e
        } catch (e: CompletionException) {
            throw e.cause ?: e
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    suspend fun readPartial(handle: Int, offset: Long, length: Int): ByteArray {
        if (!partialSupported) throw UnsupportedOperationException("This camera can't read part of a file.")
        if (hungObjects[cameraKey]?.contains(handle) == true) throw IOException("The camera stalled reading this file before.")
        // ~4 MB in 30 s is < 140 KB/s: slower than any working USB 2 link.
        return try { op("GetPartialObject(handle=$handle, offset=$offset, length=$length)", 30_000) {
            val buf = ByteArray(length)
            // GetPartialObject (PTP 0x101B) takes a 32-bit offset; the 64-bit variant is an Android
            // extension most cameras don't implement, so only use it past 4 GB (long videos).
            val n = if (offset + length <= SIZE_UNKNOWN_32) {
                mtp.getPartialObject(handle, offset, length.toLong(), buf)
            } else {
                mtp.getPartialObject64(handle, offset, length.toLong(), buf)
            }
            if (n <= 0) throw IOException("The camera returned no data.")
            if (n.toInt() == length) buf else buf.copyOf(n.toInt())
        } } catch (e: CameraTimeoutException) {
            partialHangsByCamera.merge(cameraKey, 1, Int::plus)
            hungObjects.getOrPut(cameraKey) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(handle)
            throw e
        }
    }

    /**
     * Copies a whole object. GetPartialObject in chunks when possible (progress, interleaving);
     * otherwise — no partial reads, or > 4 GB without the 64-bit variant, or a size the camera
     * won't tell us — a whole-object GetObject into a temp file. Either way the byte count is
     * checked against the size before returning.
     */
    suspend fun copyObject(handle: Int, size: Long, out: OutputStream, progress: (Double) -> Unit, check: () -> Unit) {
        val known = size > 0 && size != SIZE_UNKNOWN_32
        if (!partialSupported || !known || partialHangs >= 2 ||
            (size > SIZE_UNKNOWN_32 && (!partial64Supported || partial64Broken))) {
            importWhole(handle, if (known) size else null, out, progress, check)
            return
        }
        var off = 0L
        while (off < size) {
            check()
            val n = minOf(CHUNK.toLong(), size - off).toInt()
            val b = try {
                readPartial(handle, off, n)
            } catch (e: IOException) {
                // Past 4 GB a camera may refuse GetPartialObject64 after all: the retry uses GetObject.
                if (off + n > SIZE_UNKNOWN_32 && !closed) partial64Broken = true
                throw e
            }
            out.write(b)
            off += b.size
            progress(off.toDouble() / size)
            if (b.size < n) break
        }
        if (off != size) throw IOException("Transfer stopped at ${off * 100 / size}%")
    }

    /** GetObject into app cache, then streamed out. Used when partial reads aren't available. */
    suspend fun importWhole(handle: Int, size: Long?, out: OutputStream, progress: (Double) -> Unit, check: () -> Unit) {
        val dir = app.unroll.UnrollApplication.context.cacheDir
        if (size != null && dir.usableSpace < size + (64L shl 20)) throw IOException("Not enough free space on the phone to copy this file.")
        val tmp = File(dir, "mtp-$handle.part")
        try {
            // No progress while the camera sends the object; allow ≥ 1 MB/s plus a minute.
            val timeout = 60_000L + (size ?: (4L shl 30)) / 1000
            val ok = op("GetObject", timeout) { mtp.importFile(handle, tmp.path) }
            if (!ok) throw IOException("The camera didn't send the file.")
            if (size != null && tmp.length() != size) throw IOException("The camera sent ${tmp.length()} of $size bytes.")
            tmp.inputStream().use { pump(it, out, tmp.length(), progress, check) }
        } finally {
            tmp.delete()
        }
    }

    /** GetThumb (0x100A), unless the camera says it doesn't do it: a refused GetThumb answers with
     *  a response where a data phase is expected, and some responders get out of step after that. */
    private val thumbSupported: Boolean by lazy { supports(MtpConstants.OPERATION_GET_THUMB) ?: true }

    suspend fun thumbnail(handle: Int): ByteArray? =
        if (closed || !thumbSupported) null else op("GetThumb", 15_000) { mtp.getThumbnail(handle) }

    suspend fun info(handle: Int): MtpObjectInfo? = if (closed) null else op("GetObjectInfo", 10_000) { mtp.getObjectInfo(handle) }

    suspend fun handles(storage: Int, parent: Int): IntArray? =
        if (closed) null else op("GetObjectHandles", 90_000) { mtp.getObjectHandles(storage, 0, parent) }

    suspend fun storages(): IntArray = if (closed) IntArray(0) else op("GetStorageIDs", 10_000) { mtp.storageIds } ?: IntArray(0)

    /** Watchdog path: drop the link from this thread (unblocks the stuck transfer), clean up after. */
    private fun stuck() {
        if (closed) return
        closed = true
        runCatching { connection.close() }
        executor.execute { runCatching { mtp.close() } }
        executor.shutdown()
        onStuck?.invoke()
    }

    fun close() {
        if (closed) return
        closed = true
        // After any running transaction, on the PTP thread: MtpDevice isn't safe to close concurrently.
        runCatching {
            executor.execute {
                runCatching { mtp.close() }
                runCatching { connection.close() }
            }
            executor.shutdown()
        }.onFailure { runCatching { connection.close() } }
    }
}

/**
 * Watches the USB-C port for cameras (USB Host + android.mtp).
 *
 * Speed tricks vs. a plain file copy:
 *  • The catalog streams in newest first while it is still being read.
 *  • Headers are read with GetPartialObject — 192 KB, not the 60 MB RAW.
 *  • Grid thumbnails come from GetThumb, one small request per visible cell.
 */
class UsbCameraService(private val context: Context) {
    enum class Authorization { UNKNOWN, AUTHORIZED, DENIED }

    var authorization by mutableStateOf(Authorization.UNKNOWN)
    var lastError by mutableStateOf<String?>(null)
    /** A camera-like device is plugged in (whether or not a session is open). */
    var attached by mutableStateOf<UsbDevice?>(null)

    var makeSession: ((String) -> CameraSession)? = null
    var onSession: ((CameraSession) -> Unit)? = null

    private val usb = context.getSystemService(UsbManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var camera: MtpCamera? = null
    private var session: CameraSession? = null
    private var jobs = mutableListOf<Job>()
    private var eventSignal: CancellationSignal? = null
    private val permissionAction = "${context.packageName}.USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> device?.let {
                    if (isCamera(it)) { attached = it; connect(it) } else sonyHint(it)?.let { h -> lastError = h }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (device != null && device.deviceName == camera?.device?.deviceName) drop("Disconnected")
                    else if (device?.deviceName == attached?.deviceName) attached = null
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    authorization = if (granted) Authorization.AUTHORIZED else Authorization.DENIED
                    if (granted && device != null) open(device)
                }
            }
        }
    }

    fun start() {
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        ContextCompat.registerReceiver(context, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        scanAttached()
    }

    /** Looks at what's already plugged in (app launched by the USB attach intent, or cold start). */
    fun scanAttached(any: Boolean = false) {
        val devices = usb?.deviceList?.values.orEmpty()
        val cam = devices.firstOrNull { isCamera(it) } ?: if (any) devices.firstOrNull() else null
        attached = cam
        if (cam != null) connect(cam) else devices.firstNotNullOfOrNull { sonyHint(it) }?.let { lastError = it }
    }

    /**
     * USB class 6 = Still Image (PTP), on the device or on any interface. Some cameras (Sony in
     * "MTP" mode, others) expose MTP as a vendor-specific 0xFF interface named "MTP" instead.
     */
    fun isCamera(d: UsbDevice): Boolean =
        d.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
            (0 until d.interfaceCount).any { i ->
                val f = d.getInterface(i)
                f.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                    (f.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && f.name?.contains("MTP", ignoreCase = true) == true)
            }

    /** A Sony body in Mass Storage / PC Remote mode shows up, but not as a camera we can open. */
    private fun sonyHint(d: UsbDevice): String? =
        if (d.vendorId == SONY_VENDOR_ID && !isCamera(d)) SONY_MTP_HINT else null

    fun connect(device: UsbDevice) {
        if (camera?.device?.deviceName == device.deviceName && camera?.closed == false) return
        val m = usb ?: return
        if (m.hasPermission(device)) {
            authorization = Authorization.AUTHORIZED
            open(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(permissionAction).setPackage(context.packageName), flags)
            m.requestPermission(device, pi)
        }
    }

    private var opening = false

    private fun open(device: UsbDevice) {
        val make = makeSession ?: return
        if (camera?.device?.deviceName == device.deviceName && camera?.closed == false) return
        if (opening) return
        opening = true
        val reset = resetOnOpen.also { resetOnOpen = false }
        scope.launch {
            // USB I/O off the main thread: draining a stuck transfer can take a moment.
            val conn = withContext(Dispatchers.IO) { usb?.openDevice(device)?.also { clearPipe(it, device, reset) } }
            try { finishOpen(device, make, conn) } finally { opening = false }
        }
    }

    private fun finishOpen(device: UsbDevice, make: (String) -> CameraSession, conn: UsbDeviceConnection?) {
        val mtp = MtpDevice(device)
        if (conn == null || !mtp.open(conn)) {
            conn?.close()
            lastError = if (device.vendorId == SONY_VENDOR_ID) SONY_MTP_HINT
            else "Couldn't open the camera. Set its USB mode to PTP/MTP, and close other apps that may be importing from it."
            return
        }
        lastError = null
        val cam = MtpCamera(device, conn, mtp)
        camera = cam
        // A transaction hung: drop this session (imports pause) and reopen if still plugged in.
        cam.onStuck = {
            scope.launch {
                if (camera === cam) {
                    resetOnOpen = true      // the hung transfer's data may still be queued in the camera
                    drop("The camera stopped responding. Reconnecting…")
                    delay(1500)
                    scanAttached()
                }
            }
        }
        val s = make(cam.name)
        s.status = SessionStatus.Connecting
        s.onClose = { if (session === s) shutdown() }
        session = s
        onSession?.invoke(s)
        jobs += scope.launch { catalog(cam, s) }
    }

    fun eject() = shutdown()

    /** Set after a hung transaction: the next open also sends PTP Cancel + Device Reset. */
    private var resetOnOpen = false

    /**
     * Clears a transfer left half-done (a timeout, a yanked cable, the app killed mid-read). The
     * camera may still hold the rest of that data phase; the next OpenSession would then read
     * stale bytes instead of its response, and every reopen fails ("didn't list its files").
     * Drains the bulk-in pipe; after a hang also sends the PTP-over-USB class requests
     * Cancel (0x64) and Device Reset (0x66). Cameras that don't support them just stall the
     * request, which is ignored.
     */
    private fun clearPipe(conn: UsbDeviceConnection, device: UsbDevice, reset: Boolean) {
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE } ?: return
        if (!conn.claimInterface(intf, true)) { Log.w(TAG, "Couldn't claim the camera to clear its USB pipe"); return }
        try {
            if (reset) {
                // Cancel: code 0x4001 + transaction id (unknown here, so all ones), little-endian.
                val cancel = byteArrayOf(0x01, 0x40, -1, -1, -1, -1)
                conn.controlTransfer(0x21, 0x64, 0, intf.id, cancel, cancel.size, 500)
                conn.controlTransfer(0x21, 0x66, 0, intf.id, null, 0, 500)
            }
            val bulkIn = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
                ?: return
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (total < 256L shl 20) {
                val n = conn.bulkTransfer(bulkIn, buf, buf.size, 200)
                if (n <= 0) break
                total += n
            }
            if (total > 0 || reset) Log.i(TAG, "Cleared the USB pipe: $total stale bytes" + if (reset) ", after PTP cancel/reset" else "")
        } finally {
            conn.releaseInterface(intf)
        }
    }

    private fun shutdown() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        eventSignal?.cancel()
        camera?.close()
        camera = null
        session = null
    }

    private fun drop(reason: String) {
        val s = session
        attached = null
        shutdown()
        s?.status = SessionStatus.Failed(reason)
        s?.onClose = null
        s?.close()
    }

    // Catalog

    private val items = LinkedHashMap<Int, CardItem<Int>>()
    private val folders = ConcurrentHashMap<Int, String>()

    private suspend fun catalog(cam: MtpCamera, s: CameraSession) {
        items.clear(); folders.clear()
        s.status = SessionStatus.Cataloging(0)
        val handles = withContext(Dispatchers.IO) { runCatching { allHandles(cam) }.getOrNull() }
        if (handles == null) {
            if (!cam.closed) s.status = SessionStatus.Failed("The camera didn't list its files. Unplug, set USB to PTP/MTP, and try again.")
            return
        }
        // Handles are assigned in creation order on practically every camera: walk from the end
        // so the newest shots arrive first.
        val ordered = handles.reversedArray()
        var lastPush = System.currentTimeMillis()
        for ((i, h) in ordered.withIndex()) {
            if (cam.closed) return
            val item = withContext(Dispatchers.IO) { runCatching { item(cam, h) }.getOrNull() } ?: continue
            items[h] = item
            val now = System.currentTimeMillis()
            if (now - lastPush > 300 || i == ordered.size - 1) {
                lastPush = now
                s.status = SessionStatus.Cataloging(((i + 1) * 100 / ordered.size.coerceAtLeast(1)))
                s.replaceShots(shotsFrom(cam, Pairing.pair(items.values.toList())))
            }
        }
        if (cam.closed) return
        s.status = SessionStatus.Ready            // before the rebuild: the header scan starts only when ready
        s.replaceShots(shotsFrom(cam, Pairing.pair(items.values.toList())))
        jobs += scope.launch { watch(cam, s) }
    }

    /** "All objects" in one call (handle 0), falling back to walking folders for cameras that refuse it. */
    private suspend fun allHandles(cam: MtpCamera): IntArray? {
        val out = ArrayList<Int>()
        val storages = cam.storages()
        for (sid in storages) {
            val all = try { cam.handles(sid, 0) } catch (e: CameraTimeoutException) { throw e } catch (_: Exception) { null }
            if (all != null && all.isNotEmpty() && !isShallow(cam, sid, all)) { out += all.toList(); continue }
            if (all != null) Log.i(TAG, "storage ${Integer.toHexString(sid)}: all-objects listing has ${all.size}; walking folders")
            val queue = ArrayDeque(listOf(-1))                  // -1 = 0xFFFFFFFF = storage root
            var guard = 0
            while (queue.isNotEmpty() && guard++ < 2000) {
                val parent = queue.removeFirst()
                val kids = try { cam.handles(sid, parent) } catch (e: CameraTimeoutException) { throw e } catch (_: Exception) { null } ?: continue
                for (k in kids) {
                    val info = cam.info(k) ?: continue
                    if (info.format == MtpConstants.FORMAT_ASSOCIATION) { folders[k] = info.name ?: ""; queue += k } else out += k
                }
            }
        }
        return if (out.isEmpty() && storages.isEmpty()) null else out.toIntArray()
    }

    /**
     * "All objects" (parent 0) should list the whole card, but some responders treat 0 as the
     * root and return only the top folders (DCIM, MISC): QEMU's MTP device does, and so do some
     * cameras. A short list holding a folder whose children aren't in it is such a listing.
     */
    private suspend fun isShallow(cam: MtpCamera, sid: Int, all: IntArray): Boolean {
        if (all.size > 64) return false
        val listed = all.toHashSet()
        for (h in all) {
            val info = cam.info(h) ?: continue
            if (info.format != MtpConstants.FORMAT_ASSOCIATION) continue
            val kids = try { cam.handles(sid, h) } catch (e: CameraTimeoutException) { throw e } catch (_: Exception) { null } ?: continue
            if (kids.any { it !in listed }) return true
        }
        return false
    }

    private suspend fun item(cam: MtpCamera, h: Int): CardItem<Int>? {
        val info = cam.info(h) ?: return null
        if (info.format == MtpConstants.FORMAT_ASSOCIATION) { folders[h] = info.name ?: ""; return null }
        val name = info.name ?: return null
        if (!Pairing.isMedia(name)) return null
        val parent = info.parent
        val folder = folders[parent] ?: (if (parent == 0) "" else cam.info(parent)?.name ?: parent.toString()).also { folders[parent] = it }
        val date = info.dateCreated.takeIf { it > 0 } ?: info.dateModified
        val locked = info.protectionStatus != MtpConstants.PROTECTION_STATUS_NONE
        // ObjectCompressedSize is 32-bit: ≥ 4 GB videos report 0xFFFFFFFF. Android has no public
        // call for the 64-bit size, so that value stays "unknown": the import then uses GetObject
        // for the whole file instead of chunked reads that would stop at 4 GB.
        return CardItem(h, name, folder, info.compressedSizeLong, date, locked)
    }

    private fun shotsFrom(cam: MtpCamera, pairs: List<PairedShot<Int>>): List<Shot> = pairs.map { p ->
        Shot(
            ShotFile(p.primary.name, p.primary.size, FileLocation.Mtp(cam, p.primary.handle)),
            p.raw?.let { ShotFile(it.name, it.size, FileLocation.Mtp(cam, it.handle)) },
            p.dateMillis,
            p.locked,
        )
    }

    // Shooting while plugged in

    /**
     * PTP ObjectAdded / ObjectRemoved events. Many cameras never send any (or stop after sleeping),
     * so whenever no event arrived for [POLL_IDLE_MS] the handle list is polled as well.
     */
    private suspend fun watch(cam: MtpCamera, s: CameraSession) {
        val signal = CancellationSignal().also { eventSignal = it }
        val lastEvent = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val poller = scope.launch {
            while (isActive && !cam.closed) {
                delay(POLL_IDLE_MS)
                if (System.currentTimeMillis() - lastEvent.get() < POLL_IDLE_MS) continue
                poll(cam, s)
            }
        }
        jobs += poller
        withContext(Dispatchers.IO) {
            try {
                while (!cam.closed) {
                    val ev: MtpEvent = cam.mtp.readEvent(signal)
                    lastEvent.set(System.currentTimeMillis())
                    when (ev.eventCode) {
                        MtpEvent.EVENT_OBJECT_ADDED -> withContext(Dispatchers.Main) { added(cam, s, intArrayOf(ev.objectHandle)) }
                        MtpEvent.EVENT_OBJECT_REMOVED -> withContext(Dispatchers.Main) { removed(s, setOf(ev.objectHandle)) }
                    }
                }
            } catch (_: OperationCanceledException) {
            } catch (e: Exception) {
                Log.i(TAG, "No PTP events (${e.message}); polling instead")
            }
        }
    }

    private suspend fun poll(cam: MtpCamera, s: CameraSession) {
        val now = withContext(Dispatchers.IO) { runCatching { allHandles(cam) }.getOrNull() } ?: return
        val nowSet = now.toHashSet()
        val known = items.keys.toSet()
        val fresh = now.filter { it !in known }
        if (fresh.isNotEmpty()) added(cam, s, fresh.toIntArray())
        val gone = known.filter { it !in nowSet }.toSet()
        if (gone.isNotEmpty()) removed(s, gone)
    }

    private suspend fun added(cam: MtpCamera, s: CameraSession, handles: IntArray) {
        val fresh = withContext(Dispatchers.IO) { handles.toList().mapNotNull { h -> runCatching { item(cam, h) }.getOrNull() } }
        if (fresh.isEmpty()) return
        fresh.forEach { items[it.handle] = it }
        val all = shotsFrom(cam, Pairing.pair(items.values.toList()))
        // A RAW arriving after its JPEG joins that shot instead of becoming a new one.
        for (shot in all) shot.raw?.let { r -> s.shot(shot.id)?.let { if (it.raw == null) it.raw = r } }
        s.add(all)
    }

    private fun removed(s: CameraSession, handles: Set<Int>) {
        handles.forEach { items.remove(it) }
        s.remove { (it.primary.location as? FileLocation.Mtp)?.handle in handles }
    }

    companion object {
        private const val POLL_IDLE_MS = 10_000L
        private const val SONY_VENDOR_ID = 0x054C
        const val SONY_MTP_HINT = "Sony camera found, but not in MTP mode. On the camera: Menu → Network (or Setup) → USB Connection → MTP, then plug it in again."
    }
}
