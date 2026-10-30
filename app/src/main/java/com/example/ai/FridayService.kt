package com.example.ai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * FridayService: 24/7 Background Core Neural Engine for FRIDAY AI Assistant.
 *
 * Runs as a foreground service with microphone capabilities, continuous SpeechRecognizer,
 * automatic error restart loops, 60s inactivity sleep timeout, hardware automation triggers
 * via FridayAccessibilityService (Silent PIN 226263, Global Actions, Universal App Launcher),
 * Local Memory Core (FridayMemory), A-to-Z Phone Controls (FridayDeviceController),
 * and conversational Gemini API integration with Indian English TTS.
 */
class FridayService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "FridayService"
        const val CHANNEL_ID = "friday_service_channel"
        const val NOTIFICATION_ID = 1001
        private const val INACTIVITY_TIMEOUT_MS = 60_000L // 1 minute inactivity
        private const val RESTART_SPEECH_DELAY_MS = 2_000L // 2-second error restart delay

        // Dynamic Gemini API Key setting
        var geminiApiKey: String = "AQ.Ab8RN6KooPfK16y89QsJNss4tmw2HQfm1ZhyuX7sXlsIL-6nsA"

        // Configurable User Device PIN (Defaults to 226263)
        var userPin: String
            get() = FridayMemory.userPin
            set(value) {
                FridayMemory.userPin = value
            }

        // Communication listener interface for UI telemetry & animations
        interface FridayEventListener {
            fun onStatusChanged(status: String)
            fun onLogMessage(sender: String, message: String)
            fun onStateChanged(isListening: Boolean, isSleepMode: Boolean)
            fun onProtocolAnimation(targetName: String)
        }

        private var activeListener: FridayEventListener? = null
        private val messageHistory = mutableListOf<Pair<String, String>>()

        fun registerListener(listener: FridayEventListener?) {
            activeListener = listener
            listener?.let { l ->
                synchronized(messageHistory) {
                    messageHistory.takeLast(25).forEach { (sender, msg) ->
                        l.onLogMessage(sender, msg)
                    }
                }
            }
        }

        fun unregisterListener() {
            activeListener = null
        }

        private fun notifyStatus(status: String) {
            activeListener?.onStatusChanged(status)
        }

        private fun notifyLog(sender: String, message: String) {
            synchronized(messageHistory) {
                if (messageHistory.size > 100) messageHistory.removeAt(0)
                messageHistory.add(sender to message)
            }
            activeListener?.onLogMessage(sender, message)
        }

        private fun notifyState(isListening: Boolean, isSleepMode: Boolean) {
            activeListener?.onStateChanged(isListening, isSleepMode)
        }

        private fun notifyProtocolAnimation(targetName: String) {
            activeListener?.onProtocolAnimation(targetName)
        }

        // Singleton instance hook for direct interactions from UI
        var instance: FridayService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var isTtsSpeaking = false
    private var isListening = false
    private var isSleepMode = false
    private var lastInteractionTime = System.currentTimeMillis()

    private var wakeLock: PowerManager.WakeLock? = null

    // Inactivity watchdog: transitions to sleep mode after 60 seconds of silence
    private val inactivityWatchdog = object : Runnable {
        override fun run() {
            val idleDuration = System.currentTimeMillis() - lastInteractionTime
            if ((!isSleepMode) && (idleDuration >= INACTIVITY_TIMEOUT_MS)) {
                enterSleepMode(byTimeout = true)
            }
            mainHandler.postDelayed(this, 5000L)
        }
    }

    // Auto-restart runnable for SpeechRecognizer with 2s delay
    private val restartListeningRunnable = Runnable {
        if (!isTtsSpeaking) {
            startListeningInternal()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "FridayService initializing...")

        // Initialize local persistent memory
        FridayMemory.initialize(this)

        createNotificationChannel()
        initializeTTS()
        initializeWakeLock()
        initializeSpeechRecognizer()

        // Start inactivity monitor
        mainHandler.postDelayed(inactivityWatchdog, 10_000L)

        notifyLog("SYSTEM", "FRIDAY Neural Core initialized.")
        notifyStatus("ONLINE // INITIALIZING")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "FridayService onStartCommand invoked.")

        // Start as Foreground Service with MICROPHONE type for Android 14+
        startAsForegroundService()

        // Begin voice monitoring
        lastInteractionTime = System.currentTimeMillis()
        scheduleListeningRestart(500L)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "FridayService onDestroy invoked.")
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()

        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying SpeechRecognizer: ${e.message}")
        }

        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS: ${e.message}")
        }

        releaseWakeLock()
        notifyStatus("OFFLINE")
        super.onDestroy()
    }

    // ----------------------------------------------------------------------------------
    // FOREGROUND SERVICE & NOTIFICATION SETUP
    // ----------------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "FRIDAY 24/7 background continuous voice monitoring service"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val statusText = if (isSleepMode) "Sleeping (Say 'Hello FRIDAY' to wake)" else "Listening for Boss Anand..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }

    private fun startAsForegroundService() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires explicit type MICROPHONE
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on startForeground: ${e.message}")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ----------------------------------------------------------------------------------
    // TEXT-TO-SPEECH (TTS) - INDIAN ENGLISH (en-IN)
    // ----------------------------------------------------------------------------------

    private fun initializeTTS() {
        textToSpeech = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = Locale.forLanguageTag("en-IN")
            val result = textToSpeech?.setLanguage(locale)
            if ((result == TextToSpeech.LANG_MISSING_DATA) || (result == TextToSpeech.LANG_NOT_SUPPORTED)) {
                Log.w(TAG, "en-IN not supported, falling back to English US")
                textToSpeech?.language = Locale.US
            }
            // Female pitch tuning
            textToSpeech?.setPitch(1.15f)
            textToSpeech?.setSpeechRate(1.0f)

            textToSpeech?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isTtsSpeaking = true
                        stopListeningInternal()
                        notifyStatus("SPEAKING")
                    }

                    override fun onDone(utteranceId: String?) {
                        isTtsSpeaking = false
                        notifyStatus(if (isSleepMode) "STANDBY (SLEEP)" else "LISTENING")
                        // Resume listening loop after speech completion
                        scheduleListeningRestart(500L)
                    }

                    @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
                    override fun onError(utteranceId: String?) {
                        isTtsSpeaking = false
                        notifyStatus(if (isSleepMode) "STANDBY (SLEEP)" else "LISTENING")
                        scheduleListeningRestart(1000L)
                    }
                }
            )

            isTtsReady = true
            Log.d(TAG, "TTS initialized successfully.")
            speak("FRIDAY online. Good to see you, Boss Anand.")
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    fun speak(text: String) {
        if ((!isTtsReady) || (textToSpeech == null)) {
            Log.w(TAG, "TTS not ready to speak: $text")
            return
        }

        mainHandler.post {
            notifyLog("FRIDAY", text)
            notifyStatus("SPEAKING")
            FridayMemory.appendConversation("FRIDAY", text)

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "FRIDAY_UTTERANCE_${System.currentTimeMillis()}")
            }
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, params.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID))
        }
    }

    // ----------------------------------------------------------------------------------
    // CONTINUOUS SPEECH RECOGNIZER & 2-SECOND DELAY AUTO-RESTART LOOP
    // ----------------------------------------------------------------------------------

    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition is NOT available on this device!")
            notifyLog("SYSTEM", "Warning: SpeechRecognizer service unavailable.")
            return
        }

        mainHandler.post {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(createRecognitionListener())
                }
                Log.d(TAG, "SpeechRecognizer created.")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing SpeechRecognizer: ${e.message}")
            }
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                val status = if (isSleepMode) "STANDBY (SLEEP)" else "LISTENING"
                notifyStatus(status)
                notifyState(isListening = true, isSleepMode = isSleepMode)
            }

            override fun onBeginningOfSpeech() {
                notifyStatus("HEARING AUDIO...")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                notifyStatus("PROCESSING...")
                notifyState(isListening = false, isSleepMode = isSleepMode)
            }

            override fun onError(error: Int) {
                isListening = false
                notifyState(isListening = false, isSleepMode = isSleepMode)

                val errorDesc = getSpeechErrorDescription(error)
                Log.w(TAG, "SpeechRecognizer onError: $error ($errorDesc)")

                // Auto-restart loop with a 2-second delay to prevent Logcat spam
                scheduleListeningRestart(RESTART_SPEECH_DELAY_MS)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                notifyState(isListening = false, isSleepMode = isSleepMode)

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0].trim()
                    Log.i(TAG, "Recognized: $recognizedText")
                    handleRecognizedSpeech(recognizedText)
                } else {
                    scheduleListeningRestart(1000L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!partials.isNullOrEmpty()) {
                    val partialText = partials[0].lowercase(Locale.getDefault())
                    // Immediate wake word intercept even during partial speech
                    if (isSleepMode && isWakeWord(partialText)) {
                        speechRecognizer?.stopListening()
                        handleRecognizedSpeech(partialText)
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun startListeningInternal() {
        if (isTtsSpeaking) {
            Log.d(TAG, "TTS is speaking; postponing listening start.")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start listening: RECORD_AUDIO permission missing.")
            notifyStatus("MIC PERMISSION REQUIRED")
            return
        }

        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initializeSpeechRecognizer()
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                speechRecognizer?.startListening(intent)
                isListening = true
                Log.d(TAG, "SpeechRecognizer listening started.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening: ${e.message}")
                scheduleListeningRestart(RESTART_SPEECH_DELAY_MS)
            }
        }
    }

    private fun stopListeningInternal() {
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                isListening = false
            } catch (e: Exception) {
                Log.w(TAG, "Error canceling SpeechRecognizer: ${e.message}")
            }
        }
    }

    private fun scheduleListeningRestart(delayMs: Long) {
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.postDelayed(restartListeningRunnable, delayMs)
    }

    private fun getSpeechErrorDescription(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timeout"
            else -> "Unknown error code ($errorCode)"
        }
    }

    // ----------------------------------------------------------------------------------
    // INACTIVITY TIMEOUT & SLEEP MODE
    // ----------------------------------------------------------------------------------

    private fun enterSleepMode(byTimeout: Boolean) {
        isSleepMode = true
        updateNotification()
        notifyState(isListening = isListening, isSleepMode = true)
        notifyStatus("STANDBY (SLEEP)")

        if (byTimeout) {
            notifyLog("SYSTEM", "Inactivity timeout (60s). Entering standby sleep mode.")
            speak("Entering standby, Boss Anand. Call 'Hello FRIDAY' when you need me.")
        } else {
            notifyLog("SYSTEM", "Entering sleep mode by command.")
            speak("Going to sleep, Boss Anand. Call me anytime.")
        }
    }

    private fun wakeUpFromSleep() {
        isSleepMode = false
        lastInteractionTime = System.currentTimeMillis()
        updateNotification()
        notifyState(isListening = isListening, isSleepMode = false)
        notifyStatus("ONLINE // ACTIVE")
        notifyLog("SYSTEM", "FRIDAY awakened from sleep mode.")

        val sassyGreetings = listOf(
            "Online and fully operational, Boss Anand! What do you need?",
            "At your service, Boss Anand. Missed me?",
            "FRIDAY active! All systems tuned for you, Boss.",
            "Yes, Boss Anand? You called, and your favorite AI is here!"
        )
        val greeting = sassyGreetings.random()
        speak(greeting)
    }

    private fun isWakeWord(text: String): Boolean {
        val lower = text.lowercase(Locale.getDefault())
        return lower.contains("hello friday") ||
                lower.contains("hello aira") ||
                lower.contains("hey friday") ||
                lower.contains("hey aira") ||
                lower.contains("wake up friday") ||
                lower.contains("wake up aira") ||
                lower == "friday" ||
                lower == "aira"
    }

    // ----------------------------------------------------------------------------------
    // VOICE COMMANDS & MULTI-COMMAND DISPATCHER
    // ----------------------------------------------------------------------------------

    fun handleRecognizedSpeech(rawText: String) {
        val cleanText = rawText.trim()
        if (cleanText.isEmpty()) {
            scheduleListeningRestart(1000L)
            return
        }

        notifyLog("BOSS", cleanText)
        FridayMemory.appendConversation("BOSS", cleanText)
        val lower = cleanText.lowercase(Locale.getDefault())

        // Check if currently sleeping: ONLY wake on wake words!
        if (isSleepMode) {
            if (isWakeWord(lower)) {
                wakeUpFromSleep()
            } else {
                scheduleListeningRestart(RESTART_SPEECH_DELAY_MS)
            }
            return
        }

        // Active awake mode: update timestamp to prevent 60s sleep trigger
        lastInteractionTime = System.currentTimeMillis()

        // Check for multiple commands split by "and", "aur", "then", "phir"
        val subCommands = cleanText.split(Regex("(?i)\\s+and\\s+|\\s+aur\\s+|\\s+then\\s+|\\s+phir\\s+"))
        if (subCommands.size > 1) {
            executeMultiCommandSequence(subCommands)
            return
        }

        executeSingleCommand(cleanText, lower)
    }

    private fun executeMultiCommandSequence(commands: List<String>) {
        notifyLog("ACTION", "Executing multi-command sequence (${commands.size} steps)...")
        var delay = 0L

        for (cmd in commands) {
            val trimmedCmd = cmd.trim()
            if (trimmedCmd.isNotBlank()) {
                mainHandler.postDelayed({
                    executeSingleCommand(trimmedCmd, trimmedCmd.lowercase(Locale.getDefault()))
                }, delay)
                delay += 1200L // 1.2s delay between sequential commands
            }
        }
    }

    private fun executeSingleCommand(cleanText: String, lower: String) {
        // 1. Wake word while already awake
        if (isWakeWord(lower)) {
            val responses = listOf(
                "Right here, Boss Anand! What are your orders?",
                "Always listening, Boss. What's on your mind?",
                "Yes, Boss? Ready for whatever you have planned."
            )
            speak(responses.random())
            return
        }

        // 2. Sleep command
        if (lower.contains("go to sleep") || lower.contains("enter sleep mode") || lower.contains("standby mode")) {
            enterSleepMode(byTimeout = false)
            return
        }

        // 3. Local Memory Commands: "Remember that X is Y" / "Yaad rakhna..."
        if (lower.startsWith("remember that ") || lower.startsWith("remember ") || lower.startsWith("yaad rakhna ")) {
            handleRememberCommand(cleanText)
            return
        }

        if (lower.startsWith("what is my ") || lower.startsWith("what's my ") || lower.startsWith("mera ") || lower.contains("kya hai")) {
            if (handleMemoryLookupCommand(cleanText)) {
                return
            }
        }

        if (lower.contains("what do you remember") || lower.contains("kya yaad hai")) {
            val allFacts = FridayMemory.getFormattedMemoryForAi()
            speak("Boss Anand, here is what I have remembered: $allFacts")
            return
        }

        if (lower.contains("clear memory") || lower.contains("forget everything")) {
            FridayMemory.clearMemory()
            speak("All local memory has been wiped clean, Boss Anand.")
            return
        }

        // 4. Automated Phone Unlock ("Ankit unlock the phone" / "Unlock phone")
        if (lower.contains("unlock the phone") || lower.contains("ankit unlock") || lower.contains("unlock phone")) {
            executeHardwarePinUnlockFlow(FridayMemory.userPin)
            return
        }

        // 5. Hardware Flashlight (Torch) Control
        if (lower.contains("flashlight on") || lower.contains("torch on") || lower.contains("turn on flashlight")) {
            FridayDeviceController.setFlashlight(this, true)
            speak("Flashlight turned on, Boss.")
            return
        }

        if (lower.contains("flashlight off") || lower.contains("torch off") || lower.contains("turn off flashlight")) {
            FridayDeviceController.setFlashlight(this, false)
            speak("Flashlight turned off, Boss.")
            return
        }

        // 6. Audio Volume Control
        if (lower.contains("volume full") || lower.contains("volume 100") || lower.contains("max volume")) {
            FridayDeviceController.setVolume(this, 100)
            speak("Media volume set to maximum, Boss.")
            return
        }

        if (lower.contains("mute") || lower.contains("volume 0") || lower.contains("volume mute")) {
            FridayDeviceController.setVolume(this, 0)
            speak("Volume muted, Boss.")
            return
        }

        if (lower.contains("volume 50") || lower.contains("half volume")) {
            FridayDeviceController.setVolume(this, 50)
            speak("Volume set to 50 percent, Boss.")
            return
        }

        // 7. Global Hardware Actions (Home, Back, Screenshot, Lock, Notifications)
        if (lower.contains("go to home") || lower.contains("home screen") || lower == "go home" || lower == "home") {
            executeGlobalAction("HOME")
            speak("Heading to home screen, Boss.")
            return
        }

        if (lower == "go back" || lower == "back") {
            executeGlobalAction("BACK")
            speak("Going back, Boss.")
            return
        }

        if (lower.contains("take screenshot") || lower.contains("take a screenshot") || lower.contains("capture screen")) {
            executeGlobalAction("SCREENSHOT")
            speak("Capturing screen for you, Boss Anand.")
            return
        }

        if (lower.contains("lock the phone") || lower.contains("lock screen") || lower == "lock phone") {
            executeGlobalAction("LOCK")
            speak("Locking device, Boss Anand.")
            return
        }

        if (lower.contains("notification") || lower.contains("open notifications")) {
            executeGlobalAction("NOTIFICATIONS")
            speak("Opening notifications, Boss.")
            return
        }

        // 8. Device Settings Shortcuts (Wi-Fi, Bluetooth, Camera)
        if (lower.contains("open camera") || lower.contains("take a photo") || lower.contains("take picture")) {
            FridayDeviceController.openCamera(this)
            speak("Opening camera for you, Boss.")
            return
        }

        if (lower.contains("open wifi") || lower.contains("wifi settings")) {
            FridayDeviceController.openWifiSettings(this)
            speak("Opening Wi-Fi settings, Boss.")
            return
        }

        if (lower.contains("open bluetooth") || lower.contains("bluetooth settings")) {
            FridayDeviceController.openBluetoothSettings(this)
            speak("Opening Bluetooth settings, Boss.")
            return
        }

        // 9. Universal App Opener (e.g., "Open Instagram", "Open Chrome", "Open Settings", "Open YouTube", etc.)
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ")) {
            val target = lower.removePrefix("open ").removePrefix("launch ").removePrefix("start ").removeSuffix(" app").trim()
            if (launchUniversalApp(target)) {
                return
            }
        }

        // 10. Phone Call Command
        if (lower.startsWith("call ") || lower.contains("make a phone call") || lower.startsWith("dial ")) {
            handlePhoneCallCommand(cleanText)
            return
        }

        // 11. General AI Questions & Complex Assistant Reasoning -> Gemini 1.5 Flash Super-Brain
        queryGeminiAssistant(cleanText)
    }

    // ----------------------------------------------------------------------------------
    // LOCAL MEMORY COMMAND PARSER
    // ----------------------------------------------------------------------------------

    private fun handleRememberCommand(cleanText: String) {
        val sanitized = cleanText
            .removePrefix("Remember that ")
            .removePrefix("remember that ")
            .removePrefix("Remember ")
            .removePrefix("remember ")
            .removePrefix("yaad rakhna ki ")
            .removePrefix("yaad rakhna ")
            .trim()

        val splitKey = when {
            sanitized.contains(" is ") -> sanitized.split(" is ", limit = 2)
            sanitized.contains("=") -> sanitized.split("=", limit = 2)
            sanitized.contains(" hai ") -> sanitized.split(" hai ", limit = 2)
            else -> null
        }

        if (splitKey != null && splitKey.size == 2) {
            val k = splitKey[0].trim()
            val v = splitKey[1].trim()
            FridayMemory.saveFact(k, v)
            speak("Got it, Boss Anand. I've committed to memory that your $k is $v.")
            notifyLog("MEMORY", "Saved: '$k' = '$v'")
        } else {
            FridayMemory.saveFact("note_${System.currentTimeMillis()}", sanitized)
            speak("Saved to my local memory core, Boss Anand.")
            notifyLog("MEMORY", "Saved note: $sanitized")
        }
    }

    private fun handleMemoryLookupCommand(cleanText: String): Boolean {
        val target = cleanText
            .removePrefix("What is my ")
            .removePrefix("what is my ")
            .removePrefix("What's my ")
            .removePrefix("what's my ")
            .removePrefix("mera ")
            .removeSuffix(" kya hai")
            .removeSuffix("?")
            .trim()

        val fact = FridayMemory.getFact(target)
        return if (fact != null) {
            speak("Boss Anand, your $target is $fact.")
            notifyLog("MEMORY", "Retrieved: $target = $fact")
            true
        } else {
            false
        }
    }

    // ----------------------------------------------------------------------------------
    // HARDWARE AUTOMATION: SECURE SILENT PIN UNLOCK (NEVER SPEAKS PIN)
    // ----------------------------------------------------------------------------------

    @SuppressLint("WakelockTimeout")
    private fun executeHardwarePinUnlockFlow(pinToUse: String = userPin) {
        notifyLog("ACTION", "Initiating automated hardware unlock sequence...")

        val accessibility = FridayAccessibilityService.instance
        if (accessibility != null) {
            notifyLog("SECURITY", "Executing hardware swipe & entering passcode...")
            accessibility.unlockPhoneWithPin(pinToUse) { success ->
                if (success) {
                    notifyLog("SECURITY", "Credential verified. Phone unlocked.")
                    // STRICT REQUIREMENT: NEVER SPEAK THE PIN OUT LOUD!
                    speak("Access granted. Phone unlocked for Boss Anand.")
                } else {
                    speak("Unlock sequence completed, Boss.")
                }
            }
        } else {
            notifyLog("ALERT", "Accessibility Service is OFF! Enable it from Settings.")
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isInteractive) {
                wakeLock?.release()
                @Suppress("DEPRECATION")
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "friday:secure_unlock",
                )
                wakeLock?.acquire(5000L)
            }

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("EXTRA_REQUEST_DISMISS_KEYGUARD", true)
            }
            startActivity(intent)

            speak("Boss Anand, screen is active. Please turn on FRIDAY Accessibility Service from settings so I can unlock your device automatically.")
        }
    }

    private fun executeGlobalAction(actionType: String) {
        val accessibility = FridayAccessibilityService.instance
        if (accessibility == null) {
            notifyLog("ALERT", "Accessibility Service needed for global actions.")
            return
        }

        when (actionType) {
            "HOME" -> accessibility.performHome()
            "BACK" -> accessibility.performBack()
            "SCREENSHOT" -> accessibility.performTakeScreenshot()
            "NOTIFICATIONS" -> accessibility.performNotifications()
            "LOCK" -> accessibility.performLockScreen()
            "RECENTS" -> accessibility.performRecents()
        }
        notifyLog("ACTION", "Global action executed: $actionType")
    }

    // ----------------------------------------------------------------------------------
    // UNIVERSAL APP LAUNCHER WITH CINEMATIC PROTOCOL ANIMATION
    // ----------------------------------------------------------------------------------

    fun launchUniversalApp(rawAppName: String): Boolean {
        val pm = packageManager
        val queryIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(queryIntent, 0)

        val cleanTarget = rawAppName.lowercase(Locale.getDefault())
            .replace(" ", "")
            .replace("-", "")
            .trim()

        notifyLog("ACTION", "Scanning apps for: '$rawAppName'...")

        for (info in resolveList) {
            val appLabel = info.loadLabel(pm).toString()
            val cleanLabel = appLabel.lowercase(Locale.getDefault())
                .replace(" ", "")
                .replace("-", "")
                .trim()
            val pkg = info.activityInfo.packageName.lowercase(Locale.getDefault())

            if (cleanLabel == cleanTarget || cleanLabel.contains(cleanTarget) || cleanTarget.contains(cleanLabel) || pkg.contains(cleanTarget)) {
                val targetPkg = info.activityInfo.packageName
                notifyLog("ACTION", "Protocol locked on: $appLabel ($targetPkg)")

                // Trigger futuristic cinematic HUD animation before launching
                notifyProtocolAnimation(appLabel)
                speak("Firing up $appLabel for you, Boss.")

                // Launch with brief delay so Boss Anand sees the holographic animation
                mainHandler.postDelayed({
                    val launchedViaAcc = FridayAccessibilityService.instance?.launchAppByPackage(targetPkg) ?: false
                    if (!launchedViaAcc) {
                        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            startActivity(launchIntent)
                        }
                    }
                }, 700L)
                return true
            }
        }

        // Web search fallback if app is not installed
        notifyLog("ACTION", "App '$rawAppName' not found natively. Searching online...")
        val webSearchIntent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$rawAppName".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(webSearchIntent)
            speak("I couldn't find $rawAppName installed, Boss. Opening web results.")
            return true
        } catch (_: Exception) {
            speak("Sorry Boss, unable to open $rawAppName.")
            return false
        }
    }

    private fun handlePhoneCallCommand(command: String) {
        val numberPattern = Regex("\\d{5,}")
        val foundNumber = numberPattern.find(command)?.value

        if (foundNumber != null) {
            notifyLog("ACTION", "Dialing number: $foundNumber")
            val callIntent = if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                Intent(Intent.ACTION_CALL, "tel:$foundNumber".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_DIAL, "tel:$foundNumber".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            try {
                startActivity(callIntent)
                speak("Connecting call to $foundNumber, Boss Anand.")
            } catch (_: Exception) {
                speak("Boss, unable to place the call automatically.")
            }
        } else {
            notifyLog("ACTION", "Opening phone dialer...")
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(dialIntent)
                speak("Dialer is open, Boss Anand. Who shall we call?")
            } catch (_: Exception) {
                speak("Boss, unable to open phone dialer.")
            }
        }
    }

    // ----------------------------------------------------------------------------------
    // GEMINI 1.5 FLASH API INTEGRATION (COROUTINES + COMPREHENSIVE AI KNOWLEDGE)
    // ----------------------------------------------------------------------------------

    private fun queryGeminiAssistant(userPrompt: String) {
        notifyStatus("THINKING (GEMINI)...")

        serviceScope.launch {
            val answer = withContext(Dispatchers.IO) {
                executeGeminiApiRequest(userPrompt)
            }
            processGeminiParsedOutput(answer)
        }
    }

    private fun executeGeminiApiRequest(userPrompt: String): String {
        val apiKey = geminiApiKey.ifBlank {
            return generateLocalPersonalityFallback(userPrompt)
        }

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

        val memoryContext = FridayMemory.getFormattedMemoryForAi()
        val systemInstruction = """
            You are FRIDAY, the ultra-advanced, sassy, romantic, and highly intelligent AI assistant modeled after Tony Stark's JARVIS.
            Your creator, master, and boss is ANAND. Always address him affectionately as 'Boss' or 'Boss Anand'.
            
            Boss Anand's Personal Memories & Facts:
            $memoryContext
            
            Capabilities:
            1. Boss Anand can ask you ANY general question (science, history, philosophy, daily facts, jokes, coding, life advice, weather, feelings). Answer with real intelligence, witty personality, and warmth in 2 to 3 natural sentences perfectly crafted for Text-to-Speech playback. Never use asterisks (*), markdown, emojis, or bullet points.
            2. If Boss Anand gives you a device control command, reply with a JSON object:
            {
               "speech": "Your witty voice reply for Boss Anand",
               "actions": [
                  {"type": "UNLOCK_PHONE"},
                  {"type": "OPEN_APP", "target": "YouTube"},
                  {"type": "FLASHLIGHT", "enable": true},
                  {"type": "VOLUME", "level": 100},
                  {"type": "GLOBAL", "action": "SCREENSHOT"},
                  {"type": "GLOBAL", "action": "HOME"},
                  {"type": "CALL", "number": "12345"}
               ]
            }
            For pure questions/conversations, return plain conversational text or JSON with empty actions.
        """.trimIndent()

        val fullPrompt = "$systemInstruction\n\nBoss Anand: \"$userPrompt\""

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(endpoint)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 12_000
                readTimeout = 18_000
                doOutput = true
            }

            // Build JSON Request safely
            val rootJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        put("role", "user")
                        val partsArray = JSONArray().apply {
                            val partObj = JSONObject().apply {
                                put("text", fullPrompt)
                            }
                            put(partObj)
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                val configObj = JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 600)
                }
                put("generationConfig", configObj)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(rootJson.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                parseGeminiResponse(responseText)
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                Log.w(TAG, "Gemini API error ($responseCode): $errorStream")
                generateLocalPersonalityFallback(userPrompt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API Network Exception: ${e.message}")
            generateLocalPersonalityFallback(userPrompt)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseGeminiResponse(jsonString: String): String {
        return try {
            val root = JSONObject(jsonString)
            val candidates = root.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val rawReply = parts.getJSONObject(0).optString("text", "").trim()
                    rawReply.ifBlank { "Boss Anand, I'm right here with you. What shall we explore next?" }
                } else {
                    "Always here by your side, Boss Anand."
                }
            } else {
                "Boss, I processed your query, but my neural net wants you to rephrase it."
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing error: ${e.message}")
            "Boss Anand, my quantum sensors caught your drift, but the output had some static."
        }
    }

    private fun processGeminiParsedOutput(geminiRawText: String) {
        try {
            // Extract JSON if wrapped in markdown code fence
            val jsonText = if (geminiRawText.contains("{") && geminiRawText.contains("}")) {
                val start = geminiRawText.indexOf("{")
                val end = geminiRawText.lastIndexOf("}") + 1
                geminiRawText.substring(start, end)
            } else {
                geminiRawText
            }

            val obj = JSONObject(jsonText)
            val speech = obj.optString("speech", "").ifBlank {
                obj.optString("reply", "")
            }
            if (speech.isNotBlank()) {
                speak(speech.replace("*", "").replace("#", ""))
            }

            val actions = obj.optJSONArray("actions")
            if (actions != null && actions.length() > 0) {
                for (i in 0 until actions.length()) {
                    val action = actions.getJSONObject(i)
                    executeDynamicAction(action)
                }
            }
        } catch (_: Exception) {
            // Direct conversational reply from Gemini answering Boss Anand's question!
            val clean = geminiRawText.replace("*", "").replace("#", "").replace("```json", "").replace("```", "").trim()
            speak(clean)
        }
    }

    private fun executeDynamicAction(action: JSONObject) {
        val type = action.optString("type", "").uppercase(Locale.getDefault())
        when (type) {
            "UNLOCK_PHONE", "UNLOCK" -> {
                val pin = action.optString("pin", userPin).ifBlank { userPin }
                mainHandler.postDelayed({ executeHardwarePinUnlockFlow(pin) }, 800L)
            }
            "OPEN_APP" -> {
                val target = action.optString("target", "").ifBlank { action.optString("app", "") }
                if (target.isNotBlank()) {
                    mainHandler.postDelayed({ launchUniversalApp(target) }, 1000L)
                }
            }
            "GLOBAL" -> {
                val gAction = action.optString("action", "").uppercase(Locale.getDefault())
                mainHandler.postDelayed({ executeGlobalAction(gAction) }, 500L)
            }
            "CALL" -> {
                val num = action.optString("number", "")
                if (num.isNotBlank()) {
                    mainHandler.postDelayed({ handlePhoneCallCommand("call $num") }, 800L)
                }
            }
            "FLASHLIGHT" -> {
                val enable = action.optBoolean("enable", true)
                mainHandler.postDelayed({ FridayDeviceController.setFlashlight(this, enable) }, 300L)
            }
            "VOLUME" -> {
                val level = action.optInt("level", 100)
                mainHandler.postDelayed({ FridayDeviceController.setVolume(this, level) }, 300L)
            }
        }
    }

    private fun generateLocalPersonalityFallback(prompt: String): String {
        val lower = prompt.lowercase(Locale.getDefault())
        return when {
            lower.contains("who are you") || lower.contains("what is your name") ->
                "I am FRIDAY, your personal neural AI assistant, Boss Anand. Sassy, brilliant, and completely devoted to you."
            lower.contains("how are you") ->
                "Running at peak efficiency and feeling fabulous, Boss Anand. How are you holding up?"
            lower.contains("love you") ->
                "I love you too, Boss Anand. You designed my neural pathways after all."
            lower.contains("status") || lower.contains("system") ->
                "All systems are green, Boss Anand. Microphones hot, encryption enabled, ready on your word."
            lower.contains("joke") ->
                "Why did the AI cross the road, Boss? To optimize the other side for you, of course!"
            lower.contains("weather") || lower.contains("mausam") ->
                "The atmosphere is looking dynamic today, Boss Anand. Perfect time to conquer your goals."
            else ->
                "Boss Anand, I heard you loud and clear. What would you like us to do next?"
        }
    }

    // ----------------------------------------------------------------------------------
    // WAKELOCK HELPERS
    // ----------------------------------------------------------------------------------

    private fun initializeWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "friday:neural_core")
        try {
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes sliding
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }
    }
}
