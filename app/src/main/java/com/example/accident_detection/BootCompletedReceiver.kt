package com.example.accident_detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.accident_detection.services.AccidentDetectionService

class BootCompletedReceiver : BroadcastReceiver() {

    // This method is called when the BroadcastReceiver is receiving an Intent broadcast.
    override fun onReceive(context: Context, intent: Intent) {
        // We only want to act on the boot completed event.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootCompletedReceiver", "Boot completed event received. Starting service.")

            // Create an intent to start our AccidentDetectionService.
            val serviceIntent = Intent(context, AccidentDetectionService::class.java)

            // Start the service in the foreground.
            // Using context.startForegroundService() is required for apps targeting Android 8 (API 26) and higher.
            context.startForegroundService(serviceIntent)
        }
    }
}
