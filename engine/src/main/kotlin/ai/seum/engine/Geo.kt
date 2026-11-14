package ai.seum.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/**
 * Local flat-earth projection around a reference point. Good to well under 1 m at the
 * distances the engine cares about (< 500 m), and much cheaper than haversine.
 */
class LocalProjection(private val ref: LatLon) {
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(ref.lat * PI / 180.0)

    fun x(p: LatLon) = (p.lon - ref.lon) * mPerDegLon
    fun y(p: LatLon) = (p.lat - ref.lat) * mPerDegLat
}

object Geo {
    /** Same grid as data/build_db.py: 0.0005 deg cells. */
    const val CELL_DEG = 0.0005

    fun cellOf(lat: Double, lon: Double): Long =
        floor(lat / CELL_DEG).toLong() * 1_000_000L + floor(lon / CELL_DEG).toLong()

    /** Cells within [radiusM] of the point (square block, so a superset). */
    fun cellsAround(p: LatLon, radiusM: Double): List<Long> {
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * cos(p.lat * PI / 180.0))
        val la0 = floor((p.lat - dLat) / CELL_DEG).toLong()
        val la1 = floor((p.lat + dLat) / CELL_DEG).toLong()
        val lo0 = floor((p.lon - dLon) / CELL_DEG).toLong()
        val lo1 = floor((p.lon + dLon) / CELL_DEG).toLong()
        val out = ArrayList<Long>()
        for (a in la0..la1) for (o in lo0..lo1) out.add(a * 1_000_000L + o)
        return out
    }

    fun distanceM(a: LatLon, b: LatLon): Double {
        val proj = LocalProjection(a)
        val dx = proj.x(b)
        val dy = proj.y(b)
        return sqrt(dx * dx + dy * dy)
    }

    /** Distance from [p] to a polyline (open). Single point degenerates to point distance. */
    fun distanceToPolylineM(p: LatLon, line: List<LatLon>): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        if (line.size == 1) return distanceM(p, line[0])
        val proj = LocalProjection(p)
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until line.size - 1) {
            val d = segDist(0.0, 0.0, proj.x(line[i]), proj.y(line[i]), proj.x(line[i + 1]), proj.y(line[i + 1]))
            if (d < best) best = d
        }
        return best
    }

    /** Distance from [p] to a polygon: 0 inside, else distance to the boundary. */
    fun distanceToPolygonM(p: LatLon, ring: List<LatLon>): Double {
        if (ring.size < 3) return distanceToPolylineM(p, ring)
        val proj = LocalProjection(p)
        val xs = DoubleArray(ring.size) { proj.x(ring[it]) }
        val ys = DoubleArray(ring.size) { proj.y(ring[it]) }
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            if ((ys[i] > 0) != (ys[j] > 0) && 0.0 < (xs[j] - xs[i]) * (0.0 - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
                inside = !inside
            }
            j = i
        }
        if (inside) return 0.0
        var best = Double.POSITIVE_INFINITY
        for (i in ring.indices) {
            val k = (i + 1) % ring.size
            val d = segDist(0.0, 0.0, xs[i], ys[i], xs[k], ys[k])
            if (d < best) best = d
        }
        return best
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }

    fun parseGeom(geom: String): List<LatLon> =
        geom.split(';').mapNotNull { pair ->
            val c = pair.indexOf(',')
            if (c <= 0) null else {
                val a = pair.substring(0, c).toDoubleOrNull()
                val b = pair.substring(c + 1).toDoubleOrNull()
                if (a == null || b == null) null else LatLon(a, b)
            }
        }
}
