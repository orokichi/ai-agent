package com.orokichi.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class LlmCfg(val provider: String, val key: String, val model: String)

class RateLimitException(msg: String, val waitMs: Long = 60_000) : IOException(msg)
class TooLargeException(msg: String) : IOException(msg)

object Llm {
    private val cooldown = HashMap<String, Long>()
    private var lastOk: String? = null
    private fun id(c: LlmCfg) = c.provider + ":" + c.key.takeLast(6)

    /** 登録済みの全キーを順に試す。制限(429)なら次へ、全部制限中なら60秒待って再試行。 */
    suspend fun ask(sys: String, user: String, localSys: String? = null): String {
        val lm = Prefs.localMode
        if (lm != "off") {
            val ready = LocalLlm.readyModel(App.ctx)
            if (ready != null) {
                try { return LocalLlm.ask(App.ctx, localSys ?: sys, user, if (localSys != null) Agent.SCHEMA else null) }
                catch (e: Throwable) {
                    if (lm == "only" || e is kotlinx.coroutines.CancellationException) throw if (e is IOException || e is kotlinx.coroutines.CancellationException) e else IOException("端末内AIエラー: ${e.message}")
                    AgentState.add("⚠ 端末内AIエラー → クラウドAPIへ: ${e.message?.take(60)}")
                }
            } else if (lm == "only") throw IOException("端末内モデルがダウンロードされていません（設定タブ）")
        }
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
                    val w = e.waitMs.coerceIn(1_000, 60_000)
                    cooldown[id(c)] = System.currentTimeMillis() + w
                    // 短い待ちで済むなら切り替えずに待って同じキーで再試行（Groqのトークン/分制限）
                    if (w <= 8_000 && (list.size == 1 || !Prefs.autoSwitch)) {
                        AgentState.add("⏳ ${Prefs.providerLabel(c.provider)} 制限、${(w + 999) / 1000}秒待機")
                        kotlinx.coroutines.delay(w); cooldown.remove(id(c))
                        try { val r = chat(c, sys, user); lastOk = id(c); return r } catch (_: RateLimitException) {}
                    } else AgentState.add("⏳ ${Prefs.providerLabel(c.provider)} が制限に到達（${w / 1000}秒）" + if (Prefs.autoSwitch && list.size > 1) " → 切り替え" else "")
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
        if (c.provider == "gemini") gemini(c, sys, user)
        else if (c.provider == "groq") groq(c, sys, user)
        else openai(c, sys, user)
    }

    /* ---------- Groq（gpt-oss 最適化） ---------- */

    private class Budget(var remaining: Int = Int.MAX_VALUE, var resetAt: Long = 0)
    private val budgets = HashMap<String, Budget>()
    // キー×モデルごとの出力形式（strict → json_object → なし の順に自動で落とす）
    private val groqMode = HashMap<String, Int>()

    private fun estTokens(s: String): Int {
        var n = 0.0
        for (ch in s) n += if (ch.code < 128) 0.3 else 1.0
        return n.toInt() + 50
    }

    /** "7.66s" "2m59.56s" "120ms" → ミリ秒 */
    fun parseDur(v: String?): Long? {
        if (v.isNullOrBlank()) return null
        v.trim().toDoubleOrNull()?.let { return (it * 1000).toLong() }
        var ms = 0.0
        Regex("([0-9.]+)(ms|h|m|s)").findAll(v).forEach {
            val x = it.groupValues[1].toDouble()
            ms += when (it.groupValues[2]) { "ms" -> x; "s" -> x * 1000; "m" -> x * 60_000; else -> x * 3_600_000 }
        }
        return if (ms > 0) ms.toLong() else null
    }

