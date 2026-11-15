package com.orokichi.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class LlmCfg(val provider: String, val key: String, val model: String)

class RateLimitException(msg: String) : IOException(msg)

object Llm {
    private val cooldown = HashMap<String, Long>()
    private var lastOk: String? = null
    private fun id(c: LlmCfg) = c.provider + ":" + c.key.takeLast(6)

    /** 登録済みの全キーを順に試す。制限(429)なら次へ、全部制限中なら60秒待って再試行。 */
    suspend fun ask(sys: String, user: String): String {
        for (round in 0..3) {
            val all = Prefs.candidates()
            if (all.isEmpty()) throw IOException("APIキーが未設定です（設定タブ）")
            val list = if (Prefs.autoSwitch) all.sortedBy { if (id(it) == lastOk) 0 else 1 } else all.take(1)
            for (c in list) {
                val until = cooldown[id(c)] ?: 0L
                if (until > System.currentTimeMillis()) continue
                try {
                    val r = chat(c, sys, user)
                    lastOk = id(c)
                    return r
                } catch (e: RateLimitException) {
                    cooldown[id(c)] = System.currentTimeMillis() + 60_000
                    AgentState.add("⏳ ${Prefs.providerLabel(c.provider)} が制限に到達" + if (Prefs.autoSwitch && list.size > 1) " → 切り替え" else "")
                }
            }
            if (round == 3) break
            val wait = ((list.minOfOrNull { cooldown[id(it)] ?: 0L } ?: 0L) - System.currentTimeMillis()).coerceIn(1_000, 60_000)
            AgentState.add("⏳ 全APIが制限中。${wait / 1000}秒待機します")
            var left = wait
            while (left > 0) {
                AgentState.status.value = "API制限のため待機中… ${(left + 999) / 1000}秒"
                kotlinx.coroutines.delay(minOf(1000L, left)); left -= 1000
            }
        }
        throw IOException("API制限が続いたため停止しました")
    }

    suspend fun chat(c: LlmCfg, sys: String, user: String): String = withContext(Dispatchers.IO) {
        if (c.key.isBlank()) throw IOException("${Prefs.providerLabel(c.provider)} のAPIキーが未設定です（設定タブ）")
        if (c.provider == "gemini") gemini(c, sys, user) else openai(c, sys, user)
    }

    private fun openai(c: LlmCfg, sys: String, user: String): String {
        val url = if (c.provider == "openrouter") "https://openrouter.ai/api/v1/chat/completions"
        else "https://api.groq.com/openai/v1/chat/completions"
        val body = JSONObject()
            .put("model", c.model)
            .put("temperature", 0.2)
            .put("max_tokens", 600)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        if (c.provider == "groq") body.put("response_format", JSONObject().put("type", "json_object"))
        val h = mutableMapOf("Authorization" to "Bearer ${c.key}")
        if (c.provider == "openrouter") {
            h["HTTP-Referer"] = "https://github.com/orokichi/ai-agent"
            h["X-Title"] = "AI Agent"
        }
        val r = JSONObject(post(url, h, body.toString()))
        return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun gemini(c: LlmCfg, sys: String, user: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${c.model}:generateContent"
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))
                )
            )
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
        val r = JSONObject(post(url, mapOf("x-goog-api-key" to c.key), body.toString()))
        val parts = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
    }

    private fun post(url: String, h: Map<String, String>, body: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 20_000
            con.readTimeout = 60_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            h.forEach { (k, v) -> con.setRequestProperty(k, v) }
            con.outputStream.use { it.write(body.toByteArray()) }
            val code = con.responseCode
            val txt = (if (code in 200..299) con.inputStream else con.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 429 || (code in 500..503 && txt.contains("rate", true)) || txt.contains("RESOURCE_EXHAUSTED"))
                throw RateLimitException("API制限 $code")
            if (code !in 200..299) throw IOException("API エラー $code: ${txt.take(180)}")
            return txt
        } finally {
            con.disconnect()
        }
    }

    fun parseJson(s: String): JSONObject {
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) throw IOException("AIの応答を解析できません: ${s.take(80)}")
        return JSONObject(s.substring(a, b + 1))
    }
}
