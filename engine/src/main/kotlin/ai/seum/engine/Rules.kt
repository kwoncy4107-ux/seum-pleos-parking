package ai.seum.engine

/**
 * Legal basis and fines for a passenger car (도로교통법 제32조·제33조, 시행령 제88조 별표6).
 * Distances are the statutory ones; [nearSlackM] widens the band to absorb GPS error, and a hit
 * inside that band is reported as "near" (CAUTION) rather than as a certain violation.
 */
enum class Rule(
    val layer: Layer,
    val limitM: Double,
    val fineWon: Int,
    val title: String,
    val basis: String,
    /** Anyone can report it through 안전신문고 without a warning first (주민신고제 대상). */
    val reportable: Boolean,
) {
    HYDRANT(Layer.HYDRANT, 5.0, 80_000, "소화전 5m 이내", "도로교통법 제32조 제6호", true),
    CROSSWALK(Layer.CROSSWALK, 10.0, 40_000, "횡단보도 10m 이내", "도로교통법 제32조 제1·2호", true),
    /** Point data marks the crosswalk centre; the stripes extend a few meters each way, so allow 4 m more. */
    CROSSWALK_PT(Layer.CROSSWALK_PT, 14.0, 40_000, "횡단보도 10m 이내", "도로교통법 제32조 제1·2호", true),
    /** Node at the intersection centre; the 5 m is measured from the corner, so this is only ever "near". */
    INTERSECTION(Layer.INTERSECTION, 15.0, 40_000, "교차로 모퉁이 5m 이내", "도로교통법 제32조 제2호", true),
    /**
     * Side-street junction from OpenStreetMap: small streets are narrow, so the corner is close to the node
     * (9 m instead of 15 m). Never certain, like INTERSECTION.
     */
    JUNCTION(Layer.JUNCTION, 9.0, 40_000, "교차로 모퉁이 5m 이내", "도로교통법 제32조 제2호", true),
    BUS_STOP(Layer.BUS_STOP, 10.0, 40_000, "버스정류장 10m 이내", "도로교통법 제32조 제4호", true),
    SCHOOL_ZONE(Layer.SCHOOL_ZONE, 300.0, 120_000, "어린이보호구역", "도로교통법 제12조, 시행령 별표6", true),
    NO_STOP_LINE(Layer.NO_STOP_LINE, 4.0, 40_000, "황색 실선 구간", "도로교통법 제32조, 노면표시 516", false),
    NO_PARK_LINE(Layer.NO_PARK_LINE, 4.0, 40_000, "황색 점선: 5분 이내 정차만 허용", "도로교통법 제33조, 노면표시 516의2", false),
    NO_STOP_BOX(Layer.NO_STOP_BOX, 3.0, 40_000, "정차금지지대", "도로교통법 제32조", false),
    CCTV(Layer.CCTV, 50.0, 40_000, "고정 단속 CCTV 구간", "도로교통법 제160조 제3항", false);

    companion object {
        fun forLayer(l: Layer) = entries.first { it.layer == l }
    }
}

enum class Level { CLEAR, INFO, CAUTION, DANGER }

enum class Coverage { PRECISE, BASIC }

data class Finding(
    val rule: Rule,
    val distanceM: Double,
    /** Within the statutory distance (true) or only within the GPS-error band (false). */
    val certain: Boolean,
    val level: Level,
    val feature: Feature?,
)

data class EnforcementRisk(
    /** Records in the surrounding cells over the dataset span (one year). */
    val yearly: Int,
    val byHour: IntArray,
    val peakStartHour: Int,
    val peakEndHourExclusive: Int,
    val level: Level,
    /** Records right around the spot (within Engine.Config.closeRadiusM), over the same year. */
    val close: Int = 0,
) {
    /** Tickets cluster right here, not just somewhere on the block. */
    val clustered: Boolean get() = close >= CLOSE_HIGH

    companion object {
        /** Records per year within the close radius that make a spot "단속이 잦은 자리" on their own. */
        const val CLOSE_HIGH = 30
    }

    val peakShare: Double
        get() {
            val tot = byHour.sum()
            if (tot == 0) return 0.0
            var s = 0
            var h = peakStartHour
            while (h != peakEndHourExclusive % 24) {
                s += byHour[h]; h = (h + 1) % 24
            }
            return s.toDouble() / tot
        }

    override fun equals(other: Any?) = other is EnforcementRisk && yearly == other.yearly &&
        byHour.contentEquals(other.byHour) && peakStartHour == other.peakStartHour
    override fun hashCode() = yearly * 31 + byHour.contentHashCode()
}

/** When enforcement at this spot clusters, relative to now. Spoken at the P moment instead of a later alert. */
data class PeakTiming(
    val startHour: Int,
    val endHourExclusive: Int,
    /** Now is inside the busiest window. */
    val inPeak: Boolean,
    /** Minutes until the window starts (0 when inside it). */
    val minutesUntil: Int,
)

data class ParkingSuggestion(val lot: ParkingLot, val distanceM: Double, val feeText: String)

data class Verdict(
    val level: Level,
    val coverage: Coverage,
    val findings: List<Finding>,
    val risk: EnforcementRisk?,
    val peak: PeakTiming?,
    val parking: ParkingSuggestion?,
    /** Fine the driver would most likely face here (max over certain findings). */
    val fineWon: Int,
    /** Korean sentence for TTS; null means stay silent. */
    val speech: String?,
    /** One-line headline for the screen. */
    val headline: String,
    /** Set when the car is inside an apartment complex / parking lot: not judged, never spoken. */
    val privateArea: PrivateArea? = null,
)
