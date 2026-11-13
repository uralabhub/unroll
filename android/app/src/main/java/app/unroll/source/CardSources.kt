package app.unroll.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.DocumentsContract
import app.unroll.core.CardItem
import app.unroll.core.DemoRoll
import app.unroll.core.FolderScanner
import app.unroll.core.Pairing
import app.unroll.core.RicohOrder
import app.unroll.core.ShotFormat
import app.unroll.core.SplitMix
import app.unroll.core.TiffBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** SD card in a USB-C reader or any folder, through the Storage Access Framework. */
object SafSource {
    /** Scans the picked tree (prefers its DCIM folder). Folder + stem pairing as on the camera. */
    suspend fun scan(context: Context, tree: Uri): List<Shot> = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS,
        )
        class Row(val id: String, val name: String, val dir: Boolean, val size: Long, val modified: Long, val flags: Int)
        fun list(id: String): List<Row> {
            val out = mutableListOf<Row>()
            cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out += Row(
                        c.getString(0), c.getString(1) ?: "",
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4), c.getInt(5),
                    )
                }
            }
            return out
        }
        val top = list(rootId)
        val start = top.firstOrNull { it.dir && it.name.equals("DCIM", ignoreCase = true) }?.id ?: rootId
        val items = mutableListOf<CardItem<Uri>>()
        val queue = ArrayDeque(listOf(start to ""))
        while (queue.isNotEmpty()) {
            val (id, folder) = queue.removeFirst()
            for (r in list(id)) {
                if (r.name.startsWith(".")) continue
                if (r.dir) { queue += r.id to "$folder/${r.name}"; continue }
                if (!Pairing.isMedia(r.name)) continue
                // Cameras implement "Protect" with the FAT read-only attribute; the provider reports
                // it as a document without FLAG_SUPPORTS_WRITE.
                val locked = r.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE == 0
                items += CardItem(DocumentsContract.buildDocumentUriUsingTree(tree, r.id), r.name, folder, r.size, r.modified, locked)
            }
        }
        Pairing.pair(items).map { p ->
            Shot(
                ShotFile(p.primary.name, p.primary.size, FileLocation.Document(p.primary.handle)),
                p.raw?.let { ShotFile(it.name, it.size, FileLocation.Document(it.handle)) },
                p.dateMillis, p.locked,
            )
        }
    }

    fun displayName(tree: Uri): String =
        DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/').ifBlank { "SD card" }
}

/** Local folders (the demo roll) through the same pairing rules, via the core scanner. */
object LocalSource {
    suspend fun scan(root: File): List<Shot> = withContext(Dispatchers.IO) {
        FolderScanner.scan(root).map { p ->
            Shot(
                ShotFile(p.primary.name, p.primary.size, FileLocation.Local(p.primary.handle)),
                p.raw?.let { ShotFile(it.name, it.size, FileLocation.Local(it.handle)) },
                p.dateMillis, p.locked,
            )
        }
    }
}

/**
 * Writes the demo roll (DCIM/100_FUJI, 42 shots) with real EXIF + Fujifilm maker notes, RAF twins,
 * read-only "protected" files and a matching GPS walk, so the whole app works without a camera.
 */
object DemoRollWriter {
    fun folder(context: Context) = File(context.cacheDir, "DemoRoll")

    /** Writes the roll (idempotent per day) and returns its root folder. */
    suspend fun prepare(context: Context): File = withContext(Dispatchers.IO) {
        val root = folder(context)
        val stamp = File(root, ".day")
        val today = LocalDate.now().toString()
        if (stamp.exists() && stamp.readText() == today) return@withContext root
        root.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        val dir = File(root, DemoRoll.FOLDER).apply { mkdirs() }
        for (i in 0 until DemoRoll.COUNT) writeShot(i, dir)
        stamp.writeText(today)
        root
    }

