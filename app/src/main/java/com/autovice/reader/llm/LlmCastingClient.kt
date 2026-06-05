package com.autovice.reader.llm

import com.autovice.reader.data.preferences.LlmProvider
import com.autovice.reader.domain.model.Gender
import com.autovice.reader.tts.EngineVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/** A character to cast: name (or "NARRATOR"), how often it appears, optional known gender. */
data class CastCharacter(val name: String, val appearanceCount: Int, val gender: Gender?)

/** The model's pick: a VOICEVOX style id for a character. */
data class CastAssignment(val character: String, val speakerId: Int)

class LlmCastingException(message: String) : Exception(message)

/**
 * Asks a hosted LLM to cast characters to VOICEVOX voices, given the live voice catalog.
 * Mirrors [LlmAttributionClient]'s transport (OkHttp + provider switch). Validated against
 * the `tts-poc/autocast.py` prototype.
 */
class LlmCastingClient @Inject constructor(
    private val http: OkHttpClient,
) {

    suspend fun cast(
        provider: LlmProvider,
        apiKey: String,
        characters: List<CastCharacter>,
        voices: List<EngineVoice>,
    ): List<CastAssignment> = withContext(Dispatchers.IO) {
        if (characters.isEmpty() || voices.isEmpty()) return@withContext emptyList()
        val prompt = buildUserPrompt(characters, voices)
        val text = when (provider) {
            LlmProvider.CLAUDE -> callClaude(apiKey, prompt)
            LlmProvider.OPENAI -> callOpenAi(apiKey, prompt)
        }
        parse(text)
    }

    private fun buildUserPrompt(characters: List<CastCharacter>, voices: List<EngineVoice>): String {
        val cast = characters.joinToString("\n") { c ->
            val g = when (c.gender) { Gender.MALE -> " [male]"; Gender.FEMALE -> " [female]"; null -> "" }
            "- ${c.name} (lines: ${c.appearanceCount})$g"
        }
        // Group the flat voice list back into "character（style）=id, …" lines, with gender hints.
        val byCharacter = LinkedHashMap<String, MutableList<EngineVoice>>()
        for (v in voices) {
            val name = v.label.substringBefore("（")
            byCharacter.getOrPut(name) { mutableListOf() }.add(v)
        }
        val catalog = byCharacter.entries.joinToString("\n") { (name, list) ->
            val hint = VOICEVOX_HINTS[name]
            val g = hint?.first ?: "?"
            val desc = hint?.second ?: ""
            val styles = list.joinToString(", ") { "${it.label.substringAfter("（").removeSuffix("）")}=${it.id}" }
            "$name [$g] $desc — styles: $styles"
        }
        return "CAST:\n$cast\n\nVOICEVOX CATALOG:\n$catalog"
    }

    private fun callClaude(apiKey: String, userPrompt: String): String {
        val body = JSONObject()
            .put("model", CLAUDE_MODEL)
            .put("max_tokens", MAX_TOKENS)
            .put("system", SYSTEM_PROMPT)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)))
            .toString()
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val responseText = execute(request)
        val content = JSONObject(responseText).optJSONArray("content")
            ?: throw LlmCastingException("Claude response missing content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    private fun callOpenAi(apiKey: String, userPrompt: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        val body = JSONObject()
            .put("model", OPENAI_MODEL)
            .put("max_tokens", MAX_TOKENS)
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", messages)
            .toString()
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val responseText = execute(request)
        return JSONObject(responseText)
            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: throw LlmCastingException("OpenAI response missing message content")
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(300)
                throw LlmCastingException("HTTP ${response.code}: $detail")
            }
            return text
        }
    }

    private fun parse(modelText: String): List<CastAssignment> {
        val start = modelText.indexOf('{')
        val end = modelText.lastIndexOf('}')
        if (start < 0 || end <= start) throw LlmCastingException("No JSON object in model reply")
        val arr = JSONObject(modelText.substring(start, end + 1)).optJSONArray("castings") ?: JSONArray()
        val out = ArrayList<CastAssignment>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("character").trim()
            if (name.isEmpty() || !obj.has("speaker_id")) continue
            out.add(CastAssignment(name, obj.getInt("speaker_id")))
        }
        return out
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_TOKENS = 2048
        const val CLAUDE_MODEL = "claude-haiku-4-5"
        const val OPENAI_MODEL = "gpt-4o-mini"

        val SYSTEM_PROMPT = """
            You cast Japanese web-novel characters to VOICEVOX voices for an audiobook reader.
            You get the cast (each character: name, line count, optional gender) and the VOICEVOX
            catalog (each character [gender] description — styles with their ids). Assign every
            character — including NARRATOR — one VOICEVOX style id from the catalog.

            Rules:
            - NARRATOR is the standard reading voice: default it to a neutral male voice,
              青山龍星 ノーマル (id 13), unless the cast clearly calls for otherwise. Never a strongly
              emotional style.
            - Strictly match gender and age. A male or boy character MUST get a male voice; a female
              character a female voice. A young boy gets a young male voice, not a female one.
            - Give distinct characters distinct voices, and never reuse the narrator's voice for a
              character. Only reuse a voice between two characters if you run out of fitting options
              for minor/one-off speakers.
            - Default to a normal (ノーマル/ふつう) style unless the character has a consistent strong
              affect, then pick the matching emotion style.
            - speaker_id MUST be an id present in the catalog.
            Respond with ONLY: {"castings":[{"character":"<name>","speaker_id":<id>}]}
            No prose, no code fences.
        """.trimIndent()

        // gender + short vibe for the well-known free VOICEVOX characters (ported from the POC).
        val VOICEVOX_HINTS: Map<String, Pair<String, String>> = mapOf(
            "四国めたん" to ("female" to "teen girl, a little haughty"),
            "ずんだもん" to ("neutral" to "childish mascot, boyish"),
            "春日部つむぎ" to ("female" to "bright high-school girl"),
            "雨晴はう" to ("female" to "soft, gentle, nurse-like"),
            "波音リツ" to ("female" to "cool, low-pitched adult woman"),
            "玄野武宏" to ("male" to "standard adult man, steady"),
            "白上虎太郎" to ("male" to "energetic young boy/teen"),
            "青山龍星" to ("male" to "deep, mature man; wide range"),
            "冥鳴ひまり" to ("female" to "calm, gentle girl"),
            "九州そら" to ("female" to "mature, composed woman"),
            "もち子さん" to ("female" to "warm, gentle adult woman"),
            "剣崎雌雄" to ("male" to "mature professional man"),
            "No.7" to ("female" to "clear, neutral; read-aloud styles"),
            "ちび式じい" to ("male" to "tiny old man"),
            "ナースロボ＿タイプＴ" to ("female" to "robotic nurse, flat/clear"),
            "雀松朱司" to ("male" to "mellow adult man"),
            "麒ヶ島宗麟" to ("male" to "dignified samurai lord"),
            "中部つるぎ" to ("male" to "man; anger/anxious styles"),
            "黒沢冴白" to ("male" to "cool, composed man"),
        )
    }
}
