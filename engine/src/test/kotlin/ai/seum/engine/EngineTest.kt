package ai.seum.engine

import ai.seum.engine.FakeMap.Companion.GANGNAM
import ai.seum.engine.FakeMap.Companion.off
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineTest {
    private val noon = LocalDateTime.of(2026, 11, 13, 12, 30)
    private val morning = LocalDateTime.of(2026, 11, 13, 10, 0)
    private val night = LocalDateTime.of(2026, 11, 13, 22, 0)

    private fun judge(m: FakeMap, at: LatLon = GANGNAM, acc: Double = 3.0, now: LocalDateTime = noon) =
        Engine(m).judge(at, acc, now)

    // ---------- silence when nothing applies ----------

    @Test
    fun `empty map is clear and silent`() {
        val v = judge(FakeMap())
        assertEquals(Level.CLEAR, v.level)
        assertNull(v.speech)
        assertEquals(0, v.fineWon)
    }

    @Test
    fun `clear in Seoul says precise coverage`() {
        assertEquals("확인된 금지 표시 없음", judge(FakeMap(precise = true)).headline)
    }

    @Test
    fun `clear outside Seoul never claims full check`() {
        val v = judge(FakeMap(precise = false))
        assertEquals(Coverage.BASIC, v.coverage)
        assertEquals("기본 레이어에서 금지 표시 없음", v.headline)
    }

    @Test
    fun `far features are ignored`() {
        val m = FakeMap()
        m.point(Layer.HYDRANT, off(GANGNAM, 40.0, 0.0))
        m.polygon(Layer.CROSSWALK, off(GANGNAM, 60.0, -3.0), off(GANGNAM, 60.0, 3.0), off(GANGNAM, 64.0, 3.0), off(GANGNAM, 64.0, -3.0))
        assertEquals(Level.CLEAR, judge(m).level)
    }

    // ---------- hydrant ----------

    @ParameterizedTest
    @CsvSource("0.5", "2.0", "4.0", "4.9")
    fun `hydrant within 5m is danger with 80k fine`(d: Double) {
        val m = FakeMap().apply { point(Layer.HYDRANT, off(GANGNAM, d, 0.0)) }
        val v = judge(m)
        assertEquals(Level.DANGER, v.level)
        assertEquals(80_000, v.fineWon)
        assertTrue(v.findings.first().certain)
    }

    @ParameterizedTest
    @CsvSource("5.5", "8.0", "9.9")
    fun `hydrant just outside 5m is caution because of gps error`(d: Double) {
        val m = FakeMap().apply { point(Layer.HYDRANT, off(GANGNAM, d, 0.0)) }
        val v = judge(m, acc = 3.0)
        assertEquals(Level.CAUTION, v.level)
        assertFalse(v.findings.first().certain)
        assertEquals(0, v.fineWon, "an uncertain finding must not claim a fine")
    }

    @Test
    fun `poor gps accuracy widens the caution band`() {
        val m = FakeMap().apply { point(Layer.HYDRANT, off(GANGNAM, 18.0, 0.0)) }
        assertEquals(Level.CLEAR, judge(m, acc = 5.0).level)
        assertEquals(Level.CAUTION, judge(m, acc = 15.0).level)
    }

    @Test
    fun `hydrant speech names the distance and fine`() {
        val m = FakeMap().apply { point(Layer.HYDRANT, off(GANGNAM, 3.0, 0.0)) }
        val s = judge(m).speech!!
        assertTrue(s.contains("소화전 3미터"), s)
        assertTrue(s.contains("8만 원"), s)
    }

    @Test
    fun `nearest hydrant wins when several are close`() {
        val m = FakeMap().apply {
            point(Layer.HYDRANT, off(GANGNAM, 4.0, 0.0))
            point(Layer.HYDRANT, off(GANGNAM, 1.0, 0.0))
        }
        val f = judge(m).findings.single { it.rule == Rule.HYDRANT }
        assertEquals(1.0, f.distanceM, 0.05)
    }

    // ---------- crosswalk ----------

    @Test
    fun `parked on the crosswalk is danger`() {
        val m = FakeMap().apply {
            polygon(Layer.CROSSWALK, off(GANGNAM, -2.0, -3.0), off(GANGNAM, -2.0, 3.0), off(GANGNAM, 2.0, 3.0), off(GANGNAM, 2.0, -3.0))
        }
        val v = judge(m)
        assertEquals(Level.DANGER, v.level)
        assertEquals(0.0, v.findings.first().distanceM)
    }

    @Test
    fun `crosswalk 8m away is danger`() {
        val m = FakeMap().apply {
            polygon(Layer.CROSSWALK, off(GANGNAM, 8.0, -3.0), off(GANGNAM, 8.0, 3.0), off(GANGNAM, 12.0, 3.0), off(GANGNAM, 12.0, -3.0))
        }
        assertEquals(Level.DANGER, judge(m).level)
        assertEquals(40_000, judge(m).fineWon)
    }

    @Test
    fun `crosswalk 13m away is caution`() {
        val m = FakeMap().apply {
            polygon(Layer.CROSSWALK, off(GANGNAM, 13.0, -3.0), off(GANGNAM, 13.0, 3.0), off(GANGNAM, 17.0, 3.0), off(GANGNAM, 17.0, -3.0))
        }
        assertEquals(Level.CAUTION, judge(m).level)
    }

    // ---------- bus stop ----------

    @Test
    fun `bus stop within 10m is danger`() {
        val m = FakeMap().apply { point(Layer.BUS_STOP, off(GANGNAM, 0.0, 7.0)) }
        val v = judge(m)
        assertEquals(Level.DANGER, v.level)
        assertTrue(v.speech!!.contains("버스정류장"))
    }

    // ---------- lane lines ----------

    @Test
    fun `yellow solid line at the curb is danger`() {
        val m = FakeMap().apply { line(Layer.NO_STOP_LINE, off(GANGNAM, 0.0, 1.5), off(GANGNAM, 30.0, 1.5)) }
        val v = judge(m)
        assertEquals(Level.DANGER, v.level)
        assertTrue(v.headline.contains("황색 실선"), v.headline)
    }

    @Test
    fun `yellow dashed line is caution and says 5 minutes`() {
        val m = FakeMap().apply { line(Layer.NO_PARK_LINE, off(GANGNAM, -10.0, 1.5), off(GANGNAM, 30.0, 1.5)) }
        val v = judge(m)
        assertEquals(Level.CAUTION, v.level)
        assertTrue(v.speech!!.contains("5분 이내 정차"), v.speech)
    }

    @Test
    fun `lane line across the road is not matched`() {
        val m = FakeMap().apply { line(Layer.NO_STOP_LINE, off(GANGNAM, -10.0, 15.0), off(GANGNAM, 30.0, 15.0)) }
        assertEquals(Level.CLEAR, judge(m).level)
    }

    @Test
    fun `solid line beats dashed line when both are near`() {
        val m = FakeMap().apply {
            line(Layer.NO_PARK_LINE, off(GANGNAM, -10.0, 1.0), off(GANGNAM, 30.0, 1.0))
            line(Layer.NO_STOP_LINE, off(GANGNAM, -10.0, 2.0), off(GANGNAM, 30.0, 2.0))
        }
        val v = judge(m)
        assertEquals(Level.DANGER, v.level)
        assertEquals(Rule.NO_STOP_LINE, v.findings.first().rule)
        assertNull(v.peak, "no time advice when the spot is already a no-stop spot")
    }

    // ---------- school zone ----------

    @Test
    fun `school zone near the gate during the day is caution with 12만 fine in speech`() {
        val m = FakeMap().apply { point(Layer.SCHOOL_ZONE, off(GANGNAM, 60.0, 0.0)) }
        val v = judge(m, now = morning)
        assertEquals(Level.CAUTION, v.level)
        assertTrue(v.speech!!.contains("12만 원"), v.speech)
    }

    @Test
    fun `school zone far from the gate is screen-only info`() {
        val m = FakeMap().apply { point(Layer.SCHOOL_ZONE, off(GANGNAM, 250.0, 0.0)) }
        val v = judge(m, now = morning)
        assertEquals(Level.INFO, v.level)
        assertNull(v.speech)
    }

    @Test
    fun `school zone at night is only info and silent`() {
        val m = FakeMap().apply { point(Layer.SCHOOL_ZONE, off(GANGNAM, 60.0, 0.0)) }
        val v = judge(m, now = night)
        assertEquals(Level.INFO, v.level)
        assertNull(v.speech)
    }

    @Test
    fun `school zone fine falls back to general fine at night`() {
        assertEquals(40_000, Engine(FakeMap()).fineFor(Rule.SCHOOL_ZONE, night))
        assertEquals(120_000, Engine(FakeMap()).fineFor(Rule.SCHOOL_ZONE, morning))
    }

    @Test
    fun `school zone never produces a certain fine`() {
        val m = FakeMap().apply { point(Layer.SCHOOL_ZONE, off(GANGNAM, 10.0, 0.0)) }
        assertEquals(0, judge(m, now = morning).fineWon)
    }

    // ---------- cctv ----------

    @Test
    fun `fixed cctv within 50m is danger and mentions 10 minutes`() {
        val m = FakeMap().apply { point(Layer.CCTV, off(GANGNAM, 30.0, 0.0)) }
        val v = judge(m)
        assertEquals(Level.DANGER, v.level)
        assertTrue(v.speech!!.contains("10분"), v.speech)
    }

    @Test
    fun `cctv slightly beyond 50m is caution`() {
        val m = FakeMap().apply { point(Layer.CCTV, off(GANGNAM, 53.0, 0.0)) }
        assertEquals(Level.CAUTION, judge(m).level)
    }

    // ---------- priority ----------

    @Test
    fun `findings are ordered by level then distance`() {
        val m = FakeMap().apply {
            point(Layer.CCTV, off(GANGNAM, 20.0, 0.0))
            point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0))
            point(Layer.SCHOOL_ZONE, off(GANGNAM, 50.0, 0.0))
        }
        val rules = judge(m, now = morning).findings.map { it.rule }
        assertEquals(listOf(Rule.HYDRANT, Rule.CCTV, Rule.SCHOOL_ZONE), rules)
    }

    @Test
    fun `fine is the largest among certain danger findings`() {
        val m = FakeMap().apply {
            point(Layer.CCTV, off(GANGNAM, 20.0, 0.0))
            point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0))
        }
        assertEquals(80_000, judge(m).fineWon)
    }

    @Test
    fun `speech leads with the most severe finding`() {
        val m = FakeMap().apply {
            point(Layer.CCTV, off(GANGNAM, 20.0, 0.0))
            point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0))
        }
        assertTrue(judge(m).speech!!.startsWith("소화전"))
    }

    // ---------- enforcement history ----------

    @Test
    fun `busy enforcement spot alone is caution`() {
        val m = FakeMap().apply { enforce(GANGNAM, mapOf(12 to 100, 13 to 80, 14 to 20)) }
        val v = judge(m, now = morning)
        assertEquals(Level.CAUTION, v.level)
        assertEquals(200, v.risk!!.yearly)
        assertEquals(12, v.risk!!.peakStartHour)
    }

    @Test
    fun `moderate enforcement history is info`() {
        val m = FakeMap().apply { enforce(GANGNAM, mapOf(12 to 20, 18 to 20)) }
        assertEquals(Level.INFO, judge(m).risk!!.level)
    }

    @Test
    fun `sparse enforcement history is ignored`() {
        val m = FakeMap().apply { enforce(GANGNAM, mapOf(3 to 2)) }
        val v = judge(m)
        assertEquals(Level.CLEAR, v.level)
        assertNull(v.peak)
        assertNull(v.speech)
    }

    @Test
    fun `upcoming peak is spoken at the P moment`() {
        val m = FakeMap().apply { enforce(GANGNAM, mapOf(12 to 100, 13 to 80, 14 to 20)) }
        val v = judge(m, now = morning) // 10:00, peak 12-15
        assertEquals(PeakTiming(12, 15, inPeak = false, minutesUntil = 120), v.peak)
        assertTrue(v.speech!!.startsWith("이 자리는 작년 단속이 200건 있었고, 오후 12시부터 3시 사이에 몰려요."), v.speech)
    }

    @Test
    fun `inside the peak the speech says now`() {
        val m = FakeMap().apply { enforce(GANGNAM, mapOf(12 to 100, 13 to 80, 14 to 20)) }
        val v = judge(m, now = noon)
        assertEquals(true, v.peak!!.inPeak)
        assertTrue(v.speech!!.contains("지금 오후 12시부터 3시 사이가 이 자리 단속이 가장 많은 시간이에요."), v.speech)
    }

    @Test
    fun `no time advice when the peak is more than 8 hours away`() {
        val m = FakeMap().apply { enforce(GANGNAM, mapOf(12 to 100, 13 to 80)) }
        assertNull(judge(m, now = LocalDateTime.of(2026, 11, 13, 1, 0)).peak)
    }

    @Test
    fun `peak window wraps around midnight`() {
        val m = FakeMap().apply { enforce(GANGNAM, mapOf(23 to 60, 0 to 60, 1 to 60)) }
        assertEquals(23, judge(m).risk!!.peakStartHour)
    }

    @Test
    fun `moderate history with upcoming peak speaks only the time`() {
        val m = FakeMap().apply { enforce(GANGNAM, mapOf(12 to 30, 13 to 10)) }
        val v = judge(m, now = morning)
        assertEquals(Level.INFO, v.level)
        assertNotNull(v.peak)
        assertEquals("이 자리는 오후 12시부터 3시 사이에 단속이 몰려요.", v.speech)
        assertNull(v.parking, "no parking pitch for an info-level spot")
    }

    @Test
    fun `fixed camera spot gets no time advice in speech`() {
        val m = FakeMap().apply {
            point(Layer.CCTV, off(GANGNAM, 53.0, 0.0))
            enforce(GANGNAM, mapOf(12 to 100, 13 to 80))
        }
        val v = judge(m, now = morning)
        assertTrue(!v.speech!!.contains("몰려요"), v.speech)
    }

    @Test
    fun `peak share is the fraction of records in the peak window`() {
        val m = FakeMap().apply { enforce(GANGNAM, mapOf(12 to 50, 13 to 30, 14 to 20, 2 to 100)) }
        val r = judge(m).risk!!
        assertEquals(0.5, r.peakShare, 0.01)
    }

    // ---------- parking suggestion ----------

    @Test
    fun `danger suggests the nearest public lot`() {
        val m = FakeMap().apply {
            point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0))
            lot("먼 공영주차장", off(GANGNAM, 600.0, 0.0))
            lot("가까운 공영주차장", off(GANGNAM, 200.0, 0.0))
        }
        val v = judge(m)
        assertEquals("가까운 공영주차장", v.parking!!.lot.name)
        assertTrue(v.speech!!.contains("200미터 앞에 가까운 공영주차장이 있어요. 안내할까요?"), v.speech)
    }

    @Test
    fun `public lot is preferred over a closer private lot`() {
        val m = FakeMap().apply {
            point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0))
            lot("민영", off(GANGNAM, 100.0, 0.0), publicLot = false)
            lot("공영", off(GANGNAM, 300.0, 0.0))
        }
        assertEquals("공영", judge(m).parking!!.lot.name)
    }

    @Test
    fun `side-street junction within 9m warns as a corner, never certain`() {
        val v = judge(FakeMap().apply { point(Layer.JUNCTION, off(GANGNAM, 9.0, 0.0)) })
        assertEquals(Level.CAUTION, v.level)
        val f = v.findings.single()
        assertEquals(Rule.JUNCTION, f.rule)
        assertFalse(f.certain)
        assertTrue(v.speech!!.contains("교차로 모퉁이"), v.speech)
    }

    @Test
    fun `side-street junction farther than 9m plus GPS slack is ignored`() {
        val v = judge(FakeMap().apply { point(Layer.JUNCTION, off(GANGNAM, 16.0, 0.0)) })
        assertEquals(Level.CLEAR, v.level)
    }

    @Test
    fun `tickets clustered within 15m make the spot a caution on their own`() {
        val m = FakeMap().apply {
            enforce(GANGNAM, mapOf(19 to 40, 20 to 30, 21 to 20, 13 to 10))
            closeRecords = 38
        }
        val v = judge(m)
        assertEquals(Level.CAUTION, v.level)
        assertTrue(v.risk!!.clustered)
        assertEquals("단속이 잦은 자리 (15m 안 연 38건)", v.headline)
        assertTrue(v.speech!!.startsWith("바로 이 자리 15미터 안에서 작년에 단속이 38건 있었어요."), v.speech)
    }

    @Test
    fun `a few tickets nearby stay informational`() {
        val m = FakeMap().apply {
            enforce(GANGNAM, mapOf(19 to 40, 20 to 30, 21 to 20, 13 to 10))
            closeRecords = 10
        }
        val v = judge(m)
        assertEquals(Level.INFO, v.level)
    }

    @Test
    fun `without a public lot the nearest other lot is suggested, named before unnamed`() {
        val m = FakeMap().apply {
            point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0))
            lot("", off(GANGNAM, 80.0, 0.0), publicLot = false)
            lot("서교 360", off(GANGNAM, 250.0, 0.0), publicLot = false)
        }
        assertEquals("서교 360", judge(m).parking!!.lot.name)
        val unnamedOnly = FakeMap().apply {
            point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0))
            lot("", off(GANGNAM, 80.0, 0.0), publicLot = false)
        }
        val v = judge(unnamedOnly)
        assertTrue(v.speech!!.contains("가까운 주차장이 있어요"), v.speech)
    }

    @Test
    fun `no parking suggestion when the spot is clear`() {
        val m = FakeMap().apply { lot("공영", off(GANGNAM, 100.0, 0.0)) }
        assertNull(judge(m).parking)
    }

    @Test
    fun `lots beyond 800m are not suggested`() {
        val m = FakeMap().apply {
            point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0))
            lot("너무 먼 공영", off(GANGNAM, 900.0, 0.0))
        }
        assertNull(judge(m).parking)
    }

    @Test
    fun `fee text formats base fee`() {
        val lot = ParkingLot(1, "a", GANGNAM, true, 5, 250, 5, 250, false, "")
        assertEquals("5분 250원", Engine.feeText(lot))
        assertEquals("무료", Engine.feeText(lot.copy(free = true)))
        assertEquals("요금 정보 없음", Engine.feeText(lot.copy(baseFeeWon = null)))
    }

    @ParameterizedTest
    @CsvSource("40000,4만 원", "80000,8만 원", "120000,12만 원", "15000,1.5만 원", "500,500원")
    fun `won formatting reads naturally`(v: Int, s: String) {
        assertEquals(s, Engine.won(v))
    }

    // ---------- location reliability (underground / indoor car parks) ----------

    @ParameterizedTest
    @CsvSource("0,3.0,true", "9900,8.0,true", "10000,8.0,true", "10100,8.0,false", "60000,8.0,false",
        "1000,24.9,true", "1000,25.0,true", "1000,25.1,false", "1000,80.0,false", "30000,40.0,false")
    fun `location is trusted only when fresh and precise`(ageMs: Long, acc: Double, ok: Boolean) {
        assertEquals(ok, Engine(FakeMap()).locationReliable(ageMs, acc))
    }

    @Test
    fun `stale and accuracy limits are configurable`() {
        val e = Engine(FakeMap(), Engine.Config(staleFixMs = 5_000, maxAccuracyM = 15.0))
        assertFalse(e.locationReliable(6_000, 5.0))
        assertFalse(e.locationReliable(1_000, 16.0))
        assertTrue(e.locationReliable(4_000, 14.0))
    }

    // ---------- private ground: apartment complexes and parking lots ----------

    @Test
    fun `deep inside an apartment complex is silent even next to a hydrant`() {
        val m = FakeMap().apply {
            area(AreaKind.APT, "도곡렉슬아파트", GANGNAM, 60.0)
            point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0))
            enforce(GANGNAM, mapOf(12 to 300))
        }
        val v = judge(m)
        assertEquals(Level.CLEAR, v.level)
        assertNull(v.speech)
        assertEquals(0, v.fineWon)
        assertTrue(v.findings.isEmpty())
        assertEquals("도곡렉슬아파트", v.privateArea?.name)
        assertTrue(v.headline.contains("단지 안"), v.headline)
    }

    @Test
    fun `parking lot ground is silent too`() {
        val m = FakeMap().apply {
            area(AreaKind.PARKING, "", GANGNAM, 30.0)
            line(Layer.NO_STOP_LINE, off(GANGNAM, -10.0, 1.0), off(GANGNAM, 10.0, 1.0))
        }
        val v = judge(m)
        assertNull(v.speech)
        assertTrue(v.headline.startsWith("주차장 주차장 안").not() && v.headline.contains("주차장 안"), v.headline)
    }

    @Test
    fun `near the complex boundary the street outside is still judged`() {
        // car 3 m inside the edge: GPS may be wrong, the curb outside could be the real spot
        val m = FakeMap().apply {
            area(AreaKind.APT, "OO아파트", off(GANGNAM, 0.0, 27.0), 30.0)
            point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0))
        }
        val v = judge(m)
        assertEquals(Level.DANGER, v.level)
        assertNull(v.privateArea)
    }

    @Test
    fun `outside any complex nothing changes`() {
        val m = FakeMap().apply {
            area(AreaKind.APT, "OO아파트", off(GANGNAM, 200.0, 0.0), 30.0)
            point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0))
        }
        assertEquals(Level.DANGER, judge(m).level)
    }
}
