package com.orokichi.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.StatFs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class LocalModel(
    val id: String, val name: String, val repo: String, val file: String,
    val size: Long, val ctx: Int, val desc: String
)

/** Hugging Face の小型モデルを端末にダウンロードして LiteRT-LM で実行する */
object LocalLlm {
    val MODELS = listOf(
        LocalModel(
            "gemma4-e2b", "Gemma 4 E2B（おすすめ）", "litert-community/gemma-4-E2B-it-litert-lm",
            "gemma-4-E2B-it.litertlm", 2_588_147_712, 8192, "Google最新の端末向けモデル。精度が高くPixel 9aで快適"
        ),
        LocalModel(
            "qwen25-1.5b", "Qwen2.5 1.5B", "litert-community/Qwen2.5-1.5B-Instruct",
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", 1_597_922_856, 4096, "中くらいのサイズ。日本語もそこそこ"
        ),
        LocalModel(
            "qwen3-0.6b", "Qwen3 0.6B（軽量）", "litert-community/Qwen3-0.6B",
            "Qwen3-0.6B.litertlm", 613_722_264, 4096, "最軽量・高速。複雑な操作は苦手"
        ),
    )

    fun custom(): LocalModel? {
        val u = Prefs.customModelUrl.trim()
        if (!u.startsWith("https://") || !u.contains(".litertlm")) return null
        val name = u.substringBefore('?').substringAfterLast('/')
        return LocalModel("custom", "カスタム: $name", "", name, -1, 4096, u)
    }

    fun all() = MODELS + listOfNotNull(custom())
    fun url(m: LocalModel) = if (m.id == "custom") m.desc else "https://huggingface.co/${m.repo}/resolve/main/${m.file}?download=true"
    fun dir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    fun file(c: Context, m: LocalModel) = File(dir(c), m.file)
    fun part(c: Context, m: LocalModel) = File(dir(c), m.file + ".part")
    fun isReady(c: Context, m: LocalModel) = file(c, m).let { it.exists() && (m.size < 0 || it.length() == m.size) }
    fun selected(): LocalModel? = all().find { it.id == Prefs.localModel }
    fun readyModel(c: Context): LocalModel? = selected()?.takeIf { isReady(c, it) }
    fun usable(c: Context) = Prefs.localMode != "off" && readyModel(c) != null

    fun delete(c: Context, m: LocalModel) {
        if (engPath == file(c, m).path) releaseLater()
        file(c, m).delete(); part(c, m).delete()
    }

    // ---- ダウンロード状態 ----
    val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val error = MutableStateFlow<Map<String, String>>(emptyMap())
    val downloading get() = progress.value.keys

    fun startDownload(c: Context, m: LocalModel) {
        error.value = error.value - m.id
        c.startForegroundService(Intent(c, DownloadService::class.java).putExtra("id", m.id))
    }

    fun cancelDownload(c: Context) {
        c.startService(Intent(c, DownloadService::class.java).setAction("cancel"))
    }

    // ---- 推論 ----
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var engPath: String? = null
    private var engGpu = false

    private fun engineFor(c: Context, m: LocalModel): Engine {
        val path = file(c, m).path
        val gpu = Prefs.localGpu
        engine?.let { if (engPath == path && engGpu == gpu) return it }
        release()
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        AgentState.add("🧠 端末内モデルを読み込み中…（${m.name}）")
        fun make(useGpu: Boolean) = Engine(
            EngineConfig(
                modelPath = path,
                backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                maxNumTokens = m.ctx,
                cacheDir = c.cacheDir.path
            )
        ).also { it.initialize() }
        val e = try { make(gpu) } catch (t: Throwable) {
            if (!gpu) throw t
            AgentState.add("⚠ GPUで読み込めないためCPUで実行します")
            make(false)
        }
        engine = e; engPath = path; engGpu = gpu
        return e
    }

    /** 推論中でも安全に解放（推論が終わってから閉じる） */
    fun releaseLater() {
        CoroutineScope(Dispatchers.Default).launch { mutex.withLock { release() } }
    }

    fun release() {
        runCatching { engine?.close() }
        engine = null; engPath = null
    }

