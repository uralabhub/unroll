package app.unroll.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import app.unroll.R
import app.unroll.UnrollApplication
import app.unroll.core.FilmRecipe
import app.unroll.core.FrameGeometry
import app.unroll.core.FrameOptions
import app.unroll.core.FrameStyle
import app.unroll.core.ShotMeta
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil

enum class Align { LEFT, CENTER, RIGHT }

object Text {
    /** Nunito (bundled) everywhere; [mono] means tabular figures, so numbers align. */
    private val cache = HashMap<Int, Typeface>()

    private fun face(weight: Int): Typeface = synchronized(cache) {
        cache.getOrPut(weight) {
            val res = when { weight >= 800 -> R.font.nunito_extrabold; weight >= 700 -> R.font.nunito_bold; weight >= 600 -> R.font.nunito_semibold; weight >= 500 -> R.font.nunito_medium; else -> R.font.nunito_regular }
            runCatching { ResourcesCompat.getFont(UnrollApplication.context, res) }.getOrNull() ?: Typeface.create(Typeface.DEFAULT, weight.coerceIn(1, 1000), false)
        }
    }

    /** Draws single-line text vertically centred on [y]. */
    fun draw(c: Canvas, s: String, x: Float, y: Float, size: Float, weight: Int, color: Int, align: Align, tracking: Float = 0f, mono: Boolean = false) {
        if (s.isEmpty()) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size; typeface = face(weight); this.color = color; if (mono) fontFeatureSettings = "tnum"
            letterSpacing = if (size > 0) tracking / size else 0f
        }
        val w = p.measureText(s)
        val fm = p.fontMetrics
        val baseline = y - (fm.ascent + fm.descent) / 2
        val left = when (align) { Align.LEFT -> x; Align.CENTER -> x - w / 2; Align.RIGHT -> x - w }
        c.drawText(s, left, baseline, p)
    }

    fun width(s: String, size: Float, weight: Int, mono: Boolean = false): Float =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face(weight); if (mono) fontFeatureSettings = "tnum" }.measureText(s)
}