    private fun writeShot(i: Int, dir: File) {
        val s = DemoRoll.spec(i)
        val bmp = paint(i, s.look, s.width, s.height)
        val base = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }.toByteArray()
        bmp.recycle()
        val jpeg = TiffBuilder.jpeg(base, DemoRoll.exif(s))
        val jpg = File(dir, s.stem + ".JPG")
        jpg.writeBytes(jpeg)
        if (s.withRAF) File(dir, s.stem + ".RAF").apply { writeBytes(TiffBuilder.raf(jpeg)); setLastModified(s.date.toEpochMilli()) }
        jpg.setLastModified(s.date.toEpochMilli())
        if (s.locked) jpg.setWritable(false, false)            // "Protect" on camera = read-only file
    }

    /** Procedural street scene: sky, sun, city silhouette, street; film tint or mono; grain. */
    private fun paint(i: Int, look: DemoRoll.Look, w: Int, h: Int): Bitmap {
        val rng = SplitMix((i + 7).toLong() * -0x61c8864680b583ebL)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val hour = i.toFloat() / DemoRoll.COUNT
        val skyTop = Color.HSVToColor(floatArrayOf(((0.58f - hour * 0.5f) * 360 + 360) % 360, 0.55f, 0.95f - hour * 0.35f))
        val skyLow = Color.HSVToColor(floatArrayOf((0.08f + hour * 0.02f) * 360, 0.35f + hour * 0.4f, 1f))
        val horizon = h * (0.55f + rng.next().toFloat() * 0.15f)
        p.shader = LinearGradient(0f, 0f, 0f, horizon, skyTop, skyLow, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), horizon, p)
        p.shader = null

        val r = w * (0.05f + rng.next().toFloat() * 0.05f)
        val sunX = w * (0.15f + rng.next().toFloat() * 0.7f)
        val sunY = horizon * (0.25f + hour * 0.6f)
        p.color = Color.argb(217, 255, 255, 255)
        c.drawCircle(sunX, sunY, r, p)

        var x = 0f
        val shade = Color.HSVToColor(floatArrayOf(0.62f * 360, 0.35f, 0.22f + rng.next().toFloat() * 0.1f))
        while (x < w) {
            val bw = w * (0.04f + rng.next().toFloat() * 0.09f)
            val bh = h * (0.12f + rng.next().toFloat() * 0.38f)
            p.color = shade
            c.drawRect(x, horizon - bh, x + bw, horizon + 2, p)
            p.color = Color.argb(128, 255, 217, 128)
            var wy = horizon - bh + 8
            while (wy < horizon - 8) {
                if (rng.next() > 0.55) c.drawRect(x + bw * 0.3f, wy, x + bw * 0.42f, wy + 5, p)
                wy += 16
            }
            x += bw + rng.next().toFloat() * 6
        }
        p.shader = LinearGradient(0f, horizon, 0f, h.toFloat(), Color.rgb(46, 46, 46), Color.rgb(13, 13, 13), Shader.TileMode.CLAMP)
        c.drawRect(0f, horizon, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.argb(64, 255, 255, 255)
        p.strokeWidth = 4f
        c.drawLine(w / 2f, horizon, w * 0.1f, h.toFloat(), p)
        c.drawLine(w / 2f, horizon, w * 0.9f, h.toFloat(), p)

        if (look.mono) {
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            for (k in px.indices) {
                val v = px[k]
                val l = ((Color.red(v) * 0.3f + Color.green(v) * 0.59f + Color.blue(v) * 0.11f)).toInt().coerceIn(0, 255)
                px[k] = Color.rgb(l, l, l)
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
        } else {
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            val (tr, tg, tb) = look.tint
            p.color = Color.rgb((tr * 255).toInt(), (tg * 255).toInt(), (tb * 255).toInt())
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.xfermode = null
        }
        if (look.grain > 0) {
            repeat(w * h / 900) {
                p.color = if (rng.next() > 0.5) Color.argb(15, 255, 255, 255) else Color.argb(15, 0, 0, 0)
                val gx = (rng.next() * w).toFloat(); val gy = (rng.next() * h).toFloat()
                c.drawRect(gx, gy, gx + 2, gy + 2, p)
            }
        }
        return bmp
    }
}

/**
 * Ricoh GR III / IIIx Wi-Fi (reverse-engineered HTTP API, no auth, camera at 192.168.0.1). Beta.
 * Join the camera's Wi-Fi first. GR II uses a different API and is not supported; GR IV is untested.
 */
object RicohSource {
    private const val BASE = "http://192.168.0.1/v1/"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Props(val model: String? = null)
    @Serializable private data class Dir(val name: String, val files: List<String> = emptyList())
    @Serializable private data class Listing(val errCode: Int? = null, val dirs: List<Dir> = emptyList())

    /** The Wi-Fi network (it has no internet, so the default route may be mobile data). */
    private fun wifi(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    suspend fun connect(context: Context): Pair<String, List<Shot>> = withContext(Dispatchers.IO) {
        val net = wifi(context)
        fun loc(path: String) = FileLocation.Http(BASE + path, net)
        val model = runCatching { json.decodeFromString(Props.serializer(), String(Http.get(loc("props"), null).second)).model }.getOrNull() ?: "Ricoh GR"
        val listing = json.decodeFromString(Listing.serializer(), String(Http.get(loc("photos"), null).second))
        if (listing.errCode != null && listing.errCode != 200 && listing.dirs.isEmpty()) throw IOException("Camera answered ${listing.errCode}")
        // The list carries no dates or sizes. Folder + file numbers are chronological and stable,
        // so they give an ordering date (and a stable shot ID for the import ledger).
        val byStem = listing.dirs.flatMap { d -> d.files.map { d.name to it } }
            .groupBy { "${it.first}/${it.second.substringBeforeLast('.')}" }.toSortedMap()
        var index = 0
        val shots = byStem.values.mapNotNull { group ->
            index += 1
            fun file(g: Pair<String, String>) = ShotFile(g.second, ShotFile.UNKNOWN_SIZE, loc("photos/${g.first}/${g.second}"))
            val dev = group.firstOrNull { !ShotFormat.fromFilename(it.second).isRAW }
            val raw = group.firstOrNull { ShotFormat.fromFilename(it.second).isRAW }
            val main = dev ?: raw ?: return@mapNotNull null
            Shot(file(main), if (dev != null) raw?.let(::file) else null, (RicohOrder.orderKey(main.first, main.second) ?: index.toLong()) * 1000, false)
        }
        model to shots
    }
}
