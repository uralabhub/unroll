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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

private const val TAG = "UnrollMtp"

/**
 * One open PTP/MTP camera. PTP is strictly one transaction at a time, so every call goes
 * through [lock]; a long download is split into 4 MB GetPartialObject chunks so header reads
 * for the grid can slip in between.
 */
class MtpCamera(val device: UsbDevice, private val connection: UsbDeviceConnection, val mtp: MtpDevice) {
    private val lock = Any()
    @Volatile var closed = false
        private set

    val name: String
        get() = listOfNotNull(mtp.deviceInfo?.manufacturer, mtp.deviceInfo?.model)
            .joinToString(" ").ifBlank { device.productName ?: "USB camera" }

    fun readPartial(handle: Int, offset: Long, length: Int): ByteArray = synchronized(lock) {
        if (closed) throw IOException("Camera disconnected")
        val buf = ByteArray(length)
        // GetPartialObject (PTP 0x101B) takes a 32-bit offset; the 64-bit variant is an Android
        // extension most cameras don't implement, so only use it past 4 GB (long videos).
        val n = if (offset + length <= 0xFFFF_FFFFL) {
            mtp.getPartialObject(handle, offset, length.toLong(), buf)
        } else {
            mtp.getPartialObject64(handle, offset, length.toLong(), buf)
        }
        if (n <= 0) throw IOException("The camera returned no data.")
        if (n.toInt() == length) buf else buf.copyOf(n.toInt())
    }

    fun copyObject(handle: Int, size: Long, out: OutputStream, progress: (Double) -> Unit) {
        val chunk = 4 shl 20
        var off = 0L
        while (off < size) {
            val n = minOf(chunk.toLong(), size - off).toInt()
            val b = readPartial(handle, off, n)
            out.write(b)
            off += b.size
            progress(off.toDouble() / size)
            if (b.size < n) break
        }
        if (off < size) throw IOException("Transfer stopped at ${off * 100 / size}%")
    }

    fun thumbnail(handle: Int): ByteArray? = synchronized(lock) { if (closed) null else mtp.getThumbnail(handle) }

    fun info(handle: Int) = synchronized(lock) { if (closed) null else mtp.getObjectInfo(handle) }

    fun handles(storage: Int, parent: Int): IntArray? = synchronized(lock) { if (closed) null else mtp.getObjectHandles(storage, 0, parent) }

    fun storages(): IntArray = synchronized(lock) { if (closed) IntArray(0) else mtp.storageIds ?: IntArray(0) }

    fun close() {
        if (closed) return
        closed = true
        runCatching { mtp.close() }
        runCatching { connection.close() }
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
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> device?.let { if (isCamera(it)) { attached = it; connect(it) } }
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
        cam?.let(::connect)
    }

    /** USB class 6 = Still Image (PTP), on the device or on any interface. */
    fun isCamera(d: UsbDevice): Boolean =
        d.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

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

    private fun open(device: UsbDevice) {
        val make = makeSession ?: return
        val conn = usb?.openDevice(device)
        val mtp = MtpDevice(device)
        if (conn == null || !mtp.open(conn)) {
            conn?.close()
            lastError = "Couldn't open the camera. Set its USB mode to PTP/MTP, and close other apps that may be importing from it."
            return
        }
        val cam = MtpCamera(device, conn, mtp)
        camera = cam
        val s = make(cam.name)
        s.status = SessionStatus.Connecting
        s.onClose = { if (session === s) shutdown() }
        session = s
        onSession?.invoke(s)
        jobs += scope.launch { catalog(cam, s) }
    }

    fun eject() = shutdown()

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
    private val folders = HashMap<Int, String>()

