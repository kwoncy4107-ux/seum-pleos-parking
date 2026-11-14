package ai.seum.engine

import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decides, at the moment the gear goes to P, whether this spot is a no-parking spot.
 * Pure Kotlin: no Android types, so every rule is unit-tested on the JVM.
 */
class Engine(private val data: MapData, private val cfg: Config = Config()) {

    data class Config(
        /** Minimum GPS slack added to every statutory distance for the "near" band. */
        val minSlackM: Double = 5.0,
        /** Radius for the enforcement-history lookup. */
        val riskRadiusM: Double = 30.0,
        val riskHighPerYear: Int = 150,
        val riskMidPerYear: Int = 30,
        /**
         * Radius for "tickets right here". Records carry their own coordinates, so a cluster within 15 m
         * means this stretch of curb is enforced even where no painted line or crosswalk is on record.
         */
        val closeRadiusM: Double = 15.0,
        val parkingRadiusM: Double = 800.0,
        /** Mention an upcoming enforcement peak only if it starts within this many minutes. */
        val peakHorizonMin: Int = 8 * 60,
        val schoolZoneStartHour: Int = 8,
        val schoolZoneEndHour: Int = 20,
        /** Within this distance of a school-zone facility point, warn aloud during school hours. */
        val schoolGateM: Double = 100.0,
        /**
         * Location older than this at the P moment means the GPS was lost (underground / indoor car park):
         * the last fix is the street at the entrance, so judging it would warn about the wrong place.
         */
        val staleFixMs: Long = 10_000,
        /** Location less precise than this is not trusted either (weak signal indoors). */
        val maxAccuracyM: Double = 25.0,
        /**
         * Inside an apartment complex or parking lot by at least this much (metres from its boundary), the
         * spot is private ground: road no-parking rules do not apply and Seum stays silent. Near the boundary
         * the public street outside may be the real position (GPS error), so it is judged as usual.
         */
        val privateDepthM: Double = 6.0,
    )

    /** The private area the car is deep inside, if any. */
    fun privateAreaAt(pos: LatLon): PrivateArea? =
        data.privateAreasAt(pos).filter { it.ring.size >= 3 }
            .map { it to Geo.distanceToPolygonM(pos, it.ring) }
            .filter { (a, d) -> d == 0.0 && insideDepth(pos, a.ring) >= cfg.privateDepthM }
            .map { it.first }
            .firstOrNull()

    /** Distance from [pos] (inside the ring) to the ring's boundary. */
    private fun insideDepth(pos: LatLon, ring: List<LatLon>): Double = Geo.distanceToPolylineM(pos, ring + ring.first())

    /** Whether a fix is good enough to judge the spot; otherwise stay silent (car park, tunnel, indoors). */
    fun locationReliable(fixAgeMs: Long, accuracyM: Double): Boolean =
        fixAgeMs <= cfg.staleFixMs && accuracyM <= cfg.maxAccuracyM

    fun judge(pos: LatLon, accuracyM: Double, now: LocalDateTime): Verdict {
        val slack = max(cfg.minSlackM, accuracyM)
        val coverage = if (data.hasPreciseCoverage(pos)) Coverage.PRECISE else Coverage.BASIC

        privateAreaAt(pos)?.let { area ->
            // Apartment complex / parking lot: not a road, so no road no-parking rule applies. Say nothing.
            val what = if (area.kind == AreaKind.APT) "단지 안" else "주차장 안"
            val place = if (area.name.isBlank()) (if (area.kind == AreaKind.APT) "아파트 $what" else what) else "${area.name} $what"
            return Verdict(
                level = Level.CLEAR, coverage = coverage, findings = emptyList(), risk = null, peak = null,
                parking = null, fineWon = 0, speech = null,
                headline = "$place · 도로 주정차 규정 대상이 아니에요", privateArea = area,
            )
        }

        val findings = findingsAt(pos, slack, now)
        val risk = riskAt(pos)
        var level = findings.maxOfOrNull { it.level } ?: Level.CLEAR
        if (risk != null && risk.level > level) level = risk.level

        val fine = findings.filter { it.certain && it.level == Level.DANGER }
            .maxOfOrNull { fineFor(it.rule, now) } ?: 0
        val peak = peakFor(findings, risk, now)
        val parking = if (level >= Level.CAUTION) nearestParking(pos) else null

        return Verdict(
            level = level,
            coverage = coverage,
            findings = findings,
            risk = risk,
            peak = peak,
            parking = parking,
            fineWon = fine,
            speech = Speech.compose(level, findings, risk, peak, parking, now, fineFor = { fineFor(it, now) }),
            headline = Speech.headline(level, findings, risk, coverage),
        )
    }

    fun fineFor(rule: Rule, now: LocalDateTime): Int =
        if (rule == Rule.SCHOOL_ZONE && !schoolHours(now)) 40_000 else rule.fineWon

    private fun schoolHours(now: LocalDateTime) = now.hour in cfg.schoolZoneStartHour until cfg.schoolZoneEndHour

