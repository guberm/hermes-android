package dev.guber.hermesandroid

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val queueId = intent.getStringExtra(ReplyNotifications.EXTRA_QUEUE_ID).orEmpty()
        if (queueId.isBlank()) return
        val viewModel = (context.applicationContext as HermesApplication).viewModel
        when (intent.action) {
            ReplyNotifications.ACTION_CANCEL_QUEUE -> {
                viewModel.cancelQueued(queueId)
                context.getSystemService(NotificationManager::class.java)
                    .cancel(queueId, ReplyNotifications.QUEUED_NOTIFICATION_ID)
            }
            ReplyNotifications.ACTION_SEND_QUEUE -> viewModel.sendQueuedNow(queueId)
            else -> return
        }
    }
}