    private suspend fun groq(c: LlmCfg, sys: String, user: String): String {
        val oss = c.model.startsWith("openai/gpt-oss")
        val k = id(c) + c.model
        // トークン/分の残りが足りなければ、リセットまで先に待つ（429を出さない）
        val need = estTokens(sys) + estTokens(user) + if (oss) 1200 else 600
        budgets[k]?.let { b ->
            val now = System.currentTimeMillis()
            if (now < b.resetAt && b.remaining < need) {
                val w = (b.resetAt - now).coerceAtMost(30_000)
                AgentState.status.value = "Groqの枠回復を待機… ${(w + 999) / 1000}秒"
                kotlinx.coroutines.delay(w)
            }
        }
        var u = user
        var mode = groqMode[k] ?: if (oss) 0 else 1
        repeat(4) { attempt ->
            val body = JSONObject()
                .put("model", c.model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", u)))
            if (oss) {
                body.put("reasoning_effort", if (attempt == 0) "low" else "low")
                body.put("include_reasoning", false)
                body.put("max_completion_tokens", if (attempt == 0) 2048 else 4096)
                body.put("temperature", 0.3)
            } else {
                body.put("temperature", 0.1).put("max_completion_tokens", 900)
            }
            when (mode) {
                0 -> body.put("response_format", JSONObject().put("type", "json_schema").put("json_schema",
                    JSONObject().put("name", "phone_action").put("strict", true).put("schema", Agent.STRICT_SCHEMA)))
                1 -> body.put("response_format", JSONObject().put("type", "json_object"))
            }
            val res = try {
                withContext(Dispatchers.IO) { postFull("https://api.groq.com/openai/v1/chat/completions", mapOf("Authorization" to "Bearer ${c.key}"), body.toString()) }
            } catch (e: TooLargeException) {
                if (u.length < 1500) throw IOException("入力が大きすぎます（Groqの1分あたりの上限）")
                AgentState.add("ℹ Groq: 入力が大きすぎるため画面情報を短縮して再試行")
                u = u.take(u.length * 6 / 10) + "\n…\n" + u.takeLast(300)
                return@repeat
            }
            // 残りトークンを記録
            val b = budgets.getOrPut(k) { Budget() }
            res.h("x-ratelimit-remaining-tokens")?.toIntOrNull()?.let { b.remaining = it }
            parseDur(res.h("x-ratelimit-reset-tokens"))?.let { b.resetAt = System.currentTimeMillis() + it }
            if (res.code == 429 || (res.code == 413 && res.body.contains("rate_limit", true))) {
                val w = parseDur(res.h("retry-after")) ?: parseDur(res.h("x-ratelimit-reset-tokens")) ?: 60_000
                if (res.code == 413 || res.body.contains("Request too large", true)) {
                    if (u.length > 1500) { u = u.take(u.length * 6 / 10) + "\n…\n" + u.takeLast(300); AgentState.add("ℹ Groq: 1回の入力が上限超え → 短縮"); return@repeat }
                }
                throw RateLimitException("API制限 ${res.code}", w)
            }
            if (res.code == 400) {
                val err = runCatching { JSONObject(res.body).optJSONObject("error") }.getOrNull()
                val fg = err?.optString("failed_generation").orEmpty()
                if (fg.contains('{')) { groqMode[k] = mode; return fg }
                val msg = err?.optString("message").orEmpty()
                if (mode < 2 && (msg.contains("response_format", true) || msg.contains("json", true) || msg.contains("schema", true) || err?.optString("code") == "json_validate_failed")) {
                    mode++; groqMode[k] = mode
                    AgentState.add("ℹ Groq: 出力形式を切り替え（${listOf("strict", "json_object", "通常")[mode]}）")
                    return@repeat
                }
                throw IOException("Groq エラー 400: ${msg.ifEmpty { res.body }.take(160)}")
            }
            if (res.code !in 200..299) throw IOException("Groq エラー ${res.code}: ${res.body.take(160)}")
            groqMode[k] = mode
            val ch = JSONObject(res.body).getJSONArray("choices").getJSONObject(0)
            val content = ch.getJSONObject("message").optString("content")
            if (content.isBlank() || (ch.optString("finish_reason") == "length" && !content.trimEnd().endsWith("}"))) {
                AgentState.add("ℹ Groq: 応答が途中で切れたため再試行")
                return@repeat
            }
            return content
        }
        throw IOException("Groqから有効な応答を得られませんでした")
    }

    private class Res(val code: Int, val body: String, val headers: Map<String, List<String>>) {
        fun h(n: String) = headers.entries.firstOrNull { it.key?.equals(n, true) == true }?.value?.firstOrNull()
    }

    private fun postFull(url: String, h: Map<String, String>, body: String): Res {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 20_000
            con.readTimeout = 90_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            h.forEach { (k, v) -> con.setRequestProperty(k, v) }
            con.outputStream.use { it.write(body.toByteArray()) }
            val code = con.responseCode
            val txt = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 413 && !txt.contains("rate_limit", true)) throw TooLargeException("413")
            return Res(code, txt, con.headerFields.filterKeys { it != null })
        } finally { con.disconnect() }
    }

    private fun openai(c: LlmCfg, sys: String, user: String): String {
        val url = when (c.provider) {
            "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"
            "glm" -> "https://api.z.ai/api/paas/v4/chat/completions"
            else -> "https://api.groq.com/openai/v1/chat/completions"
        }
        val body = JSONObject()
            .put("model", c.model)
            .put("temperature", 0.1)
            .put("max_tokens", 900)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        if (c.provider == "groq" || c.provider == "glm") body.put("response_format", JSONObject().put("type", "json_object"))
        // GLMは思考モードを切って速度とトークンを節約
        if (c.provider == "glm") body.put("thinking", JSONObject().put("type", "disabled"))
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
            .put("generationConfig", JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json"))
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

    fun parseJson(raw: String): JSONObject {
        val s = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace("```json", "").replace("```", "")
        val a = s.indexOf('{')
        if (a < 0) throw IOException("AIの応答を解析できません: ${s.take(80)}")
        // 最初の完全なJSONオブジェクトを括弧の対応で切り出す（後ろに余計な文があっても可）
        var depth = 0; var inStr = false; var esc = false
        for (i in a until s.length) {
            val ch = s[i]
            if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
            when (ch) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return JSONObject(s.substring(a, i + 1)) }
            }
        }
        val b = s.lastIndexOf('}')
        if (b > a) return JSONObject(s.substring(a, b + 1))
        throw IOException("AIの応答を解析できません: ${s.take(80)}")
    }
}
