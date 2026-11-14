package ai.seum.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDateTime

/**
 * Helper, not a check (runs only with SEUM_SCAN=1): lists curb spots for the demo. Candidates sit on
 * yellow dashed lines (5-minute stopping zones, i.e. real curbs), so the car is never drawn inside a block.
 */
class DemoScanTest {
    @Test
    fun scan() {
        val path = System.getProperty("seum.db")
        assumeTrue(path != null && File(path).exists() && System.getenv("SEUM_SCAN") != null)
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            val engine = Engine(SqlMapData(JdbcSql(c)))
            val at = LocalDateTime.of(2026, 11, 13, 11, 0)
            val rows = c.createStatement().executeQuery(
                "SELECT geom FROM feature WHERE layer='NO_PARK_LINE' AND lat BETWEEN 37.495 AND 37.528 AND lon BETWEEN 127.020 AND 127.065",
            )
            var found = 0
            while (rows.next() && found < 15) {
                val pts = Geo.parseGeom(rows.getString(1))
                val p = pts[pts.size / 2]
                val v = engine.judge(p, 3.0, at)
                val onlyDashed = v.findings.all { it.rule == Rule.NO_PARK_LINE || it.level <= Level.INFO }
                if (onlyDashed && v.peak != null && !v.peak!!.inPeak && (v.risk?.yearly ?: 0) >= 150 && v.parking != null) {
                    println("SCAN ${p.lat},${p.lon} risk=${v.risk?.yearly} peak=${v.peak} lot=${v.parking?.lot?.name} ${v.parking?.distanceM?.toInt()}m | ${v.speech}")
                    found++
                }
            }
        }
    }
}