private fun white(a: Float, alpha: Float = 1f) = Color.argb((alpha * 255).toInt(), (a * 255).toInt(), (a * 255).toInt(), (a * 255).toInt())
private fun withAlpha(c: Int, alpha: Float) = Color.argb((alpha * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

/** Renders a framed export at the image's resolution (the same geometry as the iOS FrameRenderer). */
object FrameRenderer {
    fun render(img: Bitmap, meta: ShotMeta?, o: FrameOptions): Bitmap {
        val g = FrameGeometry.of(img.width, img.height, o, meta?.recipe?.rows?.size)
        val u = g.u.toFloat()
        val bg = if (o.style == FrameStyle.FILM) white(0.06f) else if (o.dark) white(0.07f) else white(0.985f)
        val ink = if (o.dark || o.style == FrameStyle.FILM) white(0.95f) else white(0.08f)
        val sub = withAlpha(ink, 0.55f)
        val W = ceil(g.canvasW).toInt(); val H = ceil(g.canvasH).toInt()
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(bg)
        val px = g.photoX.toFloat(); val py = g.photoY.toFloat()
        c.drawBitmap(img, null, RectF(px, py, px + img.width, py + img.height), Paint(Paint.FILTER_BITMAP_FLAG))
        val photo = RectF(px, py, px + img.width, py + img.height)
        val belowTop = photo.bottom
        val belowH = H - photo.bottom

        val camera = meta?.cameraName ?: "Camera"
        val lens = meta?.lens
        val exposure = if (o.showExposure) meta?.exposureLine ?: "" else ""
        val recipeLine = if (o.showRecipe) meta?.recipe?.shortLine ?: meta?.look else null
        val dateText = if (o.showDate) meta?.captureDate()?.atZone(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())) else null
        val place = if (o.showPlace) o.placeName else null
        val cx = W / 2f
        val SEMI = 600; val REG = 400; val MED = 500; val BOLD = 700

        when (o.style) {
            FrameStyle.GALLERY, FrameStyle.POLAROID -> {
                val center = belowTop + (if (o.style == FrameStyle.POLAROID) 7f else 5.5f) * u
                Text.draw(c, camera, cx, center - 1.8f * u, 2.9f * u, SEMI, ink, Align.CENTER)
                val line2 = listOfNotNull(exposure, recipeLine).filter { it.isNotEmpty() }.joinToString("   ·   ")
                Text.draw(c, line2, cx, center + 2.1f * u, 1.85f * u, REG, sub, Align.CENTER)
                val line3 = listOfNotNull(lens, place, dateText).joinToString("   ·   ")
                Text.draw(c, line3, cx, center + 5.0f * u, 1.55f * u, REG, sub, Align.CENTER)
            }
            FrameStyle.CAPTION -> {
                // Centre of the 9u caption band right under the photo (not of all the space a tall ratio adds).
                val y = belowTop + minOf(belowH / 2, 4.5f * u)
                Text.draw(c, camera, photo.left + 3 * u, y - 1.3f * u, 2.2f * u, BOLD, ink, Align.LEFT)
                Text.draw(c, listOfNotNull(lens, place).joinToString(" · "), photo.left + 3 * u, y + 1.7f * u, 1.4f * u, REG, sub, Align.LEFT)
                Text.draw(c, exposure, photo.right - 3 * u, y - 1.3f * u, 1.9f * u, SEMI, ink, Align.RIGHT)
                Text.draw(c, listOfNotNull(recipeLine, dateText).joinToString(" · "), photo.right - 3 * u, y + 1.7f * u, 1.4f * u, REG, sub, Align.RIGHT)
                c.drawRect(W / 2f + 2 * u, y - 2.4f * u, W / 2f + 2 * u + maxOf(1f, u * 0.12f), y + 2.4f * u, Paint().apply { color = withAlpha(sub, 0.3f * 0.55f) })
            }
            FrameStyle.RECIPE -> {
                var y = belowTop + 4 * u
                Text.draw(c, camera + (lens?.let { "  ·  $it" } ?: ""), photo.left, y, 2.2f * u, SEMI, ink, Align.LEFT)
                Text.draw(c, exposure, photo.right, y, 1.7f * u, REG, sub, Align.RIGHT)
                y += 4 * u
                val rows = meta?.recipe?.rows
                if (rows != null) {
                    for ((k, v) in rows) {
                        Text.draw(c, k.uppercase(), photo.left, y, 1.25f * u, MED, sub, Align.LEFT, tracking = 0.15f * u)
                        Text.draw(c, v, photo.right, y, 1.5f * u, MED, ink, Align.RIGHT)
                        y += 2.6f * u
                    }
                } else meta?.look?.let { Text.draw(c, it, photo.left, y, 1.5f * u, MED, ink, Align.LEFT) }
            }
            FrameStyle.FILM -> {
                // Sprocket holes + edge print. The edge print stays neutral grey: the app is monochrome.
                val hw = 2.2f * u; val hh = 3.2f * u
                val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white(0.85f) }
                var x = 1.5f * u
                while (x < W - hw) {
                    c.drawRoundRect(RectF(x, 2.9f * u, x + hw, 2.9f * u + hh), 0.4f * u, 0.4f * u, hole)
                    c.drawRoundRect(RectF(x, H - 6.1f * u, x + hw, H - 6.1f * u + hh), 0.4f * u, 0.4f * u, hole)
                    x += 4.4f * u
                }
                val edgeInk = white(0.7f)
                val edge = listOfNotNull(meta?.recipe?.filmSimulation?.uppercase(), meta?.isoText).joinToString("  ")
                Text.draw(c, edge.ifEmpty { camera.uppercase() }, photo.left + u, photo.top - 1.6f * u, 1.3f * u, BOLD, edgeInk, Align.LEFT, tracking = 0.3f * u, mono = true)
                Text.draw(c, listOf(camera, exposure).joinToString("   "), photo.left + u, photo.bottom + 2 * u, 1.3f * u, MED, edgeInk, Align.LEFT, tracking = 0.15f * u, mono = true)
            }
            FrameStyle.MINIMAL -> Unit
        }

        if (o.watermark) {
            if (o.style == FrameStyle.MINIMAL || o.style == FrameStyle.FILM) {
                Text.draw(c, "Unroll", W - 1.5f * u, H - 1.2f * u, 1.0f * u, MED, sub, Align.RIGHT)
            } else {
                Text.draw(c, "Unroll", W - 2 * u, H - 2 * u, 1.1f * u, MED, withAlpha(sub, 0.4f * 0.55f), Align.RIGHT)
            }
        }
        return out
    }
}

