package dev.guber.hermesandroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.guber.hermesandroid.data.CompletedReply

class ReplyNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(REPLIES, "Hermes replies", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(WAITING, "Waiting for a reply", NotificationManager.IMPORTANCE_LOW))
    }

    fun startWaiting() = ContextCompat.startForegroundService(context, Intent(context, ReplyWaitService::class.java))
    fun stopWaiting() { context.stopService(Intent(context, ReplyWaitService::class.java)) }

    fun show(reply: CompletedReply) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(reply.sessionId, 2, builder(REPLIES, reply.sessionId)
            .setContentTitle(reply.title.ifBlank { "Hermes replied" })
            .setContentText(reply.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reply.text))
            .setAutoCancel(true).build())
    }

    fun waitingNotification() = builder(WAITING)
        .setContentTitle("Hermes is working")
        .setContentText("Waiting for your response. Tap to open the conversation.")
        .setOngoing(true).setOnlyAlertOnce(true).build()

    private fun builder(channel: String, sessionId: String? = null): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("session_id", sessionId)
            data = android.net.Uri.parse("hermes://conversation/${sessionId.orEmpty()}")
        }
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
    }

    companion object {
        const val REPLIES = "hermes_replies"
        const val WAITING = "hermes_waiting"
    }
}

class ReplyWaitService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, ReplyNotifications(this).waitingNotification())
        if (!(application as HermesApplication).viewModel.hasPendingReply) stopSelf()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as HermesApplication).viewModel.cancelBackgroundWait()
        stopSelf()
    }
}
