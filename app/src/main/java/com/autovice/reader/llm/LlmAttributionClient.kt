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

    suspend fun attribute(
        provider: LlmProvider,
        apiKey: String,
        lines: List<AttributionLine>,
        knownCharacters: List<String>,
    ): List<AttributionResult> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        val userPrompt = buildUserPrompt(lines, knownCharacters)
        val modelText = when (provider) {
            LlmProvider.CLAUDE -> callClaude(apiKey, userPrompt)
            LlmProvider.OPENAI -> callOpenAi(apiKey, userPrompt)
        }
        parseAttributions(modelText)
    }

    private fun buildUserPrompt(lines: List<AttributionLine>, knownCharacters: List<String>): String {
        val sb = StringBuilder()
        if (knownCharacters.isNotEmpty()) {
            sb.append("Known characters: ").append(knownCharacters.joinToString("、")).append("\n\n")
        }
        sb.append("Transcript (each line prefixed with [segment index]):\n")
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
        const val CLAUDE_MODEL = "claude-3-5-haiku-latest"
        const val OPENAI_MODEL = "gpt-4o-mini"

        val SYSTEM_PROMPT = """
            You are a dialogue attribution engine for Japanese web novels.
            You receive an enumerated transcript; each line is one text segment prefixed with [index].
            Identify the speaker of every line that contains quoted dialogue (text inside 「」or 『』).
            Use the surrounding narration segments as context to resolve who is speaking.
            Respond with ONLY a JSON object of the form:
            {"attributions":[{"i":<segment index>,"speaker":"<value>"}]}
            where <value> is the speaking character's name exactly as written in the text, or one of:
            NARRATOR (the line is narration, not dialogue),
            UNKNOWN (dialogue whose speaker cannot be determined),
            GROUP (dialogue spoken by multiple people at once).
            Only include lines that contain quoted dialogue. Output no prose, no code fences.
        """.trimIndent()
    }
}
