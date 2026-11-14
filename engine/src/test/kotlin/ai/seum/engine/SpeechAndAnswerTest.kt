package ai.seum.engine

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SpeechAndAnswerTest {

    @ParameterizedTest
    @ValueSource(strings = ["응", "네", "예", "그래", "좋아", "안내해줘", "응 안내해", "네 부탁해요", "가자", "오케이", "응!"])
    fun `yes answers`(s: String) = assertEquals(Answer.YES, AnswerParser.parse(s))

    @ParameterizedTest
    @ValueSource(strings = ["아니", "아니요", "아뇨", "괜찮아", "됐어", "안 해도 돼", "필요 없어", "취소", "그만"])
    fun `no answers`(s: String) = assertEquals(Answer.NO, AnswerParser.parse(s))

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "날씨 어때", "음악 틀어줘"])
    fun `unrelated speech is unknown`(s: String) = assertEquals(Answer.UNKNOWN, AnswerParser.parse(s))

    @Test
    fun `null is unknown`() = assertEquals(Answer.UNKNOWN, AnswerParser.parse(null))

    @ParameterizedTest
    @CsvSource("0,0,오전 12시", "9,5,오전 9시 5분", "11,50,오전 11시 50분", "12,0,오후 12시", "13,30,오후 1시 30분", "23,59,오후 11시 59분")
    fun `clock reads in Korean`(h: Int, m: Int, s: String) = assertEquals(s, Speech.clock(h, m))

    @ParameterizedTest
    @CsvSource("0.4,1미터", "3.2,3미터", "9.6,10미터")
    fun `meters round to whole numbers`(d: Double, s: String) = assertEquals(s, Speech.meters(d))

    @ParameterizedTest
    @CsvSource("43,40미터", "180,200미터", "230,250미터", "780,800미터", "1240,1.2킬로미터")
    fun `distance words round sensibly`(d: Double, s: String) = assertEquals(s, Speech.distanceWords(d))

    @ParameterizedTest
    @CsvSource("주차장,이", "센터,가", "테헤란로26길,이", "제2,가", "제3,이", "P,가")
    fun `subject particle follows the final consonant`(w: String, j: String) =
        assertEquals(j, Speech.josa(w, "이", "가"))

    @ParameterizedTest
    @CsvSource("공영주차장,으로", "센터,로", "테헤란로26길,로", "역삼문화공원,으로")
    fun `direction particle`(w: String, j: String) = assertEquals(j, Speech.euro(w))

    @Test
    fun `on-street lot names get a suffix`() {
        val lot = ParkingLot(1, "테헤란로26길", LatLon(0.0, 0.0), true, null, null, null, null, false, "")
        assertEquals("테헤란로26길 공영주차장", Speech.lotName(lot))
        assertEquals("역삼문화공원 제1호 공영주차장", Speech.lotName(lot.copy(name = "역삼문화공원 제1호 공영주차장")))
        assertEquals("언구비 주차장", Speech.lotName(lot.copy(name = "언구비", publicLot = false)))
    }

    @ParameterizedTest
    @CsvSource("14,17,오후 2시부터 5시 사이", "11,14,오전 11시부터 오후 2시 사이", "9,12,오전 9시부터 오후 12시 사이", "21,24,오후 9시부터 밤 12시 사이", "23,26,오후 11시부터 오전 2시 사이", "8,11,오전 8시부터 11시 사이")
    fun `peak span reads naturally`(a: Int, b: Int, s: String) = assertEquals(s, Speech.span(a, b))

    @Test
    fun `headline for danger names the rule`() {
        val f = Finding(Rule.HYDRANT, 2.0, true, Level.DANGER, null)
        assertEquals("주정차 금지: 소화전 5m 이내", Speech.headline(Level.DANGER, listOf(f), null, Coverage.PRECISE))
    }

    @Test
    fun `uncertain hydrant sentence does not claim a fine`() {
        val f = Finding(Rule.HYDRANT, 7.0, false, Level.CAUTION, null)
        val s = Speech.sentenceFor(f, 80_000)
        assertEquals(false, s.contains("과태료"), s)
    }
}
