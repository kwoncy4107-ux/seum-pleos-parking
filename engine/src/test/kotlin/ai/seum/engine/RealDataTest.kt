package ai.seum.engine

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the engine against the real bundled database. Skipped unless SEUM_DB points at seum.db
 * (built by data/build_db.py), so the fast unit suite stays independent of the 100 MB file.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealDataTest {
    private lateinit var conn: Connection
    private lateinit var data: SqlMapData
    private lateinit var engine: Engine
    private val noon = LocalDateTime.of(2026, 11, 13, 12, 30)

    @BeforeAll
    fun open() {
        val path = System.getProperty("seum.db")
        assumeTrue(path != null && File(path).exists(), "SEUM_DB not set; skipping real-data checks")
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        data = SqlMapData(JdbcSql(conn))
        engine = Engine(data)
    }

    @AfterAll
    fun close() {
        if (::conn.isInitialized) conn.close()
    }

    private fun firstPoint(layer: String, latRange: ClosedRange<Double>, lonRange: ClosedRange<Double>): LatLon {
        conn.prepareStatement(
            "SELECT lat, lon FROM feature WHERE layer = ? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 1",
        ).use { st ->
            st.setString(1, layer)
            st.setDouble(2, latRange.start); st.setDouble(3, latRange.endInclusive)
            st.setDouble(4, lonRange.start); st.setDouble(5, lonRange.endInclusive)
            st.executeQuery().use { rs ->
                assertTrue(rs.next(), "no $layer in range")
                return LatLon(rs.getDouble(1), rs.getDouble(2))
            }
        }
    }

    private val gangnamLat = 37.49..37.53
    private val gangnamLon = 127.02..127.06

    @Test
    fun `meta reports all layers`() {
        val m = data.meta()
        listOf("n_HYDRANT", "n_CCTV", "n_CROSSWALK", "n_NO_STOP_LINE", "n_BUS_STOP", "n_SCHOOL_ZONE").forEach {
            assertTrue((m[it]?.toInt() ?: 0) > 0, "missing $it")
        }
        assertTrue(m.getValue("enf_records").toInt() > 1_000_000)
    }

    @Test
    fun `standing on a real Gangnam hydrant is danger`() {
        val p = firstPoint("HYDRANT", gangnamLat, gangnamLon)
        val v = engine.judge(p, 3.0, noon)
        assertEquals(Level.DANGER, v.level)
        assertTrue(v.findings.any { it.rule == Rule.HYDRANT && it.certain })
        assertEquals(Coverage.PRECISE, v.coverage)
    }

    @Test
    fun `standing on a real Gangnam yellow solid line is danger`() {
        val p = firstPoint("NO_STOP_LINE", gangnamLat, gangnamLon)
        // centroid of a short segment may sit off the line for long ones; use the first vertex instead
        val geom = conn.prepareStatement("SELECT geom FROM feature WHERE layer='NO_STOP_LINE' AND lat=? AND lon=? LIMIT 1").use { st ->
            st.setDouble(1, p.lat); st.setDouble(2, p.lon)
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
        val onLine = Geo.parseGeom(geom).first()
        val v = engine.judge(onLine, 3.0, noon)
        assertTrue(v.findings.any { it.rule == Rule.NO_STOP_LINE && it.certain }, v.findings.toString())
    }

    @Test
    fun `real Seoul bus stop is found`() {
        val p = firstPoint("BUS_STOP", gangnamLat, gangnamLon)
        val v = engine.judge(p, 3.0, noon)
        assertTrue(v.findings.any { it.rule == Rule.BUS_STOP && it.certain })
    }

    @Test
    fun `Gangnam station area has heavy enforcement history`() {
        // 강남역 사거리 일대
        val v = engine.judge(LatLon(37.4979, 127.0276), 3.0, noon)
        assertTrue((v.risk?.yearly ?: 0) > 0, "expected enforcement records near Gangnam station")
    }

    @Test
    fun `outside Seoul coverage is basic`() {
        // 대전 시청 인근
        val v = engine.judge(LatLon(36.3504, 127.3845), 3.0, noon)
        assertEquals(Coverage.BASIC, v.coverage)
    }

    @Test
    fun `middle of the Han river is clear and silent`() {
        val v = engine.judge(LatLon(37.5175, 126.9970), 3.0, noon)
        assertEquals(Level.CLEAR, v.level)
        assertFalse(v.speech != null)
    }

    @Test
    fun `a nearby parking lot is suggested in Gangnam`() {
        val p = firstPoint("HYDRANT", gangnamLat, gangnamLon)
        assertTrue(engine.judge(p, 3.0, noon).parking != null)
    }

    @Test
    fun `every demo spot produces its expected level`() {
        for (s in DemoSpots.all) {
            val now = LocalDateTime.of(2026, 11, 13, s.hour, s.minute)
            val v = engine.judge(s.pos, 3.0, now)
            println("DEMO ${s.key}: ${v.level} ${v.coverage} | ${v.headline} | ${v.speech} | lot=${v.parking?.lot?.name}@${v.parking?.lot?.pos} ${v.parking?.distanceM?.toInt()}m | risk=${v.risk?.yearly} peak=${v.risk?.peakStartHour} | peak=${v.peak}")
            assertEquals(s.expect, v.level, "${s.key}: ${v.findings}")
        }
    }

    @Test
    fun `inside real apartment complexes Seum stays silent`() {
        val noon = LocalDateTime.of(2026, 11, 13, 12, 0)
        for ((name, p) in listOf("도곡렉슬" to LatLon(37.49298, 127.05094), "헬리오시티" to LatLon(37.49760, 127.10933),
            "잠실 리센츠" to LatLon(37.51351, 127.08780))) {
            val v = engine.judge(p, 5.0, noon)
            assertTrue(v.privateArea != null, "$name: ${v.headline}")
            assertTrue(v.speech == null, name)
        }
    }

    @Test
    fun `street demo spots are not treated as private ground`() {
        for (s in DemoSpots.all) assertTrue(engine.privateAreaAt(s.pos) == null, s.key)
        assertTrue(engine.privateAreaAt(LatLon(37.487924, 127.039825)) == null, "남부순환로365길 39 앞")
    }

    @Test
    fun `judging is fast enough for the P moment`() {
        val pts = (0 until 50).map { LatLon(37.49 + it * 0.0008, 127.02 + it * 0.0007) }
        engine.judge(pts[0], 3.0, noon) // warm up
        val t0 = System.nanoTime()
        pts.forEach { engine.judge(it, 5.0, noon) }
        val avgMs = (System.nanoTime() - t0) / 1e6 / pts.size
        println("avg judge ms = $avgMs")
        assertTrue(avgMs < 100, "avg $avgMs ms")
    }
}

class JdbcSql(private val c: Connection) : Sql {
    override fun query(sql: String, args: List<Any>): List<Array<Any?>> =
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a ->
                when (a) {
                    is Long -> st.setLong(i + 1, a)
                    is Int -> st.setInt(i + 1, a)
                    is Double -> st.setDouble(i + 1, a)
                    else -> st.setString(i + 1, a.toString())
                }
            }
            st.executeQuery().use { rs ->
                val n = rs.metaData.columnCount
                val out = ArrayList<Array<Any?>>()
                while (rs.next()) out += Array(n) { rs.getObject(it + 1) }
                out
            }
        }
}