/** "Photo Walk Recap" — the shareable 1080×1920 story card that doubles as the growth loop. */
object RecapRenderer {
    class Input(
        val title: String,
        val subtitle: String,
        val photos: List<Bitmap>,
        val shotCount: Int,
        val keeperCount: Int,
        val distanceKm: Double?,
        val durationMillis: Long?,
        val cameras: List<String>,
        val looks: List<String>,
        val route: List<Pair<Double, Double>>,
        val watermark: Boolean,
    )

    const val W = 1080
    const val H = 1920

    fun render(r: Input): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val ink = white(0.96f); val sub = white(0.96f, 0.5f)
        Text.draw(c, "PHOTO WALK", 72f, 108f, 26f, 500, sub, Align.LEFT, tracking = 5f, mono = true)
        Text.draw(c, r.title, 72f, 175f, 64f, 700, ink, Align.LEFT)
        Text.draw(c, r.subtitle, 72f, 240f, 32f, 400, sub, Align.LEFT)

        val gridTop = 300f; val gap = 8f; val side = 72f
        val photos = r.photos.take(6)
        val hasMap = r.route.size >= 2
        val cols = if (photos.size <= 1) 1 else 2
        val rows = maxOf(1, ceil(photos.size / cols.toDouble()).toInt())
        val gridH = if (hasMap) 820f else 1080f
        val cellW = (W - side * 2 - gap * (cols - 1)) / cols
        val cellH = (gridH - gap * (rows - 1)) / rows
        for ((i, img) in photos.withIndex()) {
            val col = i % cols; val row = i / cols
            // A lone last photo spans the full row.
            val span = i == photos.size - 1 && photos.size % cols == 1 && cols == 2
            val rect = RectF(side + col * (cellW + gap), gridTop + row * (cellH + gap), 0f, 0f)
            rect.right = rect.left + if (span) W - side * 2 else cellW
            rect.bottom = rect.top + cellH
            drawAspectFill(c, img, rect)
        }
        var y = gridTop + gridH + 36
        if (hasMap) {
            val mr = RectF(side, y, W - side, y + 330)
            drawRoute(c, r.route, mr)
            y = mr.bottom + 40
        }

