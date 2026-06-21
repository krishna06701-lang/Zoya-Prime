package com.example.data.gemini

import android.util.Log
import com.example.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface GeminiApiService {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object GeminiApiClient {
    private const val TAG = "GeminiApiClient"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GeminiApiService::class.java)
    }

    /**
     * Executes content generation using Gemini. It handles model switching,
     * tool injection, and error recovery.
     */
    suspend fun getAssistantResponse(
        prompt: String,
        userAudioBase64: String? = null,
        history: List<Content> = emptyList(),
        tools: List<Tool>? = null,
        systemInstructionText: String? = null
    ): GenerateContentResponse? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "Gemini API key is not set or placeholder!")
            return null
        }

        // Build system instruction part
        val systemInstruction = systemInstructionText?.let {
            Content(parts = listOf(Part(text = it)))
        }

        // Build active query part
        val activeParts = mutableListOf<Part>()
        if (prompt.isNotEmpty()) {
            activeParts.add(Part(text = prompt))
        }
        if (userAudioBase64 != null) {
            activeParts.add(Part(inlineData = InlineData(mimeType = "audio/wav", data = userAudioBase64)))
        }

        val activeContent = Content(role = "user", parts = activeParts)

        // Combine history + current action
        val fullContents = history + listOf(activeContent)

        // Request dual output (Text + Audio) so it responds with premium speech + backup text
        val generationConfig = GenerationConfig(
            temperature = 0.7f,
            responseModalities = listOf("TEXT", "AUDIO"),
            speechConfig = SpeechConfig(
                voiceConfig = VoiceConfig(
                    prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = "Aoede") // Soft, warm, natural female voice
                )
            )
        )

        val request = GenerateContentRequest(
            contents = fullContents,
            generationConfig = generationConfig,
            tools = tools,
            systemInstruction = systemInstruction
        )

        // We use gemini-2.5-flash-native-audio-preview-12-2025 as recommended for real-time voice,
        // fallback to gemini-3.5-flash if there's any capacity limit on native audio preview
        return try {
            service.generateContent(
                model = "gemini-2.5-flash-native-audio-preview-12-2025",
                apiKey = apiKey,
                request = request
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed with Native Audio model, falling back to gemini-3.5-flash: ${e.localizedMessage}")
            try {
                // If native audio fails, fall back to gemini-3.5-flash (which supports text + fallback TTS)
                service.generateContent(
                    model = "gemini-3.5-flash",
                    apiKey = apiKey,
                    request = request.copy(
                        generationConfig = GenerationConfig(
                            temperature = 0.6f,
                            responseModalities = listOf("TEXT") // Fall back to pure text, and generate TTS locally
                        )
                    )
                )
            } catch (ex: Exception) {
                Log.e(TAG, "Critically failed to generate content from fallback: ${ex.localizedMessage}")
                null
            }
        }
    }
}
