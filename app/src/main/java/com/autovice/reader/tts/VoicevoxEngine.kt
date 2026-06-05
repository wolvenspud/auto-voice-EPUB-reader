package com.autovice.reader.tts

import com.autovice.audio.WavProcessor
import com.autovice.reader.data.preferences.ReaderPreferencesRepository
import com.autovice.reader.domain.model.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder

/**
 * Talks to a VOICEVOX engine over HTTP (the user supplies the base URL/IP in Settings).
 * Synthesis is the standard `/audio_query` -> `/synthesis` two-step; the engine returns
 * 24 kHz mono WAV, which is already the app's canonical synthesis format.
 *
 * [VoiceProfile.externalVoiceId] holds the VOICEVOX style id (an integer, as a string).
 * On-device VOICEVOX (native JNI) could replace this class later behind the same interface.
 */
class VoicevoxEngine(
    private val http: OkHttpClient,
    private val prefs: ReaderPreferencesRepository,
) : VoiceEngine {

    private val _state = MutableStateFlow(VoiceEngine.State.UNINITIALISED)
    override val state: StateFlow<VoiceEngine.State> = _state.asStateFlow()

    private suspend fun baseUrl(): String =
        prefs.preferences.first().voicevoxBaseUrl.trim().trimEnd('/')

    override suspend fun initialise(): Boolean = withContext(Dispatchers.IO) {
        val url = "${baseUrl()}/version"
        val ok = runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.onFailure { android.util.Log.w("Voicevox", "initialise() to $url failed: $it") }
            .getOrDefault(false)
        _state.value = if (ok) VoiceEngine.State.READY else VoiceEngine.State.ERROR
        ok
    }

    override suspend fun synthesiseToFile(
        text: String,
        voiceProfile: VoiceProfile,
        outputFile: File,
    ): Long = withContext(Dispatchers.IO) {
        val speaker = voiceProfile.externalVoiceId?.toIntOrNull() ?: DEFAULT_SPEAKER
        val base = baseUrl()

        // 1. audio_query — returns the synthesis parameters JSON for this text+speaker.
        val q = "text=${URLEncoder.encode(text, "UTF-8")}&speaker=$speaker"
        val queryReq = Request.Builder()
            .url("$base/audio_query?$q")
            .post(ByteArray(0).toRequestBody())
            .build()
        val queryJson = http.newCall(queryReq).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext -1L
            resp.body?.string() ?: return@withContext -1L
        }

        // 2. synthesis — POST the query JSON back, get WAV bytes.
        val synthReq = Request.Builder()
            .url("$base/synthesis?speaker=$speaker")
            .post(queryJson.toRequestBody(JSON))
            .build()
        val ok = http.newCall(synthReq).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext -1L
            val bytes = resp.body?.bytes() ?: return@withContext -1L
            outputFile.writeBytes(bytes)
            true
        }
        if (!ok || !outputFile.exists()) return@withContext -1L

        val info = WavProcessor.readInfo(outputFile)
        WavProcessor.durationMs(info)
    }

    override fun release() {
        _state.value = VoiceEngine.State.UNINITIALISED
    }

    /** GET /speakers -> one [EngineVoice] per character style, id = the style id. */
    override suspend fun listVoices(): List<EngineVoice> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${baseUrl()}/speakers").get().build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string() ?: return@withContext emptyList()
            }
            val speakers = JSONArray(body)
            val out = ArrayList<EngineVoice>()
            for (i in 0 until speakers.length()) {
                val spk = speakers.getJSONObject(i)
                val name = spk.optString("name")
                val styles = spk.optJSONArray("styles") ?: continue
                for (j in 0 until styles.length()) {
                    val style = styles.getJSONObject(j)
                    out.add(EngineVoice(id = style.optInt("id").toString(), label = "$name（${style.optString("name")}）"))
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        /** 青山龍星 / ノーマル — the neutral-male default if a profile has no style set. */
        const val DEFAULT_SPEAKER = 13
    }
}
