package com.example.accident_detection.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat

class AlertCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Check if the received action is the one we're interested in
        if (intent.action == "CANCEL_SOS") {
            Log.d("AlertCancelReceiver", "SOS Cancel action received from notification.")

            // Call the static method in the service to cancel the countdown
            AccidentDetectionService.cancelCountdown()

            // After cancelling, dismiss the notification
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.cancel(AccidentDetectionService.ALERT_NOTIFICATION_ID)
        }
    }
}