        val stats = mutableListOf("${r.shotCount}" to "shots", "${r.keeperCount}" to "keepers")
        r.distanceKm?.let { if (it > 0.05) stats += String.format(Locale.US, "%.1f", it) to "km" }
        r.durationMillis?.let { t ->
            if (t > 60_000) { val h = t / 3_600_000; val m = t % 3_600_000 / 60_000; stats += (if (h > 0) "${h}h$m" else "${m}m") to "walked" }
        }
        val colW = (W - side * 2) / stats.size
        for ((i, s) in stats.withIndex()) {
            val x = side + colW * i
            Text.draw(c, s.first, x, y + 22, 52f, 500, ink, Align.LEFT, mono = true)
            Text.draw(c, s.second.uppercase(), x, y + 78, 20f, 500, sub, Align.LEFT, tracking = 3f, mono = true)
        }
        y += 130
        var x = side
        val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = sub }
        for (look in r.looks.take(4)) {
            val w = Text.width(look, 26f, 500) + 40
            if (x + w > W - side) break
            val rect = RectF(x, y, x + w, y + 52)
            c.drawRoundRect(RectF(rect.left + 1, rect.top + 1, rect.right - 1, rect.bottom - 1), 26f, 26f, chip)
            Text.draw(c, look, rect.centerX(), rect.centerY(), 26f, 500, ink, Align.CENTER)
            x += w + 12
        }
        Text.draw(c, r.cameras.joinToString(" + "), side, H - 90f, 28f, 500, sub, Align.LEFT)
        if (r.watermark) Text.draw(c, "made with Unroll", W - side, H - 90f, 26f, 600, sub, Align.RIGHT)
        return out
    }

    /**
     * The walk as a white line on a dark panel with a faint grid. No map tiles: that would need a
     * maps API key and network, and it keeps the card monochrome and offline.
     */
    fun drawRoute(c: Canvas, route: List<Pair<Double, Double>>, box: RectF) {
        c.drawRect(box, Paint().apply { color = white(0.09f) })
        val grid = Paint().apply { color = white(1f, 0.06f); strokeWidth = 1f }
        var gx = box.left
        while (gx <= box.right) { c.drawLine(gx, box.top, gx, box.bottom, grid); gx += 48 }
        var gy = box.top
        while (gy <= box.bottom) { c.drawLine(box.left, gy, box.right, gy, grid); gy += 48 }
        val lats = route.map { it.first }; val lons = route.map { it.second }
        val midLat = Math.toRadians((lats.min() + lats.max()) / 2)
        val spanX = maxOf((lons.max() - lons.min()) * kotlin.math.cos(midLat), 1e-5)
        val spanY = maxOf(lats.max() - lats.min(), 1e-5)
        val pad = 36f
        val k = minOf((box.width() - pad * 2) / spanX, (box.height() - pad * 2) / spanY).toFloat()
        val ox = box.centerX() - (spanX * k / 2).toFloat()
        val oy = box.centerY() + (spanY * k / 2).toFloat()
        val path = Path()
        route.forEachIndexed { i, (la, lo) ->
            val px = ox + ((lo - lons.min()) * kotlin.math.cos(midLat) * k).toFloat()
            val py = oy - ((la - lats.min()) * k).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        c.save(); c.clipRect(box)
        line.color = white(0f, 0.5f); line.strokeWidth = 14f; c.drawPath(path, line)
        line.color = Color.WHITE; line.strokeWidth = 6f; c.drawPath(path, line)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val first = route.first(); val last = route.last()
        fun pt(p: Pair<Double, Double>) = (ox + ((p.second - lons.min()) * kotlin.math.cos(midLat) * k).toFloat()) to (oy - ((p.first - lats.min()) * k).toFloat())
        pt(first).let { (a, b) -> c.drawCircle(a, b, 10f, dot) }
        pt(last).let { (a, b) -> c.drawRect(a - 10, b - 10, a + 10, b + 10, dot) }
        c.restore()
    }

    fun drawAspectFill(c: Canvas, img: Bitmap, rect: RectF) {
        val s = maxOf(rect.width() / img.width, rect.height() / img.height)
        val w = img.width * s; val h = img.height * s
        c.save(); c.clipRect(rect)
        c.drawBitmap(img, null, RectF(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
    }
}

/** Shareable recipe card (1080 wide), as on iOS. */
object RecipeCard {
    fun render(title: String, camera: String?, recipe: FilmRecipe, watermark: Boolean): Bitmap {
        val rows = recipe.rows
        val w = 1080; val h = 360 + rows.size * 76
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val sub = white(1f, 0.5f)
        Text.draw(c, "FILM RECIPE", 80f, 100f, 26f, 500, sub, Align.LEFT, tracking = 5f, mono = true)
        Text.draw(c, title, 80f, 170f, 64f, 700, Color.WHITE, Align.LEFT)
        Text.draw(c, camera ?: "", 80f, 235f, 30f, 400, sub, Align.LEFT)
        c.drawRect(80f, 280f, 1000f, 282f, Paint().apply { color = white(1f, 0.2f) })
        var y = 320f
        for ((k, v) in rows) {
            Text.draw(c, k.uppercase(), 80f, y, 26f, 600, sub, Align.LEFT, tracking = 2f)
            Text.draw(c, v, 1000f, y, 32f, 600, Color.WHITE, Align.RIGHT)
            y += 76
        }
        if (watermark) Text.draw(c, "made with Unroll", 1000f, h - 50f, 24f, 500, white(1f, 0.4f), Align.RIGHT)
        return out
    }
}

/** Greyscale filter, for any colour that must not leak into the monochrome UI. */
fun grayscale(src: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
    return out
}
