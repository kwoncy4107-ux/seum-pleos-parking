package ai.seum.engine

/** Minimal query port so the same SQL runs over android.database.sqlite on the car and JDBC in tests. */
fun interface Sql {
    /** Runs a SELECT and returns each row as an array of column values (Long, Double, String or null). */
    fun query(sql: String, args: List<Any>): List<Array<Any?>>
}

/** MapData over the bundled seum.db (schema in data/build_db.py). */
class SqlMapData(private val sql: Sql) : MapData {

    override fun featuresNear(p: LatLon, radiusM: Double, layers: Set<Layer>?): List<Feature> {
        val cells = Geo.cellsAround(p, radiusM)
        val layerFilter = layers?.let { ls -> " AND f.layer IN (${ls.joinToString(",") { "'${it.name}'" }})" } ?: ""
        val q = "SELECT DISTINCT f.id, f.layer, f.lat, f.lon, f.geom, f.info FROM feature_cell c " +
            "JOIN feature f ON f.id = c.fid WHERE c.cell IN (${cells.joinToString(",") { "?" }})$layerFilter"
        return sql.query(q, cells).mapNotNull { row ->
            val layer = Layer.of(row[1] as String) ?: return@mapNotNull null
            val geom = row[4] as String?
            val info = MiniJson.parse(row[5] as String?)
            val shape: Shape = when {
                geom == null -> Shape.Point(LatLon(num(row[2]), num(row[3])))
                layer == Layer.CROSSWALK -> Shape.Polygon(Geo.parseGeom(geom))
                else -> Shape.Line(Geo.parseGeom(geom))
            }
            Feature(
                id = (row[0] as Number).toLong(),
                layer = layer,
                shape = shape,
                note = info["detail"] ?: info["n"] ?: "",
                atIntersection = info["x"] == "1",
            )
        }
    }

