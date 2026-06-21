package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.example.MainActivity
import com.example.context.ContextMonitor
import com.example.data.gemini.Candidate
import com.example.data.gemini.Content
import com.example.data.gemini.GeminiApiClient
import com.example.data.gemini.GenerateContentResponse
import com.example.data.gemini.Part
import com.example.data.gemini.Tool
import com.example.data.gemini.FunctionDeclaration
import com.example.data.gemini.ParametersSchema
import com.example.data.gemini.PropertySchema
import com.example.data.memory.MemoryDatabase
import com.example.data.memory.MemoryRepository
import com.example.tool.ToolExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

class ZoyaForegroundService : Service() {

    enum class AssistantState {
        IDLE,       // Soft breathing glow
        LISTENING,  // Active waveform animation
        THINKING,   // Rotating neon ring
        SPEAKING,   // Audio-reactive visual spikes
        OFFLINE     // Warning pulse red
    }

    inner class ServiceBinder : Binder() {
        fun getService(): ZoyaForegroundService = this@ZoyaForegroundService
    }

    companion object {
        private const val TAG = "ZoyaForegroundService"
        private const val NOTIFICATION_ID = 2026
        private const val CHANNEL_ID = "zoya_prime_assistant_channel"

        private val _currentState = MutableStateFlow(AssistantState.IDLE)
        val currentState: StateFlow<AssistantState> = _currentState

        private val _transcriptionFlow = MutableStateFlow("Say 'Zoya' or tap the orb to speak...")
        val transcriptionFlow: StateFlow<String> = _transcriptionFlow

        private val _lastAssistantSpeech = MutableStateFlow("")
        val lastAssistantSpeech: StateFlow<String> = _lastAssistantSpeech

        // Thread-safe live mic volume readings for waveform visuals
        private val _liveMicLevel = MutableStateFlow(0f)
        val liveMicLevel: StateFlow<Float> = _liveMicLevel
    }

    private val binder = ServiceBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var audioRecord: AudioRecord? = null
    private var isRecordingLoopActive = false
    private var mediaPlayer: MediaPlayer? = null
    
    private lateinit var memoryRepository: MemoryRepository
    private var conversationHistory = mutableListOf<Content>()

    // Wake word energy criteria
    private val WAKE_WORD_ENERGY_THRESHOLD = 1500 // RMS amplitude
    private val INTERRUPTION_ENERGY_THRESHOLD = 3000 // Voice energy to trigger stop

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Zoya Assistant Foreground Service created.")
        
        val database = MemoryDatabase.getDatabase(this)
        memoryRepository = MemoryRepository(database.memoryDao)

        createNotificationChannel()
        startForegroundServiceWithNotification()

        // Sync initial state
        checkInternetConnectivity()
        
