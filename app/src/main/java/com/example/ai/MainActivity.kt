package com.example.ai

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri

/**
 * MainActivity: Cinematic Neural Interface for FRIDAY (JARVIS-style AI Assistant).
 *
 * Configures the dark futuristic UI, requests necessary runtime permissions (Microphone,
 * Notifications, Calls), initializes and starts FridayService as a 24/7 foreground service,
 * powers on the screen from sleep via modern window flags, and renders holographic HUD animations.
 */
class MainActivity : ComponentActivity(), FridayService.Companion.FridayEventListener {

    private lateinit var tvStatusBadge: TextView
    private lateinit var viewStatusDot: View
    private lateinit var tvListeningState: TextView
    private lateinit var tvWakeHint: TextView
    private lateinit var btnMic: LinearLayout
    private lateinit var tvMicText: TextView
    private lateinit var ivMicIcon: ImageView
    private lateinit var tvConversationLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var btnClearLogs: TextView
    private lateinit var btnSetupPermissions: TextView

    // Rapid Command Simulator Views
    private lateinit var btnCmdWake: TextView
    private lateinit var btnCmdUnlock: TextView
    private lateinit var btnCmdWhatsApp: TextView
    private lateinit var btnCmdYouTube: TextView

    // Modern runtime permissions launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val postNotificationGranted = (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ||
                (permissions[Manifest.permission.POST_NOTIFICATIONS] == true)

        if (recordAudioGranted) {
            appendLogLine("SYSTEM", "Microphone access granted by Boss Anand.")
            startFridayForegroundService()
        } else {
            appendLogLine("ALERT", "Microphone permission denied! Speech recognition cannot function.")
            Toast.makeText(this, "Microphone permission is required for FRIDAY.", Toast.LENGTH_LONG).show()
        }

        if (!postNotificationGranted) {
            appendLogLine("ALERT", "Notification permission denied! Background service may be restricted.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableScreenWakeFlags()
        setContentView(R.layout.activity_main)

        FridayMemory.initialize(this)
        bindViews()
        setupListeners()
        handleUnlockIntent(intent)
        checkAndRequestRequiredPermissions()
    }

    private fun enableScreenWakeFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBannerState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enableScreenWakeFlags()
        handleUnlockIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Register real-time communication hook with FridayService
        FridayService.registerListener(this)
        updatePermissionBannerState()
    }

    override fun onStop() {
        // Unregister listener to avoid memory leaks while activity is paused
        FridayService.unregisterListener()
        super.onStop()
    }

    private fun bindViews() {
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvListeningState = findViewById(R.id.tvListeningState)
        tvWakeHint = findViewById(R.id.tvWakeHint)
        btnMic = findViewById(R.id.btnMic)
        tvMicText = findViewById(R.id.tvMicText)
        ivMicIcon = findViewById(R.id.ivMicIcon)
        tvConversationLogs = findViewById(R.id.tvConversationLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnSetupPermissions = findViewById(R.id.btnSetupPermissions)

        btnCmdWake = findViewById(R.id.btnCmdWake)
        btnCmdUnlock = findViewById(R.id.btnCmdUnlock)
        btnCmdWhatsApp = findViewById(R.id.btnCmdWhatsApp)
        btnCmdYouTube = findViewById(R.id.btnCmdYouTube)
    }

    private fun setupListeners() {
        // Central Voice Button Interaction
        btnMic.setOnClickListener {
            val service = FridayService.instance
            if (service == null) {
                appendLogLine("SYSTEM", "Starting FRIDAY Neural Core...")
                startFridayForegroundService()
            } else {
                appendLogLine("BOSS", "[Mic Tap] Ping FRIDAY")
                service.handleRecognizedSpeech("Hello FRIDAY")
            }
        }

        // Clear Logs Action
        btnClearLogs.setOnClickListener {
            tvConversationLogs.text = getString(R.string.initial_telemetry)
        }

        // Permissions Setup Banner Click
        btnSetupPermissions.setOnClickListener {
            guideThroughSystemPermissions()
        }

        // Rapid Voice Command Simulators
        btnCmdWake.setOnClickListener {
            simulateCommand("Hello FRIDAY")
        }

        btnCmdUnlock.setOnClickListener {
            simulateCommand("Ankit unlock the phone")
        }

        btnCmdWhatsApp.setOnClickListener {
            simulateCommand("Open WhatsApp")
        }

        btnCmdYouTube.setOnClickListener {
            simulateCommand("Open YouTube")
        }
    }

    private fun guideThroughSystemPermissions() {
        // 1. Accessibility Service Check (Essential for PIN Unlock & Background Actions)
        if (!FridayAccessibilityService.isServiceRunning) {
            Toast.makeText(this, "Enable 'FRIDAY' in Accessibility Services to allow PIN unlock & BG automation", Toast.LENGTH_LONG).show()
            appendLogLine("SETUP", "Opening Accessibility Settings for Boss Anand...")
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        // 2. Overlay Permission Check ("Display over other apps")
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow 'Display over other apps' so FRIDAY can launch apps from background", Toast.LENGTH_LONG).show()
            appendLogLine("SETUP", "Opening Overlay Permissions...")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
            return
        }

        // 3. Battery Optimization Exemption Check
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Allow FRIDAY to run unrestricted in the background (No Battery Saver)", Toast.LENGTH_LONG).show()
            appendLogLine("SETUP", "Requesting Battery Optimization Exemption...")
            @SuppressLint("BatteryLife")
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:$packageName".toUri()
            )
            startActivity(intent)
            return
        }

