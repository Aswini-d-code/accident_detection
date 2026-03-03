package com.example.accident_detection.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.accident_detection.MainActivity
import com.example.accident_detection.R
import com.example.accident_detection.api.AccidentRequestBody
import com.example.accident_detection.api.NetworkModule
import com.google.android.gms.location.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.math.sqrt

class AccidentDetectionService : Service(), SensorEventListener {

    private var countdownJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    // --- Sensor Management ---
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // --- Location Management ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastKnownSpeed: Float = 0.0f // Speed in meters/second

    // --- Detection Logic and Thresholds ---
    // MODIFICATION 1: Lowered thresholds for easier shake testing
    private val gForceThreshold = 7.0 // Lowered for a firm shake (2.5G is sensitive)
    private val rotationThreshold = 12.0 // Lowered for a twist motion
    private val speedDropThreshold = 8.9f // This will be ignored by our new logic
    private val triggerCooldownMs = 30000L // 30 second cooldown.
    private var lastTriggerTimestamp = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service is being created.")
        instance = this // Set the static instance

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Service is starting.")
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
        registerListeners()
        return START_STICKY
    }

    private fun registerListeners() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(2000)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                lastKnownSpeed = if (location.hasSpeed()) location.speed else 0.0f
            }
        }
    }

    // MODIFICATION 2: Replaced the onSensorChanged method with a simplified version for shake testing.
    override fun onSensorChanged(event: SensorEvent?) {
        val currentTime = System.currentTimeMillis()
        if (countdownJob?.isActive == true || (currentTime - lastTriggerTimestamp < triggerCooldownMs)) {
            return // Don't process new events if countdown is active or in cooldown
        }

        var trigger = false
        var triggerType = ""

        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (gForce > gForceThreshold) {
                    trigger = true
                    triggerType = "G-Force ($gForce > $gForceThreshold)"
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val rotation = sqrt(x * x + y * y + z * z)
                if (rotation > rotationThreshold) {
                    trigger = true
                    triggerType = "Rotation ($rotation > $rotationThreshold)"
                }
            }
        }

        // --- SIMPLIFIED LOGIC ---
        // Trigger if EITHER the G-force or Rotation threshold is met.
        if (trigger) {
            lastTriggerTimestamp = currentTime
            Log.e(TAG, "!!! SIMPLE SHAKE DETECTED !!! Triggered by: $triggerType")
            startCountdownFlow()
        }
    }

    private fun startCountdownFlow() {
        countdownJob = serviceScope.launch {
            Log.d(TAG, "Starting 30-second countdown.")
            showCountdownNotification()
            delay(30000L) // Wait for 30 seconds
            Log.d(TAG, "Countdown finished. Sending SOS.")
            triggerSosFlow() // This will now be called if not cancelled
            NotificationManagerCompat.from(this@AccidentDetectionService).cancel(ALERT_NOTIFICATION_ID)
        }
    }

    private fun showCountdownNotification() {
        val cancelIntent = Intent(this, AlertCancelReceiver::class.java).apply { action = "CANCEL_SOS" }
        val cancelPendingIntent = PendingIntent.getBroadcast(this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car_crash)
            .setContentTitle("Accident Detected!")
            .setContentText("Sending alert in 30 seconds. Press Cancel to stop.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI)
            .setOngoing(true)
            .addAction(R.drawable.ic_cancel, "CANCEL", cancelPendingIntent)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify(ALERT_NOTIFICATION_ID, notification)
        } else {
            Log.e(TAG, "Cannot show countdown notification. POST_NOTIFICATIONS permission not granted.")
        }
    }

    private fun createForegroundNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("Accident Shield is Active")
            .setContentText("We are monitoring for accidents in the background.")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val foregroundChannel = NotificationChannel(FOREGROUND_CHANNEL_ID, "Accident Monitoring Service", NotificationManager.IMPORTANCE_LOW)
            val alertChannel = NotificationChannel(ALERT_CHANNEL_ID, "High Priority Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Channel for accident detection alerts"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(foregroundChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun triggerSosFlow() {
        serviceScope.launch {
            val userId = Firebase.auth.currentUser?.uid
            if (userId == null) {
                Log.e(TAG, "Cannot trigger SOS. User is not logged in.")
                return@launch
            }

            var userName = "A user"
            try {
                val userDoc = Firebase.firestore.collection("users").document(userId).get().await()
                userName = userDoc.getString("name") ?: "A user"
            } catch (e: Exception) {
                Log.e(TAG, "Could not fetch user's name.", e)
            }

            var location: Location? = null
            try {
                location = fusedLocationClient.lastLocation.await()
            } catch (e: SecurityException) {
                Log.e(TAG, "Location permission not granted for SOS.", e)
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get location for SOS.", e)
            }

            if (location == null) {
                Log.e(TAG, "Cannot trigger SOS. Location is not available.")
                return@launch
            }

            Log.d(TAG, "Proceeding with SOS for user '$userName' at location: ${location.latitude}, ${location.longitude}")
            sendSosNotification(userId, userName, location)
        }
    }

    private suspend fun sendSosNotification(userId: String, userName: String, location: Location) {
        try {
            val requestBody = AccidentRequestBody(userId, userName, location.latitude, location.longitude)
            val reportResponse = NetworkModule.notificationApi.reportAccident(requestBody)

            if (reportResponse.isSuccessful && reportResponse.body() != null) {
                val accidentId = reportResponse.body()!!.accidentId
                Log.d(TAG, "SUCCESS: /accident call from service returned ID: $accidentId")
                delay(2000)
                val alertResponse = NetworkModule.notificationApi.triggerAlerts(accidentId)
                if (alertResponse.isSuccessful) {
                    Log.d(TAG, "SUCCESS: /trigger_alerts call from service was successful.")
                } else {
                    Log.e(TAG, "FAILED: /trigger_alerts call failed with code ${alertResponse.code()}")
                }
            } else {
                Log.e(TAG, "FAILED: /accident call failed with code ${reportResponse.code()}. Body: ${reportResponse.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SOS Network Exception in service", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Service is being destroyed.")
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        instance = null // Clear the static instance
    }

    companion object {
        private const val TAG = "AccidentDetectionSrvc"
        private const val FOREGROUND_CHANNEL_ID = "ACCIDENT_DETECTION_FOREGROUND"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        const val ALERT_CHANNEL_ID = "ACCIDENT_ALERT_CHANNEL"
        const val ALERT_NOTIFICATION_ID = 101

        private var instance: AccidentDetectionService? = null

        // This is the corrected function
        fun cancelCountdown() {
            // Get a stable reference to the running service instance
            val serviceInstance = instance
            if (serviceInstance?.countdownJob?.isActive == true) {
                // 1. Cancel the coroutine job that is counting down
                serviceInstance.countdownJob?.cancel()
                Log.d(TAG, "Countdown successfully cancelled by user.")

                // 2. IMPORTANT: The service must also dismiss its own notification
                val context = serviceInstance.applicationContext
                NotificationManagerCompat.from(context).cancel(ALERT_NOTIFICATION_ID)
            } else {
                Log.w(TAG, "Cancel requested, but countdown was not active or service instance was null.")
                // As a safety measure, try to cancel the notification anyway
                instance?.let {
                    NotificationManagerCompat.from(it.applicationContext).cancel(ALERT_NOTIFICATION_ID)
                }
            }
        }
    }
}