    suspend fun ask(c: Context, sys: String, user: String): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val m = readyModel(c) ?: throw IOException("端末内モデルがダウンロードされていません（設定タブ）")
            // 文脈長に合わせて入力を短くする
            val small = m.ctx <= 4096
            val s = if (small) sys.substringBefore("## Android 17").trim() else sys
            val u = if (user.length > (if (small) 3500 else 7000)) user.take(if (small) 3500 else 7000) + "\n…(truncated)" else user
            val e = engineFor(c, m)
            val cfg = ConversationConfig(
                systemInstruction = Contents.of(s),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2),
                maxOutputToken = 600
            )
            val out = e.createConversation(cfg).use { conv ->
                try { conv.sendMessage(u, thinkingConfig = ThinkingConfig(enableThinking = false)).toString() }
                catch (_: IllegalArgumentException) { conv.sendMessage(u).toString() }
            }
            out.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        }
    }
}

/** 大きなモデルを途中再開ありでダウンロードするフォアグラウンドサービス */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var currentId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun notif(title: String, pct: Int): Notification {
        nm().createNotificationChannel(NotificationChannel("dl", "モデルのダウンロード", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "dl")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(if (pct >= 0) "$pct%" else "準備中…")
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            job?.cancel()
            currentId?.let { LocalLlm.progress.value = LocalLlm.progress.value - it }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        val m = LocalLlm.all().find { it.id == intent?.getStringExtra("id") } ?: return START_NOT_STICKY
        startForeground(5, notif("${m.name} をダウンロード中", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive == true) return START_NOT_STICKY
        currentId = m.id
        LocalLlm.progress.value = LocalLlm.progress.value + (m.id to 0f)
        job = scope.launch {
            try {
                download(m)
                if (Prefs.localModel.isEmpty()) Prefs.localModel = m.id
                AgentState.add("✓ ${m.name} のダウンロード完了")
            } catch (e: Exception) {
                if (isActive) LocalLlm.error.value = LocalLlm.error.value + (m.id to (e.message ?: "失敗").take(120))
            } finally {
                LocalLlm.progress.value = LocalLlm.progress.value - m.id
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun download(m: LocalModel) {
        val part = LocalLlm.part(this, m)
        val dst = LocalLlm.file(this, m)
        val free = StatFs(filesDir.path).availableBytes
        if (m.size > 0 && free < m.size - part.length() + 300_000_000L)
            throw IOException("空き容量が足りません（約${(m.size / 1_000_000_000.0).let { "%.1f".format(it) }}GB必要）")
        var have = if (part.exists()) part.length() else 0L
        val con = URL(LocalLlm.url(m)).openConnection() as HttpURLConnection
        con.connectTimeout = 20_000; con.readTimeout = 60_000
        con.instanceFollowRedirects = true
        Prefs.hfToken.trim().takeIf { it.isNotEmpty() }?.let { con.setRequestProperty("Authorization", "Bearer $it") }
        if (have > 0) con.setRequestProperty("Range", "bytes=$have-")
        val code = con.responseCode
        if (code == 401 || code == 403) throw IOException("アクセスが拒否されました（ゲート付きモデルはHugging Faceで利用規約に同意し、トークンを入力）")
        if (code !in 200..299) throw IOException("HTTP $code")
        if (code == 200) have = 0
        val total = if (m.size > 0) m.size else (con.contentLengthLong.takeIf { it > 0 }?.plus(have) ?: -1)
        var lastPct = -1
        con.inputStream.use { inp ->
            FileOutputStream(part, have > 0).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw IOException("キャンセル")
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    have += n
                    if (total > 0) {
                        val f = have.toFloat() / total
                        val pct = (f * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            LocalLlm.progress.value = LocalLlm.progress.value + (m.id to f)
                            nm().notify(5, notif("${m.name} をダウンロード中", pct))
                        }
                    }
                }
            }
        }
        if (m.size > 0 && part.length() != m.size) throw IOException("ダウンロードが不完全です。もう一度押すと続きから再開します")
        if (!part.renameTo(dst)) throw IOException("保存に失敗しました")
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
}
