package ai.seum.engine

/**
 * Remembers spots where the driver keeps parking despite a warning (home, office), so the same warning
 * is not spoken every day. Kept on the device only; small and bounded (≈40 bytes a spot, [maxSpots] spots).
 *
 * A spot is "acknowledged" when the driver declines the warning ("괜찮아요"/"아니") or stays parked
 * past the credit window. After [muteAfter] acknowledgements of the same or a stronger warning within
 * [radiusM], the voice is skipped there (the screen still shows everything). A stronger rule than the
 * one remembered speaks again.
 */
class SpotMemory(
    private val radiusM: Double = 20.0,
    private val muteAfter: Int = 2,
    private val maxSpots: Int = 200,
    private val expireDays: Long = 180,
) {
    data class Spot(val pos: LatLon, val acks: Int, val severity: Int, val lastDay: Long, val muted: Boolean)

    private val spots = ArrayList<Spot>()

    val size: Int get() = spots.size
    fun all(): List<Spot> = spots.toList()

    private fun find(p: LatLon): Int? =
        spots.withIndex().filter { Geo.distanceM(it.value.pos, p) <= radiusM }
            .minByOrNull { Geo.distanceM(it.value.pos, p) }?.index

    /** Should the voice be skipped for a warning of [severity] (see [severityOf]) at [p]? */
    fun isMuted(p: LatLon, severity: Int, today: Long): Boolean {
        prune(today)
        val i = find(p) ?: return false
        val s = spots[i]
        return s.muted && severity <= s.severity
    }

    /** The driver let this warning pass at [p] (declined, or stayed parked). */
    fun acknowledge(p: LatLon, severity: Int, today: Long) {
        prune(today)
        val i = find(p)
        if (i == null) {
            // stored at 6 decimals (~0.1 m), the same precision as the saved text
            val q = LatLon(Math.round(p.lat * 1e6) / 1e6, Math.round(p.lon * 1e6) / 1e6)
            spots += Spot(q, 1, severity, today, muted = muteAfter <= 1)
        } else {
            val s = spots[i]
            val stronger = severity > s.severity
            // A stronger warning starts counting again from 1 so it is spoken until it too is acknowledged.
            val acks = if (stronger) 1 else s.acks + 1
            spots[i] = Spot(s.pos, acks, maxOf(severity, s.severity), today, muted = acks >= muteAfter)
        }
        if (spots.size > maxSpots) spots.remove(spots.minBy { it.lastDay })
    }

    /** "다시 알려 줘": speak again at this spot. */
    fun unmute(p: LatLon) {
        find(p)?.let { spots.removeAt(it) }
    }

    fun clear() = spots.clear()

    private fun prune(today: Long) {
        spots.removeAll { today - it.lastDay > expireDays }
    }

    /** Compact text form for on-device storage: one spot per line. */
    fun serialize(): String = spots.joinToString("\n") {
        "%.6f,%.6f,%d,%d,%d,%d".format(java.util.Locale.ROOT, it.pos.lat, it.pos.lon, it.acks, it.severity, it.lastDay, if (it.muted) 1 else 0)
    }

    fun load(text: String?) {
        spots.clear()
        text?.lineSequence()?.forEach { line ->
            val f = line.split(',')
            if (f.size != 6) return@forEach
            val sev = f[3].toIntOrNull() ?: return@forEach
            val lat = f[0].toDoubleOrNull(); val lon = f[1].toDoubleOrNull()
            val acks = f[2].toIntOrNull(); val day = f[4].toLongOrNull()
            if (lat == null || lon == null || acks == null || day == null) return@forEach
            spots += Spot(LatLon(lat, lon), acks, sev, day, f[5] == "1")
        }
    }

    companion object {
        /** How serious a verdict's warning is: its top finding, or 1 for enforcement history alone. */
        fun severityOf(v: Verdict): Int = v.findings.filter { it.level >= Level.CAUTION }.maxOfOrNull { severity(it.rule) } ?: 1

        /** Rough order of how serious a rule is; used to decide when to speak again. */
        fun severity(r: Rule): Int = when (r) {
            Rule.SCHOOL_ZONE, Rule.INTERSECTION, Rule.JUNCTION -> 1
            Rule.NO_PARK_LINE -> 2
            Rule.CCTV -> 3
            Rule.NO_STOP_LINE, Rule.NO_STOP_BOX -> 4
            Rule.CROSSWALK, Rule.CROSSWALK_PT, Rule.BUS_STOP -> 5
            Rule.HYDRANT -> 6
        }
    }
}
