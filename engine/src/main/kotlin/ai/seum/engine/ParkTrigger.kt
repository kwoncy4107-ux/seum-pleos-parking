package ai.seum.engine

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * Turns the raw gear and speed streams into exactly one "parked" event per stop.
 *
 * - Fires when the gear has settled in P and the car is standing still (speed 0) after having been
 *   in R/N/D. Not on app start while already in P, unless [fireIfStartedInPark] is set for the demo.
 * - Ignores P blips shorter than [settleMs] (e.g. shuffling D→P→R while parallel parking).
 * - Speed 0 is a cross-check, not a separate condition: a real transmission will not engage P while
 *   moving, so it guards against glitchy gear readings (and the Virtual Console, which allows P at speed).
 *   If the speed signal is unavailable, P alone is accepted.
 * - Re-arms when the car leaves P again.
 */
class ParkTrigger(
    private val settleMs: Long = 1500,
    private val fireIfStartedInPark: Boolean = false,
    /** At or below this speed the car counts as stopped. */
    private val stoppedMps: Float = 0.5f,
    private val onParked: (atMs: Long) -> Unit,
) {
    private var last: Gear = Gear.UNKNOWN
    private var armed = fireIfStartedInPark
    private var parkSinceMs: Long? = null
    private var firedThisStop = false
    private var speedMps: Float? = null

    fun onGear(g: Gear, nowMs: Long) {
        if (g == last) { tick(nowMs); return }
        last = g
        when (g) {
            Gear.PARK -> {
                parkSinceMs = nowMs
                if (settleMs == 0L) tick(nowMs)
            }
            Gear.REVERSE, Gear.NEUTRAL, Gear.DRIVE -> {
                armed = true
                firedThisStop = false
                parkSinceMs = null
            }
            Gear.UNKNOWN -> parkSinceMs = null
        }
    }

    /** Vehicle speed in m/s; null when the signal is unavailable. */
    fun onSpeed(mps: Float?, nowMs: Long) {
        speedMps = mps?.let { kotlin.math.abs(it) }
        tick(nowMs)
    }

    /** Call periodically (or on every event) so the settle timer can elapse. */
    fun tick(nowMs: Long) {
        val since = parkSinceMs ?: return
        if (!isStopped) return
        if (armed && !firedThisStop && nowMs - since >= settleMs) {
            firedThisStop = true
            armed = false
            onParked(nowMs)
        }
    }

    val isParked: Boolean get() = last == Gear.PARK

    val isStopped: Boolean get() = speedMps?.let { it <= stoppedMps } ?: true

    companion object {
        /** android.car.VehicleGear constants (GEAR_SELECTION / CURRENT_GEAR values). */
        fun fromVehicleGear(v: Int): Gear = when (v) {
            0x0004 -> Gear.PARK
            0x0002 -> Gear.REVERSE
            0x0001 -> Gear.NEUTRAL
            0x0008 -> Gear.DRIVE
            in 0x0010..0x1000 -> Gear.DRIVE // GEAR_1..GEAR_9
            else -> Gear.UNKNOWN
        }
    }
}
