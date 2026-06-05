package com.autovice.reader.llm

import com.autovice.reader.data.preferences.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/** One transcript line handed to the model, identified by its in-chapter segment index. */
data class AttributionLine(val segmentIndex: Int, val text: String)

/** A speaker the model resolved for a given segment. [speaker] is a name or a sentinel. */
data class AttributionResult(val segmentIndex: Int, val speaker: String) {
    companion object {
        const val NARRATOR = "NARRATOR"
        const val UNKNOWN = "UNKNOWN"
        const val GROUP = "GROUP"
    }
}

class LlmAttributionException(message: String) : Exception(message)

/**
 * Calls a hosted LLM (Claude or OpenAI) to attribute quoted dialogue to speakers.
 * One [attribute] call == one HTTP request; callers chunk long chapters.
 */
class LlmAttributionClient @Inject constructor(
    private val http: OkHttpClient,
) {

    /** A previously-attributed lead-in line, shown to keep turn-taking coherent across chunks. */
    data class LeadInLine(val segmentIndex: Int, val text: String, val speaker: String)

    suspend fun attribute(
        provider: LlmProvider,
        apiKey: String,
        lines: List<AttributionLine>,
        knownCharacters: List<String>,
        leadIn: List<LeadInLine> = emptyList(),
    ): List<AttributionResult> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        val userPrompt = buildUserPrompt(lines, knownCharacters, leadIn)
        val modelText = when (provider) {
            LlmProvider.CLAUDE -> callClaude(apiKey, userPrompt)
            LlmProvider.OPENAI -> callOpenAi(apiKey, userPrompt)
        }
        parseAttributions(modelText)
    }

    private fun buildUserPrompt(
        lines: List<AttributionLine>,
        knownCharacters: List<String>,
        leadIn: List<LeadInLine>,
    ): String {
        val sb = StringBuilder()
        sb.append("CHARACTER ROSTER SO FAR:\n")
        if (knownCharacters.isEmpty()) sb.append("(none yet)\n")
        else knownCharacters.forEach { sb.append("- ").append(it).append('\n') }
        sb.append("\nLEAD-IN (previous lines, already attributed):\n")
        if (leadIn.isEmpty()) sb.append("(start of chapter)\n")
        else leadIn.forEach { sb.append('[').append(it.segmentIndex).append("] (").append(it.speaker).append(") ").append(it.text).append('\n') }
        sb.append("\nNEW CHUNK — attribute EVERY numbered segment below:\n")
        lines.forEach { sb.append('[').append(it.segmentIndex).append("] ").append(it.text).append('\n') }
        return sb.toString()
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
            ?: throw LlmAttributionException("Claude response missing content")
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
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?: throw LlmAttributionException("OpenAI response missing message content")
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = extractApiError(text) ?: text.take(300)
                throw LlmAttributionException("HTTP ${response.code}: $detail")
            }
            return text
        }
    }

    private fun extractApiError(text: String): String? = runCatching {
        JSONObject(text).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Pulls the JSON object out of the model's reply (tolerant of stray prose / code fences). */
    private fun parseAttributions(modelText: String): List<AttributionResult> {
        val start = modelText.indexOf('{')
        val end = modelText.lastIndexOf('}')
        if (start < 0 || end <= start) throw LlmAttributionException("No JSON object in model reply")
        val json = JSONObject(modelText.substring(start, end + 1))
        val arr = json.optJSONArray("attributions") ?: JSONArray()
        val results = ArrayList<AttributionResult>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!obj.has("i")) continue
            val speaker = obj.optString("speaker").trim()
            if (speaker.isEmpty()) continue
            results.add(AttributionResult(obj.getInt("i"), speaker))
        }
        return results
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_TOKENS = 4096

        // Cost-efficient defaults; change here to use a different hosted model.
        const val CLAUDE_MODEL = "claude-haiku-4-5"
        const val OPENAI_MODEL = "gpt-4o-mini"

        val SYSTEM_PROMPT = """
            You attribute speakers in a Japanese web novel, a chunk at a time, in order.
            You receive the running character roster, a short already-attributed lead-in, and a new
            chunk of numbered segments. Attribute EVERY numbered segment in the new chunk:

            - Narration (no quotation marks 「」『』, or inner description) -> "NARRATOR".
            - Dialogue 「…」 whose speaker you can determine from speech tags (e.g. ～と太郎が言った),
              names, honorifics, turn-taking, or speech style -> the character's name exactly as it
              appears in the text (Japanese).
            - Dialogue spoken together by multiple people -> "GROUP".
            - Dialogue you genuinely cannot attribute -> "UNKNOWN". Prefer UNKNOWN over a wild guess.

            Use the lead-in for turn-taking: in an alternating two-person exchange, speakers usually
            alternate. Keep names consistent with the roster (same surface form). Do not invent a name
            from a fragment — only use a name actually present as a speaker.

            Respond with ONLY a JSON object, one entry per segment in the new chunk:
            {"attributions":[{"i":<segment index>,"speaker":"<name|NARRATOR|UNKNOWN|GROUP>"}]}
            No prose, no code fences.
        """.trimIndent()
    }
}
