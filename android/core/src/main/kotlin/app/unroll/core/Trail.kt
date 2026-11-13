package app.unroll.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class TrailPoint(val lat: Double, val lon: Double, val t: Long, val acc: Double)

@Serializable
data class PhotoWalk(
    val id: String,
    val start: Long,
    val end: Long? = null,
    val points: List<TrailPoint> = emptyList(),
    val isDemo: Boolean = false,
) {
    val distanceMeters: Double get() = points.zipWithNext().sumOf { (a, b) -> Geo.distance(a.lat, a.lon, b.lat, b.lon) }
    fun durationMillis(now: Long = System.currentTimeMillis()): Long = (end ?: points.lastOrNull()?.t ?: now) - start
}

data class Fix(val lat: Double, val lon: Double, val acc: Double)

object Geo {
    /** Haversine on a 6371 km sphere; accurate to ~0.5% — plenty for a walk's length. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }

    private const val MAX_GAP = 15 * 60 * 1000L

    /** Location at [t] (epoch ms), interpolated between trail points (max 15 min gap). */
    fun location(walks: List<PhotoWalk>, t: Long, now: Long = System.currentTimeMillis()): Fix? {
        for (w in walks) {
            if (w.start - MAX_GAP > t || (w.end ?: now) + MAX_GAP < t) continue
            val pts = w.points
            if (pts.isEmpty()) continue
            var lo = 0
            var hi = pts.size
            while (lo < hi) { val mid = (lo + hi) / 2; if (pts[mid].t < t) lo = mid + 1 else hi = mid }
            val after = pts.getOrNull(lo)
            val before = pts.getOrNull(lo - 1)
            when {
                before != null && after != null -> {
                    val span = after.t - before.t
                    if (span > MAX_GAP * 2) {
                        return if (abs(t - before.t) < abs(after.t - t)) Fix(before.lat, before.lon, before.acc) else Fix(after.lat, after.lon, after.acc)
                    }
                    val r = if (span > 0) (t - before.t).toDouble() / span else 0.0
                    return Fix(before.lat + (after.lat - before.lat) * r, before.lon + (after.lon - before.lon) * r, maxOf(before.acc, after.acc))
                }
                before != null && t - before.t < MAX_GAP -> return Fix(before.lat, before.lon, before.acc)
                after != null && after.t - t < MAX_GAP -> return Fix(after.lat, after.lon, after.acc)
            }
        }
        return null
    }

    /** Walk overlapping a set of capture times (for the recap card). */
    fun walkCovering(walks: List<PhotoWalk>, times: List<Long>, now: Long = System.currentTimeMillis()): PhotoWalk? {
        val lo = times.minOrNull() ?: return null
        val hi = times.maxOrNull() ?: return null
        return walks.filter { it.start <= hi && (it.end ?: now) >= lo }.maxByOrNull { it.points.size }
    }
}
