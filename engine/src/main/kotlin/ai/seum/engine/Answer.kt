package ai.seum.engine

enum class Answer { YES, NO, UNKNOWN }

/** Maps a short Korean STT result to yes/no. Negatives are checked first ("안 해도 돼" contains "해"). */
object AnswerParser {
    private val no = listOf("아니", "아뇨", "괜찮", "됐어", "싫어", "안 해", "안해", "필요 없", "필요없", "취소", "그만", "노")
    private val yes = listOf("응", "네", "예", "그래", "좋아", "안내", "가자", "해줘", "해 줘", "부탁", "알았", "오케이", "ok", "yes", "어")

    fun parse(text: String?): Answer {
        val t = text?.trim()?.lowercase()?.replace(Regex("[.!?,~]"), "") ?: return Answer.UNKNOWN
        if (t.isEmpty()) return Answer.UNKNOWN
        if (no.any { t.contains(it) }) return Answer.NO
        if (yes.any { t == it || t.startsWith(it) || (it.length >= 2 && t.contains(it)) }) return Answer.YES
        return Answer.UNKNOWN
    }
}
