package app.unroll.core

import kotlinx.serialization.Serializable

@Serializable
enum class FrameStyle(val label: String) {
    GALLERY("Gallery"), CAPTION("Caption Bar"), POLAROID("Instant"), RECIPE("Recipe Card"), FILM("Film Strip"), MINIMAL("Thin");

    val isPro: Boolean get() = this == RECIPE || this == FILM || this == POLAROID
}

@Serializable
enum class FrameAspect(val label: String, val ratio: Double?) {
    ORIGINAL("Fit", null), SQUARE("1:1", 1.0), PORTRAIT("4:5", 4.0 / 5.0), STORY("9:16", 9.0 / 16.0)
}

@Serializable
data class FrameOptions(
    val style: FrameStyle = FrameStyle.GALLERY,
    val aspect: FrameAspect = FrameAspect.ORIGINAL,
    val dark: Boolean = false,
    val showExposure: Boolean = true,
    val showRecipe: Boolean = true,
    val showDate: Boolean = false,
    val showPlace: Boolean = false,
    val placeName: String? = null,
    val watermark: Boolean = true,
)

/**
 * Geometry of a framed export, shared by the Android renderer and the tests.
 * Everything is in units of [u] = 1% of the photo's long edge.
 */
class FrameGeometry(
    val canvasW: Double,
    val canvasH: Double,
    val photoX: Double,
    val photoY: Double,
    val u: Double,
) {
    companion object {
        fun of(w: Int, h: Int, o: FrameOptions, recipeRows: Int?): FrameGeometry {
            val W = w.toDouble(); val H = h.toDouble()
            val u = maxOf(W, H) / 100
            // Borders (left, top, right, bottom) in units of u.
            val (l, t, r, b) = when (o.style) {
                FrameStyle.GALLERY -> listOf(4.0, 4.0, 4.0, 13.0)
                FrameStyle.CAPTION -> listOf(0.0, 0.0, 0.0, 9.0)
                FrameStyle.POLAROID -> listOf(4.5, 4.5, 4.5, 20.0)
                FrameStyle.RECIPE -> listOf(4.0, 4.0, 4.0, (recipeRows ?: 3) * 2.6 + 12)
                FrameStyle.FILM -> listOf(3.0, 9.0, 3.0, 13.0)
                FrameStyle.MINIMAL -> listOf(2.5, 2.5, 2.5, 2.5)
            }
            var cw = W + (l + r) * u
            var ch = H + (t + b) * u
            var x = l * u
            var y = t * u
            val ratio = o.aspect.ratio
            if (ratio != null) {                                  // grow the shorter side to hit the ratio
                if (cw / ch > ratio) {
                    val nh = cw / ratio
                    y += (nh - ch) / 2 * (if (b > t) 0.6 else 1.0)
                    ch = nh
                } else {
                    val nw = ch * ratio
                    x += (nw - cw) / 2
                    cw = nw
                }
            }
            return FrameGeometry(cw, ch, x, y, u)
        }
    }
}
