package com.example.accident_detection

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.accident_detection.api.AccidentRequestBody
import com.example.accident_detection.api.NetworkModule
import com.example.accident_detection.databinding.ActivityMainBinding
import com.example.accident_detection.services.AccidentDetectionService
import com.google.android.gms.location.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// --- Data class to hold the UI state ---
data class UiState(
    val isLoading: Boolean = false,
    val userName: String? = null,
    val hasContacts: Boolean = false
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val auth by lazy { Firebase.auth }
    private val db by lazy { Firebase.firestore }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null

    private val _uiState = MutableStateFlow(UiState())
    private val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val contactActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.d(TAG, "Returned from EmergencyContactActivity. Refreshing UI state.")
            checkContactStatus()
        }

    // UPDATED: Activity result launcher for handling multiple permissions
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
        val coarseLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
        val notificationsGranted = permissions.getOrDefault(Manifest.permission.POST_NOTIFICATIONS, false)
        val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.getOrDefault(Manifest.permission.ACCESS_BACKGROUND_LOCATION, false)
        } else {
            true // On older versions, this permission is granted automatically with fine/coarse location
        }

        if (fineLocationGranted || coarseLocationGranted) {
            Log.d(TAG, "Location permission granted.")
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Location is required for SOS functionality.", Toast.LENGTH_LONG).show()
        }

        if (!notificationsGranted) {
            Toast.makeText(this, "Notifications are required for automatic alerts.", Toast.LENGTH_LONG).show()
        }

        if (!backgroundLocationGranted) {
            Toast.makeText(this, "Background location access is needed for full functionality.", Toast.LENGTH_LONG).show()
        }

        // After permissions are handled, start the detection service
        startAccidentDetectionService()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupClickListeners()
        observeUiState()

        // UPDATED: Centralized permission check and service start
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        checkContactStatus() // Check contacts and user name every time the screen is shown
    }

    private fun setupClickListeners() {
        binding.ivSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnAddContact.setOnClickListener {
            val intent = Intent(this, EmergencyContactActivity::class.java)
            contactActivityResultLauncher.launch(intent)
        }
        binding.btnSos.setOnClickListener {
            lifecycleScope.launch {
                handleSosClick()
            }
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                uiState.collect { state ->
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.btnSos.isEnabled = !state.isLoading
                    binding.tvUserName.text = if (state.userName != null) "Welcome, ${state.userName}" else "Welcome"
                    binding.layoutNoContacts.visibility = if (state.hasContacts) View.GONE else View.VISIBLE
                    binding.btnSos.visibility = if (state.hasContacts) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun checkContactStatus() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        db.collection("users").document(currentUser.uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val userName = document.getString("name")
                    val contacts = document.get("emergencyContacts") as? List<*>
                    _uiState.update { it.copy(userName = userName, hasContacts = !contacts.isNullOrEmpty()) }
                } else {
                    _uiState.update { it.copy(hasContacts = false) }
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Error fetching user data", it)
                _uiState.update { state -> state.copy(hasContacts = false) }
            }
    }

    // NEW & IMPROVED: Checks all required permissions and launches request if needed.
    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Background location is requested separately after fine location is granted.
            // For simplicity in this flow, we can request it here, but best practice is a two-step request.
            permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissionsToRequest")
            permissionRequest.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "All permissions already granted.")
            startLocationUpdates()
            startAccidentDetectionService()
        }
    }

    // NEW: Starts the background service correctly.
    private fun startAccidentDetectionService() {
        val intent = Intent(this, AccidentDetectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d(TAG, "Requested to start AccidentDetectionService.")
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleSosClick() {
        _uiState.update { it.copy(isLoading = true) }
        Toast.makeText(this, "Getting current location...", Toast.LENGTH_SHORT).show()

        val userId = auth.currentUser?.uid
        val userName = uiState.value.userName ?: "A friend"

        if (!uiState.value.hasContacts) {
            Toast.makeText(this, "Please add emergency contacts first.", Toast.LENGTH_SHORT).show()
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, "Could not verify user. Please log in again.", Toast.LENGTH_SHORT).show()
            _uiState.update { it.copy(isLoading = false) }
            return
        }

        var freshLocation: Location? = null
        try {
            freshLocation = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            Log.d(TAG, "Successfully fetched on-demand location.")
        } catch (e: Exception) {
            Log.e(TAG, "Could not get on-demand location", e)
        }

        if (freshLocation == null) {
            freshLocation = currentLocation
        }

        if (freshLocation == null) {
            Toast.makeText(this, "Could not get current location. Please ensure location is enabled.", Toast.LENGTH_LONG).show()
            _uiState.update { it.copy(isLoading = false) }
            return
        }

        Log.d(TAG, "All checks passed. Proceeding with manual SOS.")
        sendSosNotification(userId, userName, freshLocation)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60000)
            .setMinUpdateIntervalMillis(30000)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                currentLocation = locationResult.lastLocation
                Log.d(TAG, "Background location updated: ${currentLocation?.latitude}, ${currentLocation?.longitude}")
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun sendSosNotification(userId: String, userName: String, location: Location) {
        Toast.makeText(this, "Reporting accident...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val requestBody = AccidentRequestBody(
                    userId = userId,
                    name = userName,
                    lat = location.latitude,
                    lon = location.longitude
                )
                val reportResponse = NetworkModule.notificationApi.reportAccident(requestBody)

                if (reportResponse.isSuccessful && reportResponse.body() != null) {
                    val accidentId = reportResponse.body()!!.accidentId
                    Log.d(TAG, "SUCCESS: /accident call returned ID: $accidentId")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Accident reported. Triggering alerts...", Toast.LENGTH_SHORT).show()
                    }
                    delay(2000)
                    val alertResponse = NetworkModule.notificationApi.triggerAlerts(accidentId)
                    withContext(Dispatchers.Main) {
                        if (alertResponse.isSuccessful) {
                            Log.d(TAG, "SUCCESS: /trigger_alerts call was successful.")
                            Toast.makeText(this@MainActivity, "Emergency alerts sent successfully!", Toast.LENGTH_LONG).show()
                        } else {
                            Log.e(TAG, "FAILED: /trigger_alerts call failed with code ${alertResponse.code()}")
                            Toast.makeText(this@MainActivity, "Failed to trigger alerts. Server Error: ${alertResponse.code()}", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    val errorBody = reportResponse.errorBody()?.string()
                    Log.e(TAG, "FAILED: /accident call failed with code ${reportResponse.code()}. Body: $errorBody")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to report accident. Server error: ${reportResponse.code()}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SOS Network Exception", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "SOS failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