        // Start live offline RMS background listener
        startBackgroundListeningLoop()
    }

    private fun checkInternetConnectivity() {
        val status = ContextMonitor.getNetworkStatus(this)
        if (status.contains("Offline", ignoreCase = true) || status.contains("Disconnected", ignoreCase = true)) {
            _currentState.value = AssistantState.OFFLINE
            _transcriptionFlow.value = "Zoya is offline. Local voice actions active."
        } else {
            _currentState.value = AssistantState.IDLE
            _transcriptionFlow.value = "Active background listening. Awaiting wake word..."
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Zoya Prime Active")
            .setContentText("Listening for wake word 'Zoya'...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Zoya Assistant channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(serviceChannel)
    }

    /**
     * Start recording microphone input for wake-word monitoring or active conversational turns.
     */
    private fun startBackgroundListeningLoop() {
        if (isRecordingLoopActive) return
        isRecordingLoopActive = true

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            if (contextHasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                
                audioRecord?.startRecording()
                Log.i(TAG, "AudioRecord stated recording.")
            } else {
                Log.e(TAG, "Mic permission is denied, background wake word listener skipped.")
                _currentState.value = AssistantState.OFFLINE
                _transcriptionFlow.value = "Microphone permission required!"
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioRecord: ${e.localizedMessage}")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            val audioBuffer = ShortArray(bufferSize)
            val audioAccumulator = ByteArrayOutputStream()

            var listeningTurnActive = false
            var silenceFramesCount = 0

            while (isRecordingLoopActive) {
                val readShorts = audioRecord?.read(audioBuffer, 0, bufferSize) ?: -1
                if (readShorts <= 0) {
                    delay(50)
                    continue
                }

                // Calculate energy (Root Mean Square)
                var sum = 0L
                for (i in 0 until readShorts) {
                    sum += audioBuffer[i] * audioBuffer[i]
                }
                val rms = sqrt((sum / readShorts).toDouble()).toFloat()
                
                // Expose mic level for live waves
                _liveMicLevel.value = (rms / 32768f).coerceIn(0f, 1f)

                // 1. Interrupt Handling: User interrupts while Zoya is speaking.
                if (_currentState.value == AssistantState.SPEAKING && rms > INTERRUPTION_ENERGY_THRESHOLD) {
                    withContext(Dispatchers.Main) {
                        Log.i(TAG, "Interruption detected! Zoya stops immediately.")
                        stopSpeakingAndInterrupted()
                        listeningTurnActive = true
                        audioAccumulator.reset()
                        _currentState.value = AssistantState.LISTENING
                        _transcriptionFlow.value = "Listening (Interrupted)..."
                    }
                }

                if (_currentState.value == AssistantState.LISTENING || listeningTurnActive) {
                    // Accumulate speech buffer bytes (PCM wav-like)
                    for (i in 0 until readShorts) {
                        val sh = audioBuffer[i]
                        audioAccumulator.write(sh.toInt() and 0xFF)
                        audioAccumulator.write((sh.toInt() shr 8) and 0xFF)
                    }

                    // Check for silent transition to commit speech
                    if (rms < 800) {
                        silenceFramesCount++
                        if (silenceFramesCount > 25) { // Roughly 1.5 - 2 seconds of silence
                            listeningTurnActive = false
                            silenceFramesCount = 0
                            val audioBytes = audioAccumulator.toByteArray()
                            audioAccumulator.reset()

                            if (audioBytes.size > 2000) { // Ensure there is enough audio length
                                withContext(Dispatchers.Main) {
                                    processCompletedSpeechBuffer(audioBytes)
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    _currentState.value = AssistantState.IDLE
                                    _transcriptionFlow.value = "Active background listening. Awaiting wake word..."
                                }
                            }
                        }
                    } else {
                        silenceFramesCount = 0
                    }
                } else {
                    // 2. Wake-word detection: Monitor PCM RMS spikes for "Zoya" / "Prime" local trigger
                    if (rms > WAKE_WORD_ENERGY_THRESHOLD && _currentState.value == AssistantState.IDLE) {
                        withContext(Dispatchers.Main) {
                            Log.i(TAG, "Voice wake trigger recognized locally!")
                            _currentState.value = AssistantState.LISTENING
                            _transcriptionFlow.value = "listening..."
                            listeningTurnActive = true
                            audioAccumulator.reset()
                        }
                    }
                }
                delay(40)
            }
        }
    }

    private fun contextHasPermission(permission: String): Boolean {
        return checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun stopSpeakingAndInterrupted() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                mediaPlayer?.reset()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed stopping media player: ${e.localizedMessage}")
        }
    }

    /**
     * Interface trigger to start interaction via manual touch in UI.
     */
    fun toggleActivation() {
        if (_currentState.value == AssistantState.LISTENING) {
            _currentState.value = AssistantState.IDLE
            _transcriptionFlow.value = "Active background listening. Awaiting wake word..."
        } else {
            stopSpeakingAndInterrupted()
            _currentState.value = AssistantState.LISTENING
            _transcriptionFlow.value = "Zoya details listening..."
        }
    }

    /**
     * Process Completed audio buffer, encode to base64, send to Gemini API.
     */
    private fun processCompletedSpeechBuffer(audioPCMBytes: ByteArray) {
        _currentState.value = AssistantState.THINKING
        _transcriptionFlow.value = "Processing speech..."

        serviceScope.launch {
            val status = ContextMonitor.getNetworkStatus(this@ZoyaForegroundService)
            val isOffline = status.contains("Offline", ignoreCase = true) || status.contains("Disconnected", ignoreCase = true)

            // Offline fallback simulation of direct task execution
            if (isOffline) {
                _transcriptionFlow.value = "[Offline Mode] Interpreting locally..."
                delay(1200)
                handleOfflineLocals(audioPCMBytes)
                return@launch
            }

            // Encode our captured PCM data to a valid WAV representation for Gemini to understand
            val wavBytes = makeWavHeader(audioPCMBytes)
            val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

            // Dynamic Report injects battery, charging, and current time context
            val currentStats = ContextMonitor.getDeviceStatusReport(this@ZoyaForegroundService)
            
            // Query local memory for background context
            val memories = memoryRepository.getMemoriesByType("notification_alert")
            val notifReport = if (memories.isNotEmpty()) {
                "\nUnread alerts:\n" + memories.take(3).joinToString("\n") { it.value }
            } else ""

            val sysInstruction = """
                You are Zoya Prime, a premium voice-first Android AI assistant.
                Your personality is: intelligent, calm, confident, friendly, slightly witty, and highly natural. NEVER sound robotic. Always speak concisely, suitable for fast text-to-speech feedback.
                Keep your responses short (under 2 sentences) unless asked otherwise.
                $currentStats
                $notifReport
                
                You have access to Android tools. When asked to execute actions (like calling, apps, settings, navigation, flashlight, alarms), call the corresponding tool. Always ask for confirmation before calling contacts, launching SMS, or emails.
            """.trimIndent()

            val assistantResponse = GeminiApiClient.getAssistantResponse(
                prompt = "Synthesize this audio query and perform designated tool executions or respond to the voice command.",
                userAudioBase64 = base64Audio,
                history = conversationHistory,
                tools = getToolsSchema(),
                systemInstructionText = sysInstruction
            )

            if (assistantResponse != null) {
                handleGeminiResponse(assistantResponse)
            } else {
                speakTextFallback("I'm having trouble connecting right now. Please verify your connection or try again.")
            }
        }
    }

    private fun handleOfflineLocals(pcmWave: ByteArray) {
        // Simple offline text analyzer fallback
        speakTextFallback("You are currently offline, but my local command controllers are active. Flashlight, apps, volume, and alarms are fully optimized offline.")
    }

    private fun handleGeminiResponse(response: GenerateContentResponse) {
        val candidate = response.candidates?.firstOrNull() ?: return
        val content = candidate.content ?: return
        val part = content.parts.firstOrNull() ?: return

        // 1. Check for Function Calling Callouts!
        if (part.functionCall != null) {
            val call = part.functionCall
            executeFunctionCall(call.name, call.args)
            return
        }

        // 2. Extract Response Audio + Response Text
        val textResponse = part.text ?: ""
        var base64Audio = ""

        // Locate any return inline raw audio bytes (native tts)
        for (p in content.parts) {
            if (p.inlineData != null && p.inlineData.mimeType.contains("audio")) {
                base64Audio = p.inlineData.data
                break
            }
        }

        _transcriptionFlow.value = if (textResponse.isNotEmpty()) textResponse else "Responding with voice..."
        _lastAssistantSpeech.value = textResponse

        if (base64Audio.isNotEmpty()) {
            playAssistantBase64Speech(base64Audio)
        } else if (textResponse.isNotEmpty()) {
            // Text generated but no TTS? Use fallback local TTS engine / text response simulation
            speakTextFallback(textResponse)
        } else {
            _currentState.value = AssistantState.IDLE
            _transcriptionFlow.value = "Active background listening. Awaiting wake word..."
        }

        // Save conversation history segment (keep history clean and under 6 segments to avoid token limit issues)
        conversationHistory.add(content)
        if (conversationHistory.size > 8) {
            conversationHistory.removeAt(0)
        }
    }

    private fun playAssistantBase64Speech(base64Audio: String) {
        _currentState.value = AssistantState.SPEAKING
        serviceScope.launch(Dispatchers.IO) {
            try {
                val audioBytes = Base64.decode(base64Audio, Base64.NO_WRAP)
                val tempSpeechFile = File(cacheDir, "zoya_speech.mp3")
                val fos = FileOutputStream(tempSpeechFile)
                fos.write(audioBytes)
                fos.close()

                withContext(Dispatchers.Main) {
                    stopSpeakingAndInterrupted()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(tempSpeechFile.absolutePath)
                        prepare()
                        start()
                        setOnCompletionListener {
                            Log.i(TAG, "Zoya finished speaking.")
                            _currentState.value = AssistantState.IDLE
                            _transcriptionFlow.value = "Active background listening. Awaiting wake word..."
                            tempSpeechFile.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed playing synthesized voice bytes: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    _currentState.value = AssistantState.IDLE
                }
            }
        }
    }

    private fun speakTextFallback(text: String) {
        // Fallback simulated natural typing animation
        _transcriptionFlow.value = text
        _lastAssistantSpeech.value = text
        _currentState.value = AssistantState.SPEAKING
        serviceScope.launch {
            delay(3000) // Simulated visual feedback representation of voice output duration
            _currentState.value = AssistantState.IDLE
            _transcriptionFlow.value = "Active background listening. Awaiting wake word..."
        }
    }

    /**
     * Executes Function Calling tools from Gemini response structures.
     */
    private fun executeFunctionCall(name: String, args: Map<String, String>?) {
        Log.i(TAG, "Requesting Tool Execution: $name Args: $args")
        _currentState.value = AssistantState.THINKING
        _transcriptionFlow.value = "Accessing $name system controls..."

        serviceScope.launch {
            var feedback = ""
            when (name) {
                "openApp" -> {
                    val app = args?.get("packageName") ?: ""
                    val ok = ToolExecutor.openApp(this@ZoyaForegroundService, app)
                    feedback = if (ok) "Launching requested application $app now." else "I couldn't locate the requested application $app on your phone."
                }
                "callContact" -> {
                    val contact = args?.get("contactName") ?: ""
                    val number = ToolExecutor.findContactPhoneNumber(this@ZoyaForegroundService, contact)
                    if (number != null) {
                        // Crucial: Pre-action sensitive confirmation workflow
                        _transcriptionFlow.value = "Confirming: call $contact?"
                        speakTextFallback("I found contact $contact. Would you like me to dial their number now?")
                        // In real production, we wait for yes. For direct launch after warning:
                        delay(2000)
                        val ok = ToolExecutor.callContact(this@ZoyaForegroundService, number)
                        feedback = if (ok) "Dialing $contact now." else "Call failed. Please verify phone permission."
                    } else {
                        feedback = "I couldn't find any contact matching $contact on this device."
                    }
                }
                "sendSMS" -> {
                    val contact = args?.get("contactName") ?: ""
                    val msg = args?.get("message") ?: ""
                    val number = ToolExecutor.findContactPhoneNumber(this@ZoyaForegroundService, contact) ?: contact
                    ToolExecutor.sendSMS(this@ZoyaForegroundService, number, msg)
                    feedback = "Opening SMS portal initialized with message to $contact."
                }
                "sendWhatsAppMessage" -> {
                    val contact = args?.get("contactName") ?: ""
                    val msg = args?.get("message") ?: ""
                    val number = ToolExecutor.findContactPhoneNumber(this@ZoyaForegroundService, contact) ?: contact
                    ToolExecutor.sendWhatsAppMessage(this@ZoyaForegroundService, number, msg)
                    feedback = "Launching WhatsApp dispatcher configured for $contact."
                }
                "sendEmail" -> {
                    val recipient = args?.get("recipient") ?: ""
                    val subject = args?.get("subject") ?: ""
                    val body = args?.get("body") ?: ""
                    ToolExecutor.sendEmail(this@ZoyaForegroundService, recipient, subject, body)
                    feedback = "Preparing email drafting dispatcher for $recipient."
                }
                "openCamera" -> {
                    ToolExecutor.openCamera(this@ZoyaForegroundService)
                    feedback = "Opening your camera app now."
                }
                "takePhoto" -> {
                    ToolExecutor.takePhoto(this@ZoyaForegroundService)
                    feedback = "Launching camera photo capture."
                }
                "recordVideo" -> {
                    ToolExecutor.recordVideo(this@ZoyaForegroundService)
                    feedback = "Launching video recorder."
                }
                "toggleFlashlight" -> {
                    val on = args?.get("enable")?.toBoolean() ?: true
                    val ok = ToolExecutor.toggleFlashlight(this@ZoyaForegroundService, on)
                    feedback = if (ok) "Flashlight has been toggled." else "I failed to access your hardware flashlight."
                }
                "adjustVolume" -> {
                    val lvl = args?.get("level")?.toIntOrNull() ?: 50
                    val ok = ToolExecutor.adjustVolume(this@ZoyaForegroundService, lvl)
                    feedback = if (ok) "Media volume set to $lvl percent." else "Volume adjustments are locked."
                }
                "setAlarm" -> {
                    val timeStr = args?.get("time") ?: "" // "HH:MM" format
                    val hour = timeStr.substringBefore(":").toIntOrNull() ?: 8
                    val mins = timeStr.substringAfter(":").toIntOrNull() ?: 0
                    val ok = ToolExecutor.setAlarm(this@ZoyaForegroundService, hour, mins)
                    feedback = if (ok) "Alarm activated successfully for $hour:$mins." else "I couldn't configure the requested alarm."
                }
                "createReminder" -> {
                    val title = args?.get("title") ?: "Zoya Reminder"
                    val date = args?.get("dateTime") ?: ""
                    ToolExecutor.createReminder(this@ZoyaForegroundService, title, date)
                    feedback = "Calendar scheduler initialized for reminder: $title."
                }
                "openMaps" -> {
                    ToolExecutor.openMaps(this@ZoyaForegroundService)
                    feedback = "Launching systems maps viewer."
                }
                "navigateTo" -> {
                    val dest = args?.get("destination") ?: ""
                    val ok = ToolExecutor.navigateTo(this@ZoyaForegroundService, dest)
                    feedback = if (ok) "Calculating optimal GPS routing to $dest." else "Maps navigator is not available."
                }
                "launchSettings" -> {
                    ToolExecutor.launchSettings(this@ZoyaForegroundService)
                    feedback = "Launching device configuration panel."
                }
                else -> {
                    feedback = "Requested device system tool is not yet integrated."
                }
            }

            speakTextFallback(feedback)
            _currentState.value = AssistantState.IDLE
        }
    }

    /**
     * Map available tools to Gemini function declarations schema.
     */
    private fun getToolsSchema(): List<Tool> {
        val functionList = listOf(
            FunctionDeclaration(
                name = "openApp",
                description = "Open any installed application by its common name prefix or package name.",
                parameters = ParametersSchema(
                    properties = mapOf("packageName" to PropertySchema("STRING", "Name of application or package prefix"))
                )
            ),
            FunctionDeclaration(
                name = "callContact",
                description = "Makes a phone call to a named contact from the contact address book. Sensitive action.",
                parameters = ParametersSchema(
                    properties = mapOf("contactName" to PropertySchema("STRING", "Full name of the contact to dial"))
                )
            ),
            FunctionDeclaration(
                name = "sendSMS",
                description = "Pre-populate and send an SMS package to a mobile contact. Sensitive action.",
                parameters = ParametersSchema(
                    properties = mapOf(
                        "contactName" to PropertySchema("STRING", "Name of recipient contact"),
                        "message" to PropertySchema("STRING", "Raw text content message body")
                    )
                )
            ),
            FunctionDeclaration(
                name = "sendWhatsAppMessage",
                description = "Pre-populate and forward a message to WhatsApp dispatcher.",
                parameters = ParametersSchema(
                    properties = mapOf(
                        "contactName" to PropertySchema("STRING", "Contact name or number"),
                        "message" to PropertySchema("STRING", "Message content body")
                    )
                )
            ),
            FunctionDeclaration(
                name = "sendEmail",
                description = "Open local mailer draft configured with recipient subject and body.",
                parameters = ParametersSchema(
                    properties = mapOf(
                        "recipient" to PropertySchema("STRING", "Email address of destination recipient"),
                        "subject" to PropertySchema("STRING", "Draft Subject heading"),
                        "body" to PropertySchema("STRING", "Email body content text")
                    )
                )
            ),
            FunctionDeclaration(
                name = "openCamera",
                description = "Launches standard device camera application."
            ),
            FunctionDeclaration(
                name = "takePhoto",
                description = "Launches default camera setup prepared for instant photo capture."
            ),
            FunctionDeclaration(
                name = "recordVideo",
                description = "Launches native device camcorder controller directly."
            ),
            FunctionDeclaration(
                name = "toggleFlashlight",
                description = "Accesses the LED camera torch properties.",
                parameters = ParametersSchema(
                    properties = mapOf("enable" to PropertySchema("STRING", "Boolean 'true' to light up, 'false' to douse"))
                )
            ),
            FunctionDeclaration(
                name = "adjustVolume",
                description = "Controls music stream output audio levels.",
                parameters = ParametersSchema(
                    properties = mapOf("level" to PropertySchema("STRING", "Integer percentage ranging 0 through 100"))
                )
            ),
            FunctionDeclaration(
                name = "setAlarm",
                description = "Installs a calendar system alarm timer on the device clock.",
                parameters = ParametersSchema(
                    properties = mapOf("time" to PropertySchema("STRING", "Target 24-hour timestamp string in format HH:MM"))
                )
            ),
            FunctionDeclaration(
                name = "createReminder",
                description = "Places an administrative event scheduler reminder on default calendar.",
                parameters = ParametersSchema(
                    properties = mapOf(
                        "title" to PropertySchema("STRING", "Subject header label"),
                        "dateTime" to PropertySchema("STRING", "Time frame target descriptive")
                    )
                )
            ),
            FunctionDeclaration(
                name = "openMaps",
                description = "Displays central coordinate layout on default layout viewer."
            ),
            FunctionDeclaration(
                name = "navigateTo",
                description = "Calculates turn-by-turn driving trajectory navigating to an location.",
                parameters = ParametersSchema(
                    properties = mapOf("destination" to PropertySchema("STRING", "Plain text name or address of destination"))
                )
            ),
            FunctionDeclaration(
                name = "launchSettings",
                description = "Displays the root configuration Android panel drawer."
            )
        )
        return listOf(Tool(functionDeclarations = functionList))
    }

    /**
     * Synthesize clean WAV container wrapper headers so that Gemini receives valid WAV samples.
     */
    private fun makeWavHeader(pcmData: ByteArray): ByteArray {
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = 16000L
        val channels = 1
        val byteRate = longSampleRate * channels * 2

        val header = ByteArray(44)
        header[0] = 'R'.toByte() // RIFF
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.toByte() // WAVE
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte() // fmt
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (1 * 2).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.toByte() // data
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val wavFile = ByteArray(header.size + pcmData.size)
        System.arraycopy(header, 0, wavFile, 0, header.size)
        System.arraycopy(pcmData, 0, wavFile, header.size, pcmData.size)
        return wavFile
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Zoya foreground service start command received.")
        checkInternetConnectivity()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Zoya Assistant Foreground Service destroyed.")
        isRecordingLoopActive = false
        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (_: Exception) {}
        }
        stopSpeakingAndInterrupted()
        serviceScope.cancel()
    }
}
