package ai.seum.app.service

import ai.seum.app.SeumApp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime

/**
 * Demo only: lets tools/demo.ps1 set the clock the engine judges at, so time-dependent advice
 * (enforcement peaks, school hours) is the same on every run. Guarded by DUMP, which adb shell holds
 * and ordinary apps cannot get.
 *
 *   adb shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ei hour 11 --ei minute 0
 *   adb shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ez clear true
 *   adb shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ez reset_tally true
 *   adb shell am broadcast -n ai.seum.app/.service.DemoClockReceiver --ez gps_lost true   (지하주차장 진입 흉내)
 */
class DemoClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SeumApp
        val guard = app.guard
        if (intent.getBooleanExtra("reset_tally", false)) app.store.resetTally()
        if (intent.getBooleanExtra("reset_intro", false)) { app.store.introSeen = false; app.store.hintsSpoken = 0 }
        if (intent.hasExtra("gps_lost")) app.location.gpsLost = intent.getBooleanExtra("gps_lost", false)
        if (intent.getBooleanExtra("clear", false)) {
            // Back to real signals: real clock and real GPS (drops a position picked in the demo panel).
            guard.demoClock = null
            app.location.demoOverride = null
            app.location.demoHeading = null
            return
        }
        val h = intent.getIntExtra("hour", -1)
        val m = intent.getIntExtra("minute", 0)
        if (h in 0..23 && m in 0..59) guard.demoClock = LocalDateTime.now().withHour(h).withMinute(m).withSecond(0)
    }
}