    internal fun findingsAt(pos: LatLon, slack: Double, now: LocalDateTime): List<Finding> {
        // School zones need a 300 m look-around; everything else is within ~50 m. Query them separately so
        // the P moment does not read every hydrant and lane line within 300 m.
        val wide = setOf(Layer.SCHOOL_ZONE)
        val nearLimit = Rule.entries.filter { it.layer !in wide }.maxOf { it.limitM }
        val nearLayers = Layer.entries.toSet() - wide
        val feats = data.featuresNear(pos, nearLimit + slack, nearLayers) +
            data.featuresNear(pos, Rule.SCHOOL_ZONE.limitM + slack, wide)
        // best (closest) finding per rule
        val best = HashMap<Rule, Finding>()
        for (f in feats) {
            val rule = Rule.forLayer(f.layer)
            val d = when (val s = f.shape) {
                is Shape.Point -> Geo.distanceM(pos, s.p)
                is Shape.Line -> Geo.distanceToPolylineM(pos, s.pts)
                is Shape.Polygon -> Geo.distanceToPolygonM(pos, s.ring)
            }
            val certain = d <= rule.limitM && rule != Rule.INTERSECTION && rule != Rule.JUNCTION
            if (d > rule.limitM + slack) continue
            val lvl = if (rule == Rule.SCHOOL_ZONE && d <= cfg.schoolGateM && schoolHours(now)) Level.CAUTION
            else levelFor(rule, certain, now)
            val cur = best[rule]
            if (cur == null || d < cur.distanceM) best[rule] = Finding(rule, d, certain, lvl, f)
        }
        return best.values.sortedWith(compareByDescending<Finding> { it.level }.thenBy { it.distanceM })
    }

    private fun levelFor(rule: Rule, certain: Boolean, now: LocalDateTime): Level = when (rule) {
        // Zones are stored as the facility point; the law allows up to 300 m from the main gate but most
        // designated roads are the ones right outside it. Speak only close to the gate during school hours;
        // farther out it is screen-only information, so the guardian stays quiet in ordinary city streets.
        Rule.SCHOOL_ZONE -> Level.INFO
        Rule.NO_PARK_LINE -> Level.CAUTION
        Rule.INTERSECTION, Rule.JUNCTION -> Level.CAUTION
        Rule.CCTV -> if (certain) Level.DANGER else Level.CAUTION
        else -> if (certain) Level.DANGER else Level.CAUTION
    }

    internal fun riskAt(pos: LatLon): EnforcementRisk? {
        val cells = data.enforcementCellsNear(pos, cfg.riskRadiusM)
        if (cells.isEmpty()) return null
        val hours = IntArray(24)
        for (c in cells) for (h in 0 until 24) hours[h] += c.byHour[h]
        val total = hours.sum()
        if (total == 0) return null
        // densest 3-hour window (wrapping midnight)
        var bestStart = 0
        var bestSum = -1
        for (s in 0 until 24) {
            val sum = hours[s] + hours[(s + 1) % 24] + hours[(s + 2) % 24]
            // ties: prefer the window that starts on an hour with records (so it is not padded on the left)
            if (sum > bestSum || (sum == bestSum && hours[s] > hours[bestStart])) { bestSum = sum; bestStart = s }
        }
        val close = data.enforcementCountNear(pos, cfg.closeRadiusM)
        val lvl = when {
            total >= cfg.riskHighPerYear || close >= EnforcementRisk.CLOSE_HIGH -> Level.CAUTION
            total >= cfg.riskMidPerYear -> Level.INFO
            else -> Level.CLEAR
        }
        return EnforcementRisk(total, hours, bestStart, bestStart + 3, lvl, close)
    }

    /**
     * Time advice for the P moment. Skipped where the message is simply "move now" (a certain no-stop
     * spot) or where enforcement does not depend on time (fixed cameras run all day).
     */
    internal fun peakFor(findings: List<Finding>, risk: EnforcementRisk?, now: LocalDateTime): PeakTiming? {
        if (risk == null || risk.level < Level.INFO) return null
        if (findings.any { it.level == Level.DANGER && it.certain }) return null
        val inPeak = ((now.hour - risk.peakStartHour + 24) % 24) < (risk.peakEndHourExclusive - risk.peakStartHour)
        val until = if (inPeak) 0 else minutesUntilHour(now, risk.peakStartHour)
        if (!inPeak && until > cfg.peakHorizonMin) return null
        return PeakTiming(risk.peakStartHour, risk.peakEndHourExclusive % 24, inPeak, until)
    }

    private fun minutesUntilHour(now: LocalDateTime, hour: Int): Int {
        val nowMin = now.hour * 60 + now.minute
        val target = hour * 60
        return ((target - nowMin) + 24 * 60) % (24 * 60)
    }

    internal fun nearestParking(pos: LatLon): ParkingSuggestion? {
        val lots = data.parkingNear(pos, cfg.parkingRadiusM)
        val best = lots.map { it to Geo.distanceM(pos, it.pos) }
            .filter { it.second <= cfg.parkingRadiusM }
            // 1st: public lots, 2nd: other lots open to the public (named ones before unnamed), nearest first.
            .sortedWith(compareBy<Pair<ParkingLot, Double>> { !it.first.publicLot }.thenBy { it.first.name.isBlank() }.thenBy { it.second })
            .firstOrNull() ?: return null
        return ParkingSuggestion(best.first, best.second, feeText(best.first))
    }

    companion object {
        fun feeText(lot: ParkingLot): String = when {
            lot.free -> "무료"
            lot.baseMinutes != null && lot.baseFeeWon != null -> "${lot.baseMinutes}분 ${won(lot.baseFeeWon)}"
            else -> "요금 정보 없음"
        }

        fun won(v: Int): String = when {
            v >= 10_000 && v % 10_000 == 0 -> "${v / 10_000}만 원"
            v >= 10_000 -> "${(v / 1000.0).roundToInt() / 10.0}만 원"
            else -> "${v}원"
        }
    }
}
