package ai.seum.app

import android.content.Context

/** Small persistent state: the running "fines avoided" tally. */
class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("seum", Context.MODE_PRIVATE)

    val savedWon: Int get() = p.getInt(KEY_SAVED, 0)
    val savedCount: Int get() = p.getInt(KEY_SAVED_N, 0)
    val checks: Int get() = p.getInt(KEY_CHECKS, 0)

    fun addCheck() = p.edit().putInt(KEY_CHECKS, checks + 1).apply()

    /** The driver moved away after a warning: count that fine as avoided. */
    fun addSaved(won: Int) = p.edit().putInt(KEY_SAVED, savedWon + won).putInt(KEY_SAVED_N, savedCount + 1).apply()

    /** Demo recordings start from a clean tally. */
    fun resetTally() = p.edit().putInt(KEY_SAVED, 0).putInt(KEY_SAVED_N, 0).putInt(KEY_CHECKS, 0).apply()

    /** Spots where the driver keeps parking despite a warning (SpotMemory text, on this device only). */
    var spots: String?
        get() = p.getString(KEY_SPOTS, null)
        set(v) = p.edit().putString(KEY_SPOTS, v).apply()

    /** The first-run intro was shown and closed. */
    var introSeen: Boolean
        get() = p.getBoolean(KEY_INTRO, false)
        set(v) = p.edit().putBoolean(KEY_INTRO, v).apply()

    /** How many spoken warnings have ended with "where to look" (only the first few do). */
    var hintsSpoken: Int
        get() = p.getInt(KEY_HINTS, 0)
        set(v) = p.edit().putInt(KEY_HINTS, v).apply()

    var onboarded: Boolean
        get() = p.getBoolean(KEY_ONBOARD, false)
        set(v) = p.edit().putBoolean(KEY_ONBOARD, v).apply()

    companion object {
        private const val KEY_SAVED = "saved_won"
        private const val KEY_SAVED_N = "saved_n"
        private const val KEY_CHECKS = "checks"
        private const val KEY_ONBOARD = "onboarded"
        private const val KEY_SPOTS = "spots"
        private const val KEY_INTRO = "intro_seen"
        private const val KEY_HINTS = "hints_spoken"
    }
}
