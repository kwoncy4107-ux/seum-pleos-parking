package ai.seum.app.service

import ai.seum.app.R
import ai.seum.app.SeumApp
import ai.seum.app.ui.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the gear listener alive while another app (usually the navigation) is in front.
 * Without it the process can be killed and the P moment is missed.
 */
class GuardService : Service() {
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
        }.onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }.isSuccess
        if (!started) {
            (application as SeumApp).guard.start()
            started = true
        }
        if (!ok) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        if (started) (application as SeumApp).guard.stop()
        started = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_guard), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_seum)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.guard_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Seum/Service"
        private const val CHANNEL = "guard"
        private const val NOTIF_ID = 7

        fun start(context: Context) {
            val i = Intent(context, GuardService::class.java)
            runCatching { context.startForegroundService(i) }
                .onFailure { Log.w(TAG, "could not start service: ${it.message}") }
        }
    }
}

/** Starts listening after the head unit boots, once the driver has opened the app at least once. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as SeumApp
        if (app.store.onboarded) GuardService.start(context)
    }
}
