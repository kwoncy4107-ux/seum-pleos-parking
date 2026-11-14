package ai.seum.engine

import ai.seum.engine.FakeMap.Companion.GANGNAM
import ai.seum.engine.FakeMap.Companion.off
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    @ParameterizedTest
    @ValueSource(doubles = [1.0, 5.0, 10.0, 50.0, 300.0])
    fun `offset north is measured back to the same distance`(m: Double) {
        assertEquals(m, Geo.distanceM(GANGNAM, off(GANGNAM, m, 0.0)), 0.05)
    }

    @ParameterizedTest
    @ValueSource(doubles = [1.0, 5.0, 10.0, 50.0, 300.0])
    fun `offset east is measured back to the same distance`(m: Double) {
        assertEquals(m, Geo.distanceM(GANGNAM, off(GANGNAM, 0.0, m)), 0.05)
    }

    @Test
    fun `diagonal distance is pythagorean`() {
        assertEquals(5.0, Geo.distanceM(GANGNAM, off(GANGNAM, 3.0, 4.0)), 0.02)
    }

    @Test
    fun `distance to polyline uses the perpendicular foot`() {
        val line = listOf(off(GANGNAM, 3.0, -20.0), off(GANGNAM, 3.0, 20.0))
        assertEquals(3.0, Geo.distanceToPolylineM(GANGNAM, line), 0.02)
    }

    @Test
    fun `distance to polyline beyond its end uses the endpoint`() {
        val line = listOf(off(GANGNAM, 0.0, 10.0), off(GANGNAM, 0.0, 30.0))
        assertEquals(10.0, Geo.distanceToPolylineM(GANGNAM, line), 0.02)
    }

    @Test
    fun `multi segment polyline picks the nearest segment`() {
        val line = listOf(off(GANGNAM, 50.0, -50.0), off(GANGNAM, 50.0, 0.0), off(GANGNAM, 2.0, 0.0), off(GANGNAM, 2.0, 40.0))
        assertEquals(2.0, Geo.distanceToPolylineM(GANGNAM, line), 0.02)
    }

    @Test
    fun `single point polyline degenerates to point distance`() {
        assertEquals(7.0, Geo.distanceToPolylineM(GANGNAM, listOf(off(GANGNAM, 7.0, 0.0))), 0.02)
    }

    @Test
    fun `empty polyline is infinitely far`() {
        assertEquals(Double.POSITIVE_INFINITY, Geo.distanceToPolylineM(GANGNAM, emptyList()))
    }

    @Test
    fun `point inside polygon has zero distance`() {
        val sq = listOf(off(GANGNAM, -2.0, -2.0), off(GANGNAM, -2.0, 2.0), off(GANGNAM, 2.0, 2.0), off(GANGNAM, 2.0, -2.0))
        assertEquals(0.0, Geo.distanceToPolygonM(GANGNAM, sq))
    }

    @Test
    fun `point outside polygon measures to the nearest edge`() {
        val sq = listOf(off(GANGNAM, 6.0, -2.0), off(GANGNAM, 6.0, 2.0), off(GANGNAM, 10.0, 2.0), off(GANGNAM, 10.0, -2.0))
        assertEquals(6.0, Geo.distanceToPolygonM(GANGNAM, sq), 0.02)
    }

    @Test
    fun `closed ring with repeated first point still works`() {
        val a = off(GANGNAM, 6.0, -2.0)
        val sq = listOf(a, off(GANGNAM, 6.0, 2.0), off(GANGNAM, 10.0, 2.0), off(GANGNAM, 10.0, -2.0), a)
        assertEquals(6.0, Geo.distanceToPolygonM(GANGNAM, sq), 0.02)
    }

    @Test
    fun `cell id matches the python pipeline formula`() {
        // build_db.py: floor(lat/0.0005)*1_000_000 + floor(lon/0.0005)
        assertEquals(75048L * 1_000_000L + 254070L, Geo.cellOf(37.5244, 127.0352))
    }

    @Test
    fun `cells around a point include its own cell`() {
        assertTrue(Geo.cellOf(GANGNAM.lat, GANGNAM.lon) in Geo.cellsAround(GANGNAM, 10.0))
    }

    @Test
    fun `cells around grow with radius`() {
        assertTrue(Geo.cellsAround(GANGNAM, 300.0).size > Geo.cellsAround(GANGNAM, 30.0).size)
    }

    @Test
    fun `cells around cover a point at the radius edge`() {
        val p = off(GANGNAM, 0.0, 49.0)
        assertTrue(Geo.cellOf(p.lat, p.lon) in Geo.cellsAround(GANGNAM, 50.0))
    }

    @Test
    fun `parseGeom reads the pipeline format`() {
        val pts = Geo.parseGeom("37.500000,127.000000;37.500100,127.000200")
        assertEquals(listOf(LatLon(37.5, 127.0), LatLon(37.5001, 127.0002)), pts)
    }

    @Test
    fun `parseGeom skips malformed pairs`() {
        assertEquals(1, Geo.parseGeom("37.5,127.0;garbage;;1,").size)
    }
}
