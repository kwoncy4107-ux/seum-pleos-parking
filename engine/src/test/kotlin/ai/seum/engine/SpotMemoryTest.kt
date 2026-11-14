package ai.seum.engine

import ai.seum.engine.FakeMap.Companion.GANGNAM
import ai.seum.engine.FakeMap.Companion.off
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotMemoryTest {
    private val cross = SpotMemory.severity(Rule.CROSSWALK)
    private val dashed = SpotMemory.severity(Rule.NO_PARK_LINE)
    private val hydrant = SpotMemory.severity(Rule.HYDRANT)

    @Test
    fun `new spot is not muted`() {
        assertFalse(SpotMemory().isMuted(GANGNAM, cross, 100))
    }

    @Test
    fun `one acknowledgement is not enough, two mute the spot`() {
        val m = SpotMemory()
        m.acknowledge(GANGNAM, cross, 100)
        assertFalse(m.isMuted(GANGNAM, cross, 101))
        m.acknowledge(GANGNAM, cross, 101)
        assertTrue(m.isMuted(GANGNAM, cross, 102))
    }

    @Test
    fun `parking a few metres away counts as the same spot`() {
        val m = SpotMemory()
        m.acknowledge(GANGNAM, cross, 100)
        m.acknowledge(off(GANGNAM, 8.0, 3.0), cross, 101)
        assertTrue(m.isMuted(off(GANGNAM, -5.0, 4.0), cross, 102))
        assertEquals(1, m.size)
    }

    @Test
    fun `a spot 30m away is a different spot`() {
        val m = SpotMemory()
        m.acknowledge(GANGNAM, cross, 100); m.acknowledge(GANGNAM, cross, 101)
        assertFalse(m.isMuted(off(GANGNAM, 30.0, 0.0), cross, 102))
    }

    @Test
    fun `muted spot still speaks for a stronger warning`() {
        val m = SpotMemory()
        m.acknowledge(GANGNAM, dashed, 100); m.acknowledge(GANGNAM, dashed, 101)
        assertTrue(m.isMuted(GANGNAM, dashed, 102))
        assertFalse(m.isMuted(GANGNAM, hydrant, 102))
    }

    @Test
    fun `a stronger warning has to be acknowledged twice again`() {
        val m = SpotMemory()
        m.acknowledge(GANGNAM, dashed, 100); m.acknowledge(GANGNAM, dashed, 101)
        m.acknowledge(GANGNAM, hydrant, 102)
        assertFalse(m.isMuted(GANGNAM, hydrant, 103))
        m.acknowledge(GANGNAM, hydrant, 103)
        assertTrue(m.isMuted(GANGNAM, hydrant, 104))
        assertTrue(m.isMuted(GANGNAM, dashed, 104), "weaker warnings stay muted")
    }

    @Test
    fun `unmute speaks again`() {
        val m = SpotMemory()
        m.acknowledge(GANGNAM, cross, 100); m.acknowledge(GANGNAM, cross, 101)
        m.unmute(off(GANGNAM, 3.0, 0.0))
        assertFalse(m.isMuted(GANGNAM, cross, 102))
    }

    @Test
    fun `spots expire after 180 days`() {
        val m = SpotMemory()
        m.acknowledge(GANGNAM, cross, 100); m.acknowledge(GANGNAM, cross, 101)
        assertTrue(m.isMuted(GANGNAM, cross, 281))
        assertFalse(m.isMuted(GANGNAM, cross, 282))
        assertEquals(0, m.size)
    }

    @Test
    fun `memory is capped and drops the least recently used spot`() {
        val m = SpotMemory(maxSpots = 3)
        for (i in 0 until 4) m.acknowledge(off(GANGNAM, i * 100.0, 0.0), cross, 100L + i)
        assertEquals(3, m.size)
        assertTrue(m.all().none { Geo.distanceM(it.pos, GANGNAM) < 1 }, "oldest spot dropped")
    }

    @Test
    fun `serialize and load round trip`() {
        val m = SpotMemory()
        m.acknowledge(GANGNAM, cross, 100); m.acknowledge(GANGNAM, cross, 101)
        m.acknowledge(off(GANGNAM, 200.0, 0.0), dashed, 102)
        val text = m.serialize()
        val n = SpotMemory().apply { load(text) }
        assertEquals(m.all(), n.all())
        assertTrue(n.isMuted(GANGNAM, cross, 103))
        assertTrue(text.length < 100, "about 40 bytes a spot: $text")
    }

    @Test
    fun `load ignores broken lines`() {
        val n = SpotMemory().apply { load("garbage\n37.5,127.0,2,5,100,1\n1,2,3") }
        assertEquals(1, n.size)
    }

    @Test
    fun `severity of a verdict uses its top finding, or 1 for history only`() {
        val m = FakeMap().apply { point(Layer.HYDRANT, off(GANGNAM, 2.0, 0.0)) }
        val v = Engine(m).judge(GANGNAM, 3.0, java.time.LocalDateTime.of(2026, 11, 13, 12, 0))
        assertEquals(hydrant, SpotMemory.severityOf(v))
        val h = FakeMap().apply { enforce(GANGNAM, mapOf(12 to 200)) }
        assertEquals(1, SpotMemory.severityOf(Engine(h).judge(GANGNAM, 3.0, java.time.LocalDateTime.of(2026, 11, 13, 10, 0))))
    }
}
