package ai.seum.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParkTriggerTest {
    private val fired = mutableListOf<Long>()
    private fun trigger(settle: Long = 1500, startInPark: Boolean = false) =
        ParkTrigger(settle, startInPark) { fired += it }

    @Test
    fun `D to P fires once after settling`() {
        val t = trigger()
        t.onGear(Gear.DRIVE, 0)
        t.onGear(Gear.PARK, 1000)
        t.tick(2000)
        assertTrue(fired.isEmpty(), "must wait for the settle time")
        t.tick(2500)
        assertEquals(listOf(2500L), fired)
    }

    @Test
    fun `staying in P does not fire again`() {
        val t = trigger()
        t.onGear(Gear.DRIVE, 0)
        t.onGear(Gear.PARK, 0)
        t.tick(2000); t.tick(5000); t.onGear(Gear.PARK, 9000); t.tick(20_000)
        assertEquals(1, fired.size)
    }

    @Test
    fun `short P blip while parallel parking is ignored`() {
        val t = trigger()
        t.onGear(Gear.DRIVE, 0)
        t.onGear(Gear.PARK, 1000)
        t.onGear(Gear.REVERSE, 1500)
        t.tick(4000)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `R to P also fires`() {
        val t = trigger()
        t.onGear(Gear.REVERSE, 0)
        t.onGear(Gear.PARK, 100)
        t.tick(1700)
        assertEquals(1, fired.size)
    }

    @Test
    fun `app started while already parked does not fire by default`() {
        val t = trigger()
        t.onGear(Gear.PARK, 0)
        t.tick(10_000)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `demo mode fires when started in P`() {
        val t = trigger(startInPark = true)
        t.onGear(Gear.PARK, 0)
        t.tick(1500)
        assertEquals(1, fired.size)
    }

    @Test
    fun `second stop fires again after driving`() {
        val t = trigger()
        t.onGear(Gear.DRIVE, 0); t.onGear(Gear.PARK, 0); t.tick(2000)
        t.onGear(Gear.DRIVE, 3000); t.onGear(Gear.PARK, 9000); t.tick(11_000)
        assertEquals(2, fired.size)
    }

    @Test
    fun `zero settle fires immediately`() {
        val t = trigger(settle = 0)
        t.onGear(Gear.DRIVE, 0)
        t.onGear(Gear.PARK, 10)
        assertEquals(listOf(10L), fired)
    }

    @Test
    fun `unknown gear cancels a pending park`() {
        val t = trigger()
        t.onGear(Gear.DRIVE, 0)
        t.onGear(Gear.PARK, 0)
        t.onGear(Gear.UNKNOWN, 500)
        t.tick(5000)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `isParked tracks the last gear`() {
        val t = trigger()
        assertFalse(t.isParked)
        t.onGear(Gear.PARK, 0)
        assertTrue(t.isParked)
        t.onGear(Gear.DRIVE, 1)
        assertFalse(t.isParked)
    }

    @Test
    fun `P while still rolling waits until speed reaches zero`() {
        val t = trigger()
        t.onGear(Gear.DRIVE, 0)
        t.onSpeed(3.0f, 0)
        t.onGear(Gear.PARK, 100)
        t.tick(3000)
        assertTrue(fired.isEmpty(), "must not fire while moving")
        t.onSpeed(0.0f, 3500)
        assertEquals(listOf(3500L), fired)
    }

    @Test
    fun `creeping below the threshold counts as stopped`() {
        val t = trigger()
        t.onGear(Gear.DRIVE, 0)
        t.onSpeed(0.3f, 0)
        t.onGear(Gear.PARK, 0)
        t.tick(1600)
        assertEquals(1, fired.size)
    }

    @Test
    fun `missing speed signal falls back to gear only`() {
        val t = trigger()
        t.onSpeed(null, 0)
        t.onGear(Gear.DRIVE, 0)
        t.onGear(Gear.PARK, 0)
        t.tick(1600)
        assertEquals(1, fired.size)
        assertTrue(t.isStopped)
    }

    @Test
    fun `negative speed (reversing) is treated by magnitude`() {
        val t = trigger()
        t.onGear(Gear.REVERSE, 0)
        t.onSpeed(-2.0f, 0)
        t.onGear(Gear.PARK, 0)
        t.tick(2000)
        assertTrue(fired.isEmpty())
        assertFalse(t.isStopped)
    }

    @ParameterizedTest
    @CsvSource("4,PARK", "2,REVERSE", "1,NEUTRAL", "8,DRIVE", "16,DRIVE", "4096,DRIVE", "0,UNKNOWN", "-1,UNKNOWN")
    fun `AAOS VehicleGear values map to gears`(raw: Int, g: Gear) {
        assertEquals(g, ParkTrigger.fromVehicleGear(raw))
    }
}
