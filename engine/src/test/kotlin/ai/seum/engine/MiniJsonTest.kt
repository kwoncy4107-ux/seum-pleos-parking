package ai.seum.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MiniJsonTest {
    @Test
    fun `reads strings numbers and booleans`() {
        val m = MiniJson.parse("""{"a": "가나", "b": 12, "c": true, "d": null, "e": 1.5}""")
        assertEquals(mapOf("a" to "가나", "b" to "12", "c" to "1", "d" to "", "e" to "1.5"), m)
    }

    @Test
    fun `handles escapes and unicode`() {
        assertEquals("a\"b\\c", MiniJson.parse("""{"k": "a\"b\\c"}""")["k"])
        assertEquals("가", MiniJson.parse("""{"k": "가"}""")["k"])
    }

    @Test
    fun `empty and null inputs`() {
        assertEquals(emptyMap(), MiniJson.parse(null))
        assertEquals(emptyMap(), MiniJson.parse(""))
        assertEquals(emptyMap(), MiniJson.parse("{}"))
    }

    @Test
    fun `values with commas inside strings`() {
        assertEquals("1,000원", MiniJson.parse("""{"fee": "1,000원", "x": 1}""")["fee"])
    }

    @Test
    fun `crosswalk intersection flag`() {
        assertEquals("1", MiniJson.parse("""{"x": 1}""")["x"])
    }
}
