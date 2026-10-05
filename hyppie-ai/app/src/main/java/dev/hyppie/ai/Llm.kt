package dev.hyppie.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** User settings. API keys stay on the phone (app-private storage) and are only sent to their provider. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("hyppie", Context.MODE_PRIVATE)

    var provider: String
        get() = p.getString("provider", "ollama")!!
        set(v) = p.edit().putString("provider", v).apply()
    var ollamaUrls: String   // comma separated, tried in order (USB "adb reverse" first, then the laptop on Wi-Fi)
        get() = p.getString("ollama_urls", "http://127.0.0.1:11434")!!
        set(v) = p.edit().putString("ollama_urls", v).apply()
    var ollamaModel: String
        get() = p.getString("ollama_model", "qwen2.5:7b-instruct")!!
        set(v) = p.edit().putString("ollama_model", v).apply()
    var claudeKey: String
        get() = p.getString("claude_key", "")!!
        set(v) = p.edit().putString("claude_key", v).apply()
    var claudeModel: String
        get() = p.getString("claude_model", "claude-haiku-4-5-20251001")!!
        set(v) = p.edit().putString("claude_model", v).apply()
    var geminiKey: String
        get() = p.getString("gemini_key", "")!!
        set(v) = p.edit().putString("gemini_key", v).apply()
    var geminiModel: String
        get() = p.getString("gemini_model", "gemini-2.5-flash")!!
        set(v) = p.edit().putString("gemini_model", v).apply()
    var groqKey: String
        get() = p.getString("groq_key", "")!!
        set(v) = p.edit().putString("groq_key", v).apply()
    var groqModel: String
        get() = p.getString("groq_model", "openai/gpt-oss-120b")!!
        set(v) = p.edit().putString("groq_model", v).apply()
    var island: Boolean
        get() = p.getBoolean("island", true)
        set(v) = p.edit().putBoolean("island", v).apply()
    var speak: Boolean
        get() = p.getBoolean("speak", false)
        set(v) = p.edit().putBoolean("speak", v).apply()
}

data class Msg(val role: String, val text: String)   // role: "user" | "assistant"

class LlmException(msg: String) : Exception(msg)

/** One blocking chat call to whichever brain is configured. Call from a background thread. */
class Llm(private val prefs: Prefs) {

    fun label(): String = when (prefs.provider) {
        "claude" -> "Claude"
        "gemini" -> "Gemini"
        "groq" -> "Groq"
        else -> "Laptop AI"
    }

    /** Quick check (≤ 1.5 s) whether the configured brain can be reached. */
    fun reachable(): Boolean = when (prefs.provider) {
        "claude" -> prefs.claudeKey.isNotBlank()
        "gemini" -> prefs.geminiKey.isNotBlank()
        "groq" -> prefs.groqKey.isNotBlank()
        else -> prefs.ollamaUrls.split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.any { base ->
            runCatching {
                val c = URL("$base/api/tags").openConnection() as HttpURLConnection
                c.connectTimeout = 700; c.readTimeout = 800
                c.responseCode == 200
            }.getOrDefault(false)
        }
    }

    fun chat(system: String, history: List<Msg>, json: Boolean = false, maxTokens: Int = 700): String =
        when (prefs.provider) {
            "claude" -> claude(system, history, maxTokens)
            "gemini" -> gemini(system, history, json, maxTokens)
            "groq" -> groq(system, history, json, maxTokens)
            else -> ollama(system, history, json, maxTokens)
        }

    private fun ollama(system: String, history: List<Msg>, json: Boolean, maxTokens: Int): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject()
            .put("model", prefs.ollamaModel)
            .put("messages", msgs)
            .put("stream", false)
            .put("think", false)
            .put("keep_alive", "30m")
            .put("options", JSONObject().put("num_predict", maxTokens).put("temperature", 0.3))
        if (json) body.put("format", "json")
        var last: Exception? = null
        for (base in prefs.ollamaUrls.split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }) {
            try {
                val out = post("$base/api/chat", body.toString(), emptyMap(), connectTimeout = 2500)
                return JSONObject(out).getJSONObject("message").getString("content").trim()
            } catch (e: Exception) {
                last = e
            }
        }
        throw LlmException("I'm offline right now (the laptop AI isn't reachable), so I can only do quick commands like " +
            "Wi-Fi, flashlight, volume, timers, opening apps, the time or battery.")
    }

    private fun claude(system: String, history: List<Msg>, maxTokens: Int): String {
        if (prefs.claudeKey.isBlank()) throw LlmException("Add your Claude API key in the assistant settings.")
        val msgs = JSONArray()
        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject().put("model", prefs.claudeModel).put("max_tokens", maxTokens)
            .put("system", system).put("messages", msgs)
        val out = post("https://api.anthropic.com/v1/messages", body.toString(),
            mapOf("x-api-key" to prefs.claudeKey, "anthropic-version" to "2023-06-01"))
        val content = JSONObject(out).getJSONArray("content")
        return (0 until content.length()).map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString("") { it.getString("text") }.trim()
    }

    /** Groq: OpenAI-compatible chat completions (free tier, large Llama models, very fast). */
    private fun groq(system: String, history: List<Msg>, json: Boolean, maxTokens: Int): String {
        if (prefs.groqKey.isBlank()) throw LlmException("Add your Groq API key in the assistant settings.")
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject().put("model", prefs.groqModel).put("messages", msgs)
            .put("max_tokens", maxTokens + 400).put("temperature", 0.3)
        if (prefs.groqModel.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low")   // fast replies
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        val out = post("https://api.groq.com/openai/v1/chat/completions", body.toString(),
            mapOf("authorization" to "Bearer ${prefs.groqKey}"))
        return JSONObject(out).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
    }

    private fun gemini(system: String, history: List<Msg>, json: Boolean, maxTokens: Int): String {
        if (prefs.geminiKey.isBlank()) throw LlmException("Add your Gemini API key in the assistant settings.")
        val contents = JSONArray()
        history.forEach {
            contents.put(JSONObject().put("role", if (it.role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
        }
        val gen = JSONObject().put("maxOutputTokens", maxTokens).put("temperature", 0.3)
        if (json) gen.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents).put("generationConfig", gen)
        val out = post("https://generativelanguage.googleapis.com/v1beta/models/${prefs.geminiModel}:generateContent",
            body.toString(), mapOf("x-goog-api-key" to prefs.geminiKey))
        val parts = JSONObject(out).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
    }

    private fun post(url: String, body: String, headers: Map<String, String>, connectTimeout: Int = 10000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = connectTimeout
        c.readTimeout = 180000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: text.take(200)
            throw LlmException("HTTP $code: $msg")
        }
        return text
    }
}
