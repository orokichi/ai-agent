package com.orokichi.agent

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Tasks.load()
        Notifs.channels(this)
    }
}

object Prefs {
    lateinit var sp: SharedPreferences
    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.getSharedPreferences("agent", Context.MODE_PRIVATE)
    }

    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    val providers = listOf("groq", "gemini", "openrouter")
    fun providerLabel(p: String) = when (p) {
        "gemini" -> "Gemini"; "openrouter" -> "OpenRouter"; else -> "Groq"
    }

    var provider: String
        get() = s("provider", "groq")
        set(v) = put("provider", v)

    fun key(p: String) = s("key_$p", "")
    fun setKey(p: String, v: String) = put("key_$p", v.trim())
    fun defaultModel(p: String) = when (p) {
        "gemini" -> "gemini-2.5-flash"
        "openrouter" -> "meta-llama/llama-3.3-70b-instruct:free"
        else -> "llama-3.3-70b-versatile"
    }
    fun model(p: String) = s("model_$p", defaultModel(p)).ifBlank { defaultModel(p) }
    fun setModel(p: String, v: String) = put("model_$p", v.trim())
    fun llm() = LlmCfg(provider, keys(provider).firstOrNull() ?: "", model(provider))
    fun keys(p: String) = key(p).split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    fun hasAnyKey() = providers.any { keys(it).isNotEmpty() }

    /** 優先プロバイダー → 残りの順に、登録済みの全キー */
    fun candidates(): List<LlmCfg> =
        (listOf(provider) + providers.filter { it != provider })
            .flatMap { p -> keys(p).map { LlmCfg(p, it, model(p)) } }

    var autoSwitch: Boolean
        get() = sp.getBoolean("autoSwitch", true)
        set(v) = sp.edit().putBoolean("autoSwitch", v).apply()

    var maxSteps: Int
        get() = sp.getInt("maxSteps", 25)
        set(v) = sp.edit().putInt("maxSteps", v).apply()

    /** "confirm" = 危険操作で一時停止して確認 / "strict" = 危険操作は即停止 */
    var safetyMode: String
        get() = s("safetyMode", "confirm")
        set(v) = put("safetyMode", v)

    var blockedPkgs: String
        get() = s("blockedPkgs", "")
        set(v) = put("blockedPkgs", v)

    var overlayStop: Boolean
        get() = sp.getBoolean("overlayStop", true)
        set(v) = sp.edit().putBoolean("overlayStop", v).apply()

    var cursor: Boolean
        get() = sp.getBoolean("cursor", true)
        set(v) = sp.edit().putBoolean("cursor", v).apply()

    var volumeStop: Boolean
        get() = sp.getBoolean("volumeStop", true)
        set(v) = sp.edit().putBoolean("volumeStop", v).apply()
}

object AgentState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("待機中")
    val log = MutableStateFlow<List<String>>(emptyList())
    val pending = MutableStateFlow<String?>(null)
    @Volatile var approval: CompletableDeferred<Boolean>? = null
    @Volatile var job: Job? = null

    fun add(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
        log.value = (log.value + "$t  $s").takeLast(300)
    }

    fun stop(reason: String = "ユーザーが停止") {
        approval?.complete(false)
        val j = job
        if (j != null && j.isActive) {
            add("■ 停止: $reason")
            status.value = "停止中…"
            j.cancel()
        }
    }

    fun answer(ok: Boolean) {
        approval?.complete(ok)
    }
}
