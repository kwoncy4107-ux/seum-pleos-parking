package ai.seum.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDateTime

/**
 * Helper, not a check (runs only with SEUM_HOTSPOTS=<csv>): judges Seoul's most-enforced curb spots
 * (from data/hotspots) and prints the ones whose no-parking reason is not just a straight painted line.
 */
class HotspotScanTest {
    @Test
    fun scan() {
        val path = System.getProperty("seum.db")
        val csv = System.getenv("SEUM_HOTSPOTS")
        assumeTrue(path != null && File(path).exists() && csv != null && File(csv).exists())
        val shapes = setOf(Rule.CROSSWALK, Rule.CROSSWALK_PT, Rule.HYDRANT, Rule.BUS_STOP, Rule.INTERSECTION, Rule.SCHOOL_ZONE)
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            val engine = Engine(SqlMapData(JdbcSql(c)))
            val out = StringBuilder()
            File(csv).readLines(Charsets.UTF_8).drop(1).forEach { line ->
                val f = line.split(',')
                if (f.size < 5) return@forEach
                val n = f[0].toInt(); val p = LatLon(f[1].toDouble(), f[2].toDouble())
                val v = engine.judge(p, 3.0, LocalDateTime.of(2026, 11, 13, 13, 0))
                val certain = v.findings.filter { it.certain || it.level >= Level.CAUTION }
                val kinds = certain.map { it.rule }.toSet()
                val shaped = kinds.intersect(shapes)
                out.append("HOT|$n|${p.lat},${p.lon}|${f[3]}|${v.level}|${kinds.joinToString("+")}|shaped=${shaped.size}|${v.privateArea?.name ?: ""}\n")
            }
            File(System.getProperty("java.io.tmpdir"), "seum_hotspots_judged.txt").writeText(out.toString())
            println("written ${out.lines().size}")
        }
    }
}
