package ai.seum.engine

/** Data layers, matching the `layer` column written by data/build_db.py. */
enum class Layer {
    HYDRANT,        // 소화전 (national)
    CROSSWALK,      // 횡단보도 polygon (Seoul T-GIS)
    CROSSWALK_PT,   // 횡단보도 point (전국횡단보도표준데이터, outside Seoul)
    INTERSECTION,   // 교차로 point (Seoul T-GIS)
    JUNCTION,       // 골목 교차로 point, where OpenStreetMap streets meet (fills the gaps in INTERSECTION)
    BUS_STOP,       // 버스정류장 (국토부 TAGO, national)
    SCHOOL_ZONE,    // 어린이보호구역 facility point (national)
    CCTV,           // 고정식 불법주정차 단속 카메라 (national)
    NO_STOP_LINE,   // 주정차금지선, 황색 실선 (Seoul T-GIS)
    NO_PARK_LINE,   // 주차금지선, 황색 점선 (Seoul T-GIS)
    NO_STOP_BOX;    // 정차금지지대 (Seoul T-GIS)

    companion object {
        fun of(s: String): Layer? = entries.firstOrNull { it.name == s }
    }
}

sealed interface Shape {
    data class Point(val p: LatLon) : Shape
    data class Line(val pts: List<LatLon>) : Shape
    data class Polygon(val ring: List<LatLon>) : Shape
}

data class Feature(
    val id: Long,
    val layer: Layer,
    val shape: Shape,
    /** Short free text from the source (e.g. hydrant location note, CCTV place). */
    val note: String = "",
    /** Crosswalk linked to an intersection code in T-GIS. */
    val atIntersection: Boolean = false,
)

/** Tickets written at one ~3 m spot over the dataset year. */
data class EnforcementPoint(val pos: LatLon, val total: Int)

/** One year of enforcement records aggregated into a grid cell. */
data class EnforcementCell(val cell: Long, val total: Int, val byHour: IntArray) {
    init {
        require(byHour.size == 24)
    }

    override fun equals(other: Any?) =
        other is EnforcementCell && cell == other.cell && total == other.total && byHour.contentEquals(other.byHour)

    override fun hashCode() = cell.hashCode() * 31 + byHour.contentHashCode()
}

data class ParkingLot(
    val id: Long,
    val name: String,
    val pos: LatLon,
    val publicLot: Boolean,
    val baseMinutes: Int?,
    val baseFeeWon: Int?,
    val addMinutes: Int?,
    val addFeeWon: Int?,
    val free: Boolean,
    val address: String,
)

/** Private ground where road no-parking rules do not apply: apartment complexes, parking lots. */
enum class AreaKind { APT, PARKING }

data class PrivateArea(val kind: AreaKind, val name: String, val ring: List<LatLon>)

/** Read side of the bundled database. Android implements it over SQLite; tests use an in-memory fake. */
interface MapData {
    /** Features whose grid cells fall within [radiusM]; [layers] null = every layer. */
    fun featuresNear(p: LatLon, radiusM: Double, layers: Set<Layer>? = null): List<Feature>
    fun enforcementCellsNear(p: LatLon, radiusM: Double): List<EnforcementCell>
    /** Where tickets were written (one year), on a ~3 m grid, within about [radiusM] of [p]. */
    fun enforcementPointsNear(p: LatLon, radiusM: Double): List<EnforcementPoint> = emptyList()
    /** Enforcement records within [radiusM] of [p] (one year). */
    fun enforcementCountNear(p: LatLon, radiusM: Double): Int =
        enforcementPointsNear(p, radiusM).filter { Geo.distanceM(p, it.pos) <= radiusM }.sumOf { it.total }
    fun parkingNear(p: LatLon, radiusM: Double): List<ParkingLot>
    /** Seoul precise layers (lane lines, crosswalks, enforcement history) cover this point. */
    fun hasPreciseCoverage(p: LatLon): Boolean
    /** Private areas whose bounding box covers [p] (apartment complexes, parking lots). */
    fun privateAreasAt(p: LatLon): List<PrivateArea> = emptyList()
}