    override fun enforcementPointsNear(p: LatLon, radiusM: Double): List<EnforcementPoint> {
        // enf_pt: 0.00003 deg grid, cell = lat_i * 1e8 + lon_i; one primary-key range per grid row
        val g = 0.00003
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * kotlin.math.cos(Math.toRadians(p.lat)))
        val a0 = kotlin.math.floor((p.lat - dLat) / g).toLong(); val a1 = kotlin.math.floor((p.lat + dLat) / g).toLong()
        val o0 = kotlin.math.floor((p.lon - dLon) / g).toLong(); val o1 = kotlin.math.floor((p.lon + dLon) / g).toLong()
        val where = (a0..a1).joinToString(" OR ") { a -> "cell BETWEEN ${a * 100_000_000L + o0} AND ${a * 100_000_000L + o1}" }
        return runCatching {
            sql.query("SELECT cell, total FROM enf_pt WHERE $where", emptyList()).map { row ->
                val c = (row[0] as Number).toLong()
                EnforcementPoint(LatLon((c / 100_000_000L + 0.5) * g, (c % 100_000_000L + 0.5) * g), (row[1] as Number).toInt())
            }
        }.getOrDefault(emptyList())
    }

    override fun enforcementCellsNear(p: LatLon, radiusM: Double): List<EnforcementCell> {
        val cells = Geo.cellsAround(p, radiusM)
        val q = "SELECT cell, total, hours FROM enf_cell WHERE cell IN (${cells.joinToString(",") { "?" }})"
        return sql.query(q, cells).map { row ->
            val hours = (row[2] as String).split(',').map { it.trim().toInt() }.toIntArray()
            EnforcementCell((row[0] as Number).toLong(), (row[1] as Number).toInt(), hours)
        }
    }

    override fun parkingNear(p: LatLon, radiusM: Double): List<ParkingLot> {
        // ~18k rows: a bounding-box scan is cheaper than listing ~1,000 grid cells for an 800 m radius.
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * kotlin.math.cos(Math.toRadians(p.lat)))
        val q = "SELECT id, lat, lon, name, kind, info FROM parking WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?"
        return sql.query(q, listOf(p.lat - dLat, p.lat + dLat, p.lon - dLon, p.lon + dLon)).map { row ->
            val info = MiniJson.parse(row[5] as String?)
            val charge = info["PARKINGCHRGE_INFO"] ?: ""
            ParkingLot(
                id = (row[0] as Number).toLong(),
                name = (row[3] as String?).orEmpty(),
                pos = LatLon(num(row[1]), num(row[2])),
                publicLot = (row[4] as String?) == "공영",
                baseMinutes = info["BASIC_TIME"]?.toIntOrNullLoose(),
                baseFeeWon = info["BASIC_CHARGE"]?.toIntOrNullLoose(),
                addMinutes = info["ADD_UNIT_TIME"]?.toIntOrNullLoose(),
                addFeeWon = info["ADD_UNIT_CHARGE"]?.toIntOrNullLoose(),
                free = charge.contains("무료"),
                address = info["RDNMADR"]?.takeIf { it.isNotBlank() } ?: info["LNMADR"].orEmpty(),
            )
        }
    }

    override fun hasPreciseCoverage(p: LatLon): Boolean {
        if (p.lat !in SEOUL_LAT || p.lon !in SEOUL_LON) return false
        // Seoul enforcement records cover every district, so any record within 500 m means the Seoul
        // layers (lane lines, crosswalk polygons) are present here. Primary-key lookups only.
        val cells = Geo.cellsAround(p, 500.0)
        val q = "SELECT 1 FROM enf_cell WHERE cell IN (${cells.joinToString(",") { "?" }}) LIMIT 1"
        return sql.query(q, cells).isNotEmpty()
    }

    override fun privateAreasAt(p: LatLon): List<PrivateArea> {
        val q = "SELECT a.kind, a.name, a.geom FROM area_cell c JOIN area a ON a.id = c.aid WHERE c.cell = ?"
        return runCatching {
            sql.query(q, listOf(Geo.cellOf(p.lat, p.lon))).mapNotNull { row ->
                val kind = runCatching { AreaKind.valueOf(row[0] as String) }.getOrNull() ?: return@mapNotNull null
                PrivateArea(kind, (row[1] as String?).orEmpty(), Geo.parseGeom(row[2] as String))
            }
        }.getOrDefault(emptyList()) // bundles without the area table
    }

    fun meta(): Map<String, String> =
        sql.query("SELECT key, value FROM meta", emptyList()).associate { (it[0] as String) to (it[1] as String) }

    private fun num(v: Any?): Double = (v as Number).toDouble()

    private fun String.toIntOrNullLoose(): Int? = trim().toDoubleOrNull()?.toInt()

    companion object {
        private val SEOUL_LAT = 37.41..37.72
        private val SEOUL_LON = 126.73..127.27
    }
}

/** Parses the flat {"k": "v" | number | bool} objects written by build_db.py. No nesting, no arrays. */
object MiniJson {
    fun parse(s: String?): Map<String, String> {
        if (s.isNullOrBlank()) return emptyMap()
        val out = HashMap<String, String>()
        var i = s.indexOf('{') + 1
        while (i in 1 until s.length) {
            i = skipWs(s, i)
            if (i >= s.length || s[i] == '}') break
            if (s[i] == ',') { i++; continue }
            val (key, afterKey) = readString(s, i) ?: break
            i = skipWs(s, afterKey)
            if (i >= s.length || s[i] != ':') break
            i = skipWs(s, i + 1)
            if (i >= s.length) break
            if (s[i] == '"') {
                val (v, after) = readString(s, i) ?: break
                out[key] = v
                i = after
            } else {
                var j = i
                while (j < s.length && s[j] != ',' && s[j] != '}') j++
                val raw = s.substring(i, j).trim()
                out[key] = when (raw) {
                    "true" -> "1"
                    "false", "null" -> ""
                    else -> raw
                }
                i = j
            }
        }
        return out
    }

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun readString(s: String, from: Int): Pair<String, Int>? {
        if (from >= s.length || s[from] != '"') return null
        val sb = StringBuilder()
        var i = from + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    val n = s[i + 1]
                    when (n) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'u' -> if (i + 5 < s.length) {
                            sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4
                        }
                        else -> sb.append(n)
                    }
                    i += 2
                }
                c == '"' -> return sb.toString() to i + 1
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }
}
