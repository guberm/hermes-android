package dev.guber.hermesandroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.guber.hermesandroid.data.CompletedReply
import dev.guber.hermesandroid.data.QueuedPrompt

class ReplyNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.cancel(LEGACY_WAITING_NOTIFICATION_ID)
        manager.deleteNotificationChannel(LEGACY_WAITING)
        manager.createNotificationChannel(NotificationChannel(REPLIES, "Hermes replies", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(reply: CompletedReply) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(reply.sessionId, 2, builder(REPLIES, reply.sessionId)
            .setContentTitle(reply.title.ifBlank { "Hermes replied" })
            .setContentText(reply.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reply.text))
            .setAutoCancel(true).build())
    }

    fun showQueued(item: QueuedPrompt) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(item.id, QUEUED_NOTIFICATION_ID, builder(REPLIES, item.sessionId)
            .setContentTitle("Queued message")
            .setContentText(item.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.text))
            .addAction(0, "Cancel queued", actionPendingIntent(ACTION_CANCEL_QUEUE, item.sessionId, item.id))
            .addAction(0, "Send now", actionPendingIntent(ACTION_SEND_QUEUE, item.sessionId, item.id))
            .setAutoCancel(true).build())
    }

    fun cancelQueued(itemId: String) = manager.cancel(itemId, QUEUED_NOTIFICATION_ID)

    private fun actionPendingIntent(action: String, sessionId: String, queueId: String): PendingIntent =
        PendingIntent.getBroadcast(context, (action + queueId).hashCode(), Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_QUEUE_ID, queueId)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

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
        const val ACTION_CANCEL_QUEUE = "dev.guber.hermesandroid.CANCEL_QUEUE"
        const val ACTION_SEND_QUEUE = "dev.guber.hermesandroid.SEND_QUEUE"
        const val EXTRA_QUEUE_ID = "queue_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val QUEUED_NOTIFICATION_ID = 3
        private const val LEGACY_WAITING = "hermes_waiting"
        private const val LEGACY_WAITING_NOTIFICATION_ID = 1
    }
}
