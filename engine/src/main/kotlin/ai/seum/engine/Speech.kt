package ai.seum.engine

import java.time.LocalDateTime
import kotlin.math.roundToInt

/** Korean sentences for TTS and the screen. Short, one fact per sentence, numbers read naturally. */
object Speech {

    fun compose(
        level: Level,
        findings: List<Finding>,
        risk: EnforcementRisk?,
        peak: PeakTiming?,
        parking: ParkingSuggestion?,
        now: LocalDateTime,
        fineFor: (Rule) -> Int,
    ): String? {
        // The silent guardian: nothing to say when the spot is fine and no enforcement time is coming up.
        if (level < Level.CAUTION && peak == null) return null
        val parts = ArrayList<String>()
        val top = findings.firstOrNull { it.level >= Level.CAUTION }
        if (top != null) {
            parts += sentenceFor(top, fineFor(top.rule))
            if (peak != null && top.rule != Rule.CCTV) parts += peakSentence(peak)
        } else if (risk != null && risk.clustered) {
            parts += "바로 이 자리 15미터 안에서 작년에 단속이 ${risk.close}건 있었어요." +
                (peak?.let { " " + peakSentence(it) } ?: "")
        } else if (risk != null && risk.level >= Level.CAUTION) {
            parts += if (peak != null && !peak.inPeak) {
                "이 자리는 작년 단속이 ${risk.yearly}건 있었고, ${span(peak.startHour, peak.endHourExclusive)}에 몰려요."
            } else {
                "이 자리는 작년 한 해 단속이 ${risk.yearly}건 있었어요." + (peak?.let { " " + peakSentence(it) } ?: "")
            }
        } else if (peak != null) {
            parts += peakSentence(peak)
        }
        if (parking != null && level >= Level.CAUTION) {
            val name = lotName(parking.lot)
            parts += "${distanceWords(parking.distanceM)} 앞에 ${name}${josa(name, "이", "가")} 있어요. 안내할까요?"
        }
        return parts.joinToString(" ").ifBlank { null }
    }

    fun peakSentence(p: PeakTiming): String =
        if (p.inPeak) "지금 ${span(p.startHour, p.endHourExclusive)}가 이 자리 단속이 가장 많은 시간이에요."
        else "이 자리는 ${span(p.startHour, p.endHourExclusive)}에 단속이 몰려요."

    /** "오후 2시부터 5시 사이", "오전 11시부터 오후 2시 사이". */
    fun span(start: Int, endExclusive: Int): String {
        val sameHalf = (start < 12) == (endExclusive % 24 < 12) && endExclusive % 24 != 0
        val end = when {
            endExclusive % 24 == 0 -> "밤 12시"
            sameHalf -> clock(endExclusive % 24, 0).substringAfter(' ')
            else -> clock(endExclusive % 24, 0)
        }
        return "${clock(start, 0)}부터 $end 사이"
    }

    fun sentenceFor(f: Finding, fine: Int): String {
        val where = if (f.certain) "" else "근처일 수 있어요. "
        val fineWords = "과태료 ${Engine.won(fine)}"
        return when (f.rule) {
            Rule.HYDRANT -> if (f.certain) "소화전 ${meters(f.distanceM)} 앞이라 바로 신고될 수 있어요. $fineWords." else "소화전 ${where}5미터 안은 주정차 금지예요."
            Rule.INTERSECTION, Rule.JUNCTION -> "교차로 모퉁이 가까이일 수 있어요. 모퉁이 5미터 안은 주정차 금지예요."
            Rule.CROSSWALK, Rule.CROSSWALK_PT -> if (f.certain) "횡단보도 ${meters(f.distanceM)} 앞이라 바로 신고될 수 있어요. $fineWords." else "횡단보도 ${where}10미터 안은 주정차 금지예요."
            Rule.BUS_STOP -> if (f.certain) "버스정류장 ${meters(f.distanceM)} 앞이라 바로 신고될 수 있어요. $fineWords." else "버스정류장 ${where}10미터 안은 주정차 금지예요."
            Rule.SCHOOL_ZONE -> "어린이보호구역 안일 수 있어요. 저녁 8시까지는 $fineWords."
            Rule.NO_STOP_LINE -> if (f.certain) "황색 실선 구간이라 주정차 금지예요. $fineWords." else "황색 실선 구간 ${where}"
            Rule.NO_PARK_LINE -> "황색 점선 구간이라 5분 이내 정차만 돼요."
            Rule.NO_STOP_BOX -> "정차금지지대예요. $fineWords."
            Rule.CCTV -> "고정 단속 카메라 구간이에요. 10분이 지나면 찍혀요."
        }.trim()
    }

    fun headline(level: Level, findings: List<Finding>, risk: EnforcementRisk?, coverage: Coverage): String {
        val top = findings.firstOrNull()
        return when (level) {
            Level.DANGER -> top?.rule?.title?.let { "주정차 금지: $it" } ?: "주정차 금지 구역"
            Level.CAUTION -> top?.rule?.title?.let { "주의: $it" }
                ?: risk?.let { if (it.clustered) "단속이 잦은 자리 (15m 안 연 ${it.close}건)" else "단속이 잦은 자리 (연 ${it.yearly}건)" } ?: "주의"
            Level.INFO -> risk?.takeIf { it.level >= Level.INFO }?.let { "참고: 이 근처 연 ${it.yearly}건 단속" }
                ?: top?.rule?.title?.let { "참고: $it 근처" } ?: "참고 정보 있음"
            Level.CLEAR -> if (coverage == Coverage.PRECISE) "확인된 금지 표시 없음" else "기본 레이어에서 금지 표시 없음"
        }
    }

    /** Spoken lot name: on-street lots are named after the road, so say what they are. */
    fun lotName(lot: ParkingLot): String {
        val n = lot.name.trim()
        return when {
            n.isEmpty() -> "가까운 주차장"
            n.contains("주차") -> n
            lot.publicLot -> "$n 공영주차장"
            else -> "$n 주차장"
        }
    }

    /** Picks the particle by the last syllable's final consonant (받침): 이/가, 은/는, 을/를. */
    fun josa(word: String, withFinal: String, withoutFinal: String): String {
        val c = word.trimEnd().lastOrNull() ?: return withoutFinal
        if (c in '가'..'힣') return if ((c - '가') % 28 != 0) withFinal else withoutFinal
        if (c.isDigit()) return if (c in "0136789") withFinal else withoutFinal // 영,일,삼,육,칠,팔,구 end in a consonant
        return withoutFinal
    }

    /** "으로/로": ㄹ 받침 takes 로. */
    fun euro(word: String): String {
        val c = word.trimEnd().lastOrNull() ?: return "로"
        if (c !in '가'..'힣') return "로"
        val jong = (c - '가') % 28
        return if (jong == 0 || jong == 8) "로" else "으로"
    }

    fun meters(d: Double): String {
        val m = d.roundToInt()
        return if (m <= 1) "1미터" else "${m}미터"
    }

    fun distanceWords(d: Double): String = when {
        d < 100 -> "${(d / 10).roundToInt() * 10}미터"
        d < 1000 -> "${(d / 50).roundToInt() * 50}미터"
        else -> "${(d / 100).roundToInt() / 10.0}킬로미터"
    }

    fun clock(h: Int, m: Int): String {
        val ampm = if (h < 12) "오전" else "오후"
        val hh = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return if (m == 0) "$ampm ${hh}시" else "$ampm ${hh}시 ${m}분"
    }
}