    private suspend fun catalog(cam: MtpCamera, s: CameraSession) {
        items.clear(); folders.clear()
        s.status = SessionStatus.Cataloging(0)
        val handles = withContext(Dispatchers.IO) { allHandles(cam) }
        if (handles == null) {
            s.status = SessionStatus.Failed("The camera didn't list its files. Unplug, set USB to PTP/MTP, and try again.")
            return
        }
        // Handles are assigned in creation order on practically every camera: walk from the end
        // so the newest shots arrive first.
        val ordered = handles.reversedArray()
        var lastPush = System.currentTimeMillis()
        for ((i, h) in ordered.withIndex()) {
            val item = withContext(Dispatchers.IO) { runCatching { item(cam, h) }.getOrNull() } ?: continue
            items[h] = item
            val now = System.currentTimeMillis()
            if (now - lastPush > 300 || i == ordered.size - 1) {
                lastPush = now
                s.status = SessionStatus.Cataloging(((i + 1) * 100 / ordered.size.coerceAtLeast(1)))
                s.replaceShots(shotsFrom(cam, Pairing.pair(items.values.toList())))
            }
        }
        s.status = SessionStatus.Ready            // before the rebuild: the header scan starts only when ready
        s.replaceShots(shotsFrom(cam, Pairing.pair(items.values.toList())))
        jobs += scope.launch { watch(cam, s) }
    }

    /** "All objects" in one call (handle 0), falling back to walking folders for cameras that refuse it. */
    private fun allHandles(cam: MtpCamera): IntArray? {
        val out = ArrayList<Int>()
        for (sid in cam.storages()) {
            val all = runCatching { cam.handles(sid, 0) }.getOrNull()
            if (all != null && all.isNotEmpty()) { out += all.toList(); continue }
            val queue = ArrayDeque(listOf(-1))                  // -1 = 0xFFFFFFFF = storage root
            var guard = 0
            while (queue.isNotEmpty() && guard++ < 2000) {
                val parent = queue.removeFirst()
                val kids = runCatching { cam.handles(sid, parent) }.getOrNull() ?: continue
                for (k in kids) {
                    val info = cam.info(k) ?: continue
                    if (info.format == MtpConstants.FORMAT_ASSOCIATION) { folders[k] = info.name ?: ""; queue += k } else out += k
                }
            }
        }
        return if (out.isEmpty() && cam.storages().isEmpty()) null else out.toIntArray()
    }

    private fun item(cam: MtpCamera, h: Int): CardItem<Int>? {
        val info = cam.info(h) ?: return null
        if (info.format == MtpConstants.FORMAT_ASSOCIATION) { folders[h] = info.name ?: ""; return null }
        val name = info.name ?: return null
        if (!Pairing.isMedia(name)) return null
        val parent = info.parent
        val folder = folders.getOrPut(parent) { if (parent == 0) "" else cam.info(parent)?.name ?: parent.toString() }
        val date = info.dateCreated.takeIf { it > 0 } ?: info.dateModified
        val locked = info.protectionStatus != MtpConstants.PROTECTION_STATUS_NONE
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

    /** PTP ObjectAdded / ObjectRemoved events; polling the handle list when the camera sends none. */
    private suspend fun watch(cam: MtpCamera, s: CameraSession) {
        val signal = CancellationSignal().also { eventSignal = it }
        val eventsWork = withContext(Dispatchers.IO) {
            try {
                while (!cam.closed) {
                    val ev: MtpEvent = cam.mtp.readEvent(signal)
                    when (ev.eventCode) {
                        MtpEvent.EVENT_OBJECT_ADDED -> withContext(Dispatchers.Main) { added(cam, s, intArrayOf(ev.objectHandle)) }
                        MtpEvent.EVENT_OBJECT_REMOVED -> withContext(Dispatchers.Main) { removed(s, setOf(ev.objectHandle)) }
                    }
                }
                true
            } catch (_: OperationCanceledException) {
                true
            } catch (e: Exception) {
                Log.i(TAG, "No PTP events (${e.message}); polling instead")
                false
            }
        }
        if (eventsWork) return
        while (scope.isActive && !cam.closed) {
            delay(6000)
            val now = withContext(Dispatchers.IO) { runCatching { allHandles(cam) }.getOrNull() } ?: continue
            val known = items.keys
            val fresh = now.filter { it !in known }
            if (fresh.isNotEmpty()) added(cam, s, fresh.toIntArray())
            val gone = known.filter { it !in now.toSet() }.toSet()
            if (gone.isNotEmpty()) removed(s, gone)
        }
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
}
