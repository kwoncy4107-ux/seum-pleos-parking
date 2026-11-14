package ai.seum.engine

/** In-memory MapData with the same semantics as the SQLite implementation. */
class FakeMap(
    val features: MutableList<Feature> = mutableListOf(),
    val enforcement: MutableList<EnforcementCell> = mutableListOf(),
    val parking: MutableList<ParkingLot> = mutableListOf(),
    var precise: Boolean = true,
) : MapData {
    private var nextId = 1L

    override fun featuresNear(p: LatLon, radiusM: Double, layers: Set<Layer>?): List<Feature> {
        val cells = Geo.cellsAround(p, radiusM).toSet()
        return features.filter { f -> layers == null || f.layer in layers }.filter { f ->
            when (val s = f.shape) {
                is Shape.Point -> Geo.cellOf(s.p.lat, s.p.lon) in cells
                is Shape.Line -> s.pts.any { Geo.cellOf(it.lat, it.lon) in cells }
                is Shape.Polygon -> s.ring.any { Geo.cellOf(it.lat, it.lon) in cells }
            }
        }
    }

    override fun enforcementCellsNear(p: LatLon, radiusM: Double): List<EnforcementCell> {
        val cells = Geo.cellsAround(p, radiusM).toSet()
        return enforcement.filter { it.cell in cells }
    }

    /** Records "right here" for the close-cluster check (the fine grid in the bundle). */
    var closeRecords: Int = 0
    override fun enforcementCountNear(p: LatLon, radiusM: Double): Int = closeRecords

    override fun parkingNear(p: LatLon, radiusM: Double): List<ParkingLot> =
        parking.filter { Geo.distanceM(p, it.pos) <= radiusM }

    override fun hasPreciseCoverage(p: LatLon) = precise

    val areas = mutableListOf<PrivateArea>()
    override fun privateAreasAt(p: LatLon): List<PrivateArea> = areas

    /** Square private area of [halfM] metres around [c]. */
    fun area(kind: AreaKind, name: String, c: LatLon, halfM: Double) = PrivateArea(
        kind, name,
        listOf(off(c, -halfM, -halfM), off(c, -halfM, halfM), off(c, halfM, halfM), off(c, halfM, -halfM), off(c, -halfM, -halfM)),
    ).also { areas += it }

    fun point(layer: Layer, p: LatLon, note: String = "") =
        Feature(nextId++, layer, Shape.Point(p), note).also { features += it }

    fun line(layer: Layer, vararg pts: LatLon) =
        Feature(nextId++, layer, Shape.Line(pts.toList())).also { features += it }

    fun polygon(layer: Layer, vararg pts: LatLon) =
        Feature(nextId++, layer, Shape.Polygon(pts.toList())).also { features += it }

    fun enforce(p: LatLon, byHour: Map<Int, Int>) {
        val arr = IntArray(24).also { a -> byHour.forEach { (h, n) -> a[h] = n } }
        enforcement += EnforcementCell(Geo.cellOf(p.lat, p.lon), arr.sum(), arr)
    }

    fun lot(name: String, p: LatLon, publicLot: Boolean = true, baseMin: Int? = 5, baseFee: Int? = 250, free: Boolean = false) =
        ParkingLot(nextId++, name, p, publicLot, baseMin, baseFee, 5, 250, free, "").also { parking += it }

    companion object {
        /** Point offset from [o] by meters north/east. */
        fun off(o: LatLon, northM: Double, eastM: Double) = LatLon(
            o.lat + northM / 111_320.0,
            o.lon + eastM / (111_320.0 * kotlin.math.cos(o.lat * kotlin.math.PI / 180.0)),
        )

        /** 강남구 신사동 도산공원 인근 기준점. */
        val GANGNAM = LatLon(37.5244, 127.0352)
    }
}