        Toast.makeText(this, "All background and hardware automation permissions are ACTIVE!", Toast.LENGTH_SHORT).show()
        appendLogLine("SYSTEM", "All automation permissions verified. 100% functionality active.")
        updatePermissionBannerState()
    }

    private fun updatePermissionBannerState() {
        val hasAccessibility = FridayAccessibilityService.isServiceRunning
        val hasOverlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val hasBattery = pm.isIgnoringBatteryOptimizations(packageName)

        if (hasAccessibility && hasOverlay && hasBattery) {
            btnSetupPermissions.text = getString(R.string.permissions_granted_text)
            btnSetupPermissions.setTextColor("#00FF66".toColorInt())
        } else {
            btnSetupPermissions.text = getString(R.string.permission_banner_text)
            btnSetupPermissions.setTextColor("#00FFFF".toColorInt())
        }
    }

    private fun simulateCommand(command: String) {
        val service = FridayService.instance
        if (service != null) {
            service.handleRecognizedSpeech(command)
        } else {
            appendLogLine("BOSS", command)
            appendLogLine("SYSTEM", "Initializing service to execute command...")
            startFridayForegroundService()
        }
    }

    private fun handleUnlockIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("EXTRA_REQUEST_DISMISS_KEYGUARD", false) == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
                keyguardManager?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        super.onDismissSucceeded()
                        appendLogLine("SECURITY", "Keyguard dismissed. Device unlocked.")
                    }

                    override fun onDismissError() {
                        super.onDismissError()
                        appendLogLine("SECURITY", "Keyguard dismissal error. Manual unlock required.")
                    }
                })
            }
        }
    }

    // ----------------------------------------------------------------------------------
    // RUNTIME PERMISSIONS & SERVICE START
    // ----------------------------------------------------------------------------------

    private fun checkAndRequestRequiredPermissions() {
        val neededPermissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CALL_PHONE)
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            startFridayForegroundService()
        }
    }

    private fun startFridayForegroundService() {
        val serviceIntent = Intent(this, FridayService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            appendLogLine("SYSTEM", "Foreground Service started. Microphones active.")
        } catch (e: Exception) {
            appendLogLine("ERROR", "Failed to launch FridayService: ${e.message}")
        }
    }

    // ----------------------------------------------------------------------------------
    // FRIDAY EVENT LISTENER IMPLEMENTATION
    // ----------------------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    override fun onStatusChanged(status: String) {
        runOnUiThread {
            tvStatusBadge.text = status
            tvListeningState.text = "STATUS: $status"

            when {
                status.contains("LISTENING", ignoreCase = true) -> {
                    tvStatusBadge.setTextColor("#00FFFF".toColorInt())
                    viewStatusDot.setBackgroundColor("#00FFFF".toColorInt())
                }
                status.contains("SPEAKING", ignoreCase = true) -> {
                    tvStatusBadge.setTextColor("#00FF66".toColorInt())
                    viewStatusDot.setBackgroundColor("#00FF66".toColorInt())
                }
                status.contains("SLEEP", ignoreCase = true) || status.contains("STANDBY", ignoreCase = true) -> {
                    tvStatusBadge.setTextColor("#94A3B8".toColorInt())
                    viewStatusDot.setBackgroundColor("#94A3B8".toColorInt())
                }
                status.contains("THINKING", ignoreCase = true) -> {
                    tvStatusBadge.setTextColor("#8A2BE2".toColorInt())
                    viewStatusDot.setBackgroundColor("#8A2BE2".toColorInt())
                }
                else -> {
                    tvStatusBadge.setTextColor("#00FFFF".toColorInt())
                    viewStatusDot.setBackgroundColor("#00FFFF".toColorInt())
                }
            }
        }
    }

    override fun onLogMessage(sender: String, message: String) {
        runOnUiThread {
            appendLogLine(sender, message)
        }
    }

    override fun onStateChanged(isListening: Boolean, isSleepMode: Boolean) {
        runOnUiThread {
            when {
                isSleepMode -> {
                    tvMicText.text = getString(R.string.status_standby)
                    ivMicIcon.alpha = 0.5f
                    btnMic.alpha = 0.7f
                }
                isListening -> {
                    tvMicText.text = getString(R.string.mic_active)
                    ivMicIcon.alpha = 1.0f
                    btnMic.alpha = 1.0f
                }
                else -> {
                    tvMicText.text = getString(R.string.status_standby)
                    ivMicIcon.alpha = 0.8f
                    btnMic.alpha = 0.9f
                }
            }
        }
    }

    override fun onProtocolAnimation(targetName: String) {
        runOnUiThread {
            tvStatusBadge.text = getString(R.string.status_executing, targetName)
            tvStatusBadge.setTextColor("#00FFFF".toColorInt())
            viewStatusDot.setBackgroundColor("#00FFFF".toColorInt())

            // Cinematic Holographic HUD Pulse Animation
            btnMic.animate()
                .scaleX(1.22f)
                .scaleY(1.22f)
                .setDuration(350)
                .withEndAction {
                    btnMic.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(350)
                        .start()
                }
                .start()
        }
    }

    private fun appendLogLine(sender: String, message: String) {
        val line = "[$sender] $message\n"
        tvConversationLogs.append(line)
        scrollLogs.post {
            scrollLogs.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
