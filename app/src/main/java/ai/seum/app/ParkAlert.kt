package ai.seum.app

import ai.seum.app.ui.MainActivity
import ai.seum.engine.Engine
import ai.seum.engine.Level
import ai.seum.engine.Speech
import ai.seum.engine.Verdict
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Notification for the P moment. The voice is the primary channel; this is the "look later" copy.
 * At that moment the navigation app is usually in front, Android 14 does not let a background app bring
 * its screen forward, and Pleos pops heads-up cards only for privileged system apps (verified on the
 * emulator), so the verdict lands in the notification center; tapping it opens the full screen.
 */
class ParkAlert(context: Context, private val screenVisible: () -> Boolean) {
    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "주차 자리 경고", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "기어를 P에 넣었을 때 이 자리가 주정차 금지·단속 구역이면 알려요"
                setSound(null, null) // the spoken message is the sound
            },
        )
    }

    fun show(v: Verdict) {
        if (screenVisible()) return
        val open = PendingIntent.getActivity(
            app, 1, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (v.level) {
            Level.DANGER -> "세우면 안 되는 자리"
            else -> "주의가 필요한 자리"
        }
        val lines = buildList {
            add(v.headline)
            if (v.fineWon > 0) add("예상 과태료 ${Engine.won(v.fineWon)}")
            v.peak?.let { add("단속 집중 ${Speech.span(it.startHour, it.endHourExclusive)}") }
            v.parking?.let { add("${Speech.lotName(it.lot)} ${Speech.distanceWords(it.distanceM)}") }
        }
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_seum)
            .setContentTitle(title)
            // Car notification center shows title + one line; BigTextStyle is not supported there.
            .setContentText(lines.take(2).joinToString(" · "))
            // Not CATEGORY_NAVIGATION: AAOS only pops navigation-category cards from the app holding nav focus.
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(if (v.level == Level.DANGER) 0xFFF85149.toInt() else 0xFFE3B341.toInt())
            .setTimeoutAfter(10 * 60_000L)
            .build()
        runCatching { nm.notify(ID, n) }.onFailure { Log.w("Seum/Alert", "notify failed: ${it.message}") }
    }

    fun clear() = nm.cancel(ID)

    companion object {
        private const val CHANNEL = "park_alert"
        private const val ID = 11
    }
}
