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

/** A reading-corrected TTS text for a segment: [yomi] = the segment with ambiguous kanji in kana. */
data class ReadingCorrection(val segmentIndex: Int, val yomi: String)

class LlmReadingException(message: String) : Exception(message)

/**
 * Dedicated LLM pass that fixes context-ambiguous kanji readings for TTS. Kept separate from
 * attribution because the model only does this reliably when it's the sole task. Selective: only the
 * genuinely ambiguous words are respelled in kana, leaving surrounding kanji so VOICEVOX's
 * word-boundary and accent cues are preserved (verified: selective yields the same correct reading
 * as full-kana without the boundary risk). Mirrors [LlmCastingClient]'s transport.
 */
class LlmReadingClient @Inject constructor(
    private val http: OkHttpClient,
) {

    suspend fun correctReadings(
        provider: LlmProvider,
        apiKey: String,
        lines: List<AttributionLine>,
    ): List<ReadingCorrection> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        val prompt = buildUserPrompt(lines)
        val text = when (provider) {
            LlmProvider.CLAUDE -> callClaude(apiKey, prompt)
            LlmProvider.OPENAI -> callOpenAi(apiKey, prompt)
        }
        parse(text)
    }

    private fun buildUserPrompt(lines: List<AttributionLine>): String {
        val sb = StringBuilder("SEGMENTS:\n")
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
            ?: throw LlmReadingException("Claude response missing content")
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
            ?: throw LlmReadingException("OpenAI response missing message content")
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(300)
                throw LlmReadingException("HTTP ${response.code}: $detail")
            }
            return text
        }
    }

    private fun parse(modelText: String): List<ReadingCorrection> {
        val start = modelText.indexOf('{')
        val end = modelText.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val arr = JSONObject(modelText.substring(start, end + 1)).optJSONArray("readings") ?: return emptyList()
        val out = ArrayList<ReadingCorrection>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!obj.has("i")) continue
            val yomi = obj.optString("yomi").trim()
            if (yomi.isEmpty()) continue
            out.add(ReadingCorrection(obj.getInt("i"), yomi))
        }
        return out
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_TOKENS = 4096
        const val CLAUDE_MODEL = "claude-haiku-4-5"
        const val OPENAI_MODEL = "gpt-4o-mini"

        val SYSTEM_PROMPT = """
            You fix Japanese kanji readings for a text-to-speech engine. You receive numbered segments
            of a Japanese novel. TTS engines mispronounce kanji whose reading depends on context or
            grammar — your job is to catch those.

            For EACH segment that contains such a word, output a "yomi": the segment rewritten with
            ONLY the ambiguous word(s) respelled in hiragana — every other character (other kanji,
            kana, punctuation, 「」) left byte-for-byte identical. Keeping the surrounding kanji is
            important so word boundaries stay clear. If a segment has no context-ambiguous word, do
            not include it in the output at all.

            Examples (only the ambiguous word changes):
            - お腹が空いた → "お腹がすいた"   (空いた = すいた, hungry; not あいた)
            - 苦手な方だ → "苦手なかただ"   (方 = かた, person; not ほう)
            - 旅行に行った → "旅行にいった"   (行った = いった, went; not おこなった)
            - 一行は宿に向かう → "いっこうは宿に向かう"   (一行 = いっこう, the group)
            - 何人いるの → "なんにんいるの"
            Look especially at: 方, 行く/行った, 空く/空いた, 入る/入れる, 開く/開ける, 上/下/中, 何,
            counters, and numerals whose reading depends on the counter.

            Do NOT romanize, do NOT convert a whole segment to kana, do NOT change wording or
            punctuation. Respond with ONLY JSON, including only the segments that needed a fix:
            {"readings":[{"i":<segment index>,"yomi":"<segment with ambiguous words in kana>"}]}
            No prose, no code fences.
        """.trimIndent()
    }
}
