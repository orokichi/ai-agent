package com.orokichi.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class El(val id: Int, val node: AccessibilityNodeInfo, val rect: Rect, val label: String)
class Screen(val pkg: String, val text: String, val els: List<El>)

class AgentService : AccessibilityService() {
    companion object {
        @Volatile var instance: AgentService? = null
        fun isEnabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return s.contains("${ctx.packageName}/")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: View? = null
    private var volUp = false
    private var volDown = false

    override fun onServiceConnected() {
        Prefs.init(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        AgentState.stop("ユーザー補助が無効になりました")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AgentState.stop("サービス終了")
        instance = null
        hideOverlay()
        scope.cancel()
        super.onDestroy()
    }

    /** 音量↑と↓の同時押しで緊急停止 */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> volUp = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_VOLUME_DOWN -> volDown = event.action == KeyEvent.ACTION_DOWN
            else -> return false
        }
        if (Prefs.volumeStop && volUp && volDown && AgentState.running.value) {
            AgentState.stop("音量キー同時押し")
            return true
        }
        return false
    }

    fun startRun(goal: String, deadline: Long?, source: String): Boolean {
        if (AgentState.running.value) return false
        AgentState.running.value = true
        AgentState.job = scope.launch {
            showOverlay()
            Notifs.ongoing(this@AgentService, goal)
            val wl = wake()
            try {
                AgentState.add("▶ [$source] $goal")
                val r = Agent(this@AgentService).run(goal, deadline)
                AgentState.add("✓ $r")
                Notifs.result(this@AgentService, "$source: 終了", r)
            } catch (e: CancellationException) {
                Notifs.result(this@AgentService, "$source: 停止しました", "エージェントを停止しました")
                throw e
            } catch (e: Exception) {
                AgentState.add("✕ ${e.message}")
                Notifs.result(this@AgentService, "$source: エラー", e.message ?: "不明なエラー")
            } finally {
                AgentState.running.value = false
                AgentState.pending.value = null
                AgentState.status.value = "待機中"
                hideOverlay()
                Notifs.cancel(this@AgentService, Notifs.ID_RUN)
                if (wl.isHeld) wl.release()
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun wake(): PowerManager.WakeLock {
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "agent:run")
        wl.acquire(30 * 60 * 1000L)
        return wl
    }

    fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun showOverlay() {
        if (!Prefs.overlayStop || overlay != null) return
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = "■  停止"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 40 * d; setColor(0xFFC62828.toInt()) }
            elevation = 8 * d
            setOnClickListener { AgentState.stop("画面上の停止ボタン") }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = (12 * d).toInt(); y = (90 * d).toInt() }
        runCatching { getSystemService(WindowManager::class.java).addView(tv, lp); overlay = tv }
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
    }

    /* ---------- 画面の読み取り ---------- */

    fun readScreen(): Screen {
        val root = rootInActiveWindow ?: return Screen("", "", emptyList())
        val els = ArrayList<El>()
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || els.size >= 120) return
            if (n.isVisibleToUser) {
                val raw = n.text ?: n.contentDescription ?: n.hintText ?: ""
                val label = raw.toString().replace('\n', ' ').trim().take(50)
                val interactive = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || n.isLongClickable
                if (label.isNotEmpty() || interactive) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) {
                        val id = els.size
                        els += El(id, n, r, label)
                        val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
                        sb.append('[').append(id).append("] ").append(cls)
                        if (label.isNotEmpty()) sb.append(" \"").append(label).append('"')
                        val f = StringBuilder()
                        if (n.isClickable) f.append('c')
                        if (n.isEditable) f.append('e')
                        if (n.isScrollable) f.append('s')
                        if (n.isCheckable) f.append(if (n.isChecked) "✓" else "☐")
                        if (n.isPassword) f.append('p')
                        if (f.isNotEmpty()) sb.append(' ').append(f)
                        sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return Screen(root.packageName?.toString() ?: "", sb.toString(), els)
    }

    /* ---------- 操作 ---------- */

    fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
        }
        return false
    }

    suspend fun tap(x: Float, y: Float, long: Boolean = false) =
        gesture(Path().apply { moveTo(x, y) }, if (long) 700 else 60)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 400)

    private suspend fun gesture(p: Path, dur: Long): Boolean = suspendCancellableCoroutine { c ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (c.isActive) c.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (c.isActive) c.resume(false) }
        }, null)
        if (!ok && c.isActive) c.resume(false)
    }

    fun typeText(el: El?, text: String): String {
        var n = el?.node
        if (n != null && !n.isEditable) {
            clickNode(n)
            n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }
        if (n == null) n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (n == null) return "入力欄なし"
        if (n.isPassword) return "パスワード欄は入力不可"
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) "ok" else "失敗"
    }

    fun enter(el: El?): String {
        val n = el?.node ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "入力欄なし"
        return if (n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) "ok" else "失敗"
    }

    suspend fun scroll(el: El?, dir: String): String {
        if (el != null) {
            var c: AccessibilityNodeInfo? = el.node
            while (c != null && !c.isScrollable) c = c.parent
            if (c != null) {
                val act = if (dir == "down" || dir == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (c.performAction(act)) return "ok"
            }
        }
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val ok = when (dir) {
            "up" -> swipe(w / 2, h * 0.3f, w / 2, h * 0.7f)
            "left" -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
            "right" -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            else -> swipe(w / 2, h * 0.7f, w / 2, h * 0.3f)
        }
        return if (ok) "ok" else "失敗"
    }

    fun openApp(name: String): String {
        val pm = packageManager
        val acts = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = name.lowercase().trim()
        if (q.isEmpty()) return "アプリ名なし"
        val hit = acts.firstOrNull { it.loadLabel(pm).toString().lowercase() == q || it.activityInfo.packageName == q }
            ?: acts.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "アプリが見つかりません"
        val pkg = hit.activityInfo.packageName
        if (Safety.isBlockedPkg(pkg)) return "保護対象アプリのため起動しません"
        val i = pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return "起動できません"
        startActivity(i)
        return "ok"
    }
}
