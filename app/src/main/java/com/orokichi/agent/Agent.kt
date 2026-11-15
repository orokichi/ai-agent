package com.orokichi.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException

class Agent(private val s: AgentService) {
    companion object {
        val SYSTEM = """
You operate an Android phone to reach the user's goal. Each turn you get the goal, recent actions and the current screen as numbered elements: [id] Class "label" flags @x,y (flags: c=clickable e=editable s=scrollable ✓/☐=checkbox p=password).
Reply with ONE JSON object only:
{"thought":"very short","action":"...", ...params, "risk":"low|high"}
Actions: tap{id} | long_press{id} | type{id,text} | enter{id} | scroll{id?,dir:"up|down|left|right"} | swipe{x1,y1,x2,y2} | back | home | recents | notifications | open_app{name} | wait{sec} | done{result}
Rules: one action per reply. Use open_app to launch apps. Never enter passwords, PINs or card numbers. Do not buy, pay, send money, delete data, post, send messages, call, or change security settings unless the goal explicitly asks. Set risk "high" for anything irreversible. If the goal is done, impossible, or unsafe, use done with a short result. Write thought and result in the user's language.
""".trimIndent()
    }

    suspend fun run(goal: String, deadline: Long?): String {
        val max = Prefs.maxSteps
        val history = ArrayDeque<String>()
        if (s.readScreen().pkg == Safety.OWN) {
            s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            delay(900)
        }
        for (step in 1..max) {
            if (deadline != null && System.currentTimeMillis() > deadline) return "指定の時間帯を過ぎたため終了しました"
            waitUnlocked()
            val sc = s.readScreen()
            AgentState.status.value = "考え中… ($step/$max)"
            val user = buildString {
                append("Goal: ").append(goal)
                append("\nStep ").append(step).append('/').append(max)
                append("\nRecent: ").append(if (history.isEmpty()) "none" else history.joinToString(" | "))
                append("\nApp: ").append(sc.pkg.ifEmpty { "unknown" })
                append("\nScreen:\n").append(sc.text.ifEmpty { "(no readable elements)" })
            }
            val a: JSONObject = try {
                Llm.parseJson(Llm.ask(SYSTEM, user))
            } catch (e: IOException) {
                if (e.message?.startsWith("AIの応答") == true) {
                    history.addLast("invalid JSON reply"); trim(history); continue
                }
                throw e
            }
            val act = a.optString("action")
            a.optString("thought").takeIf { it.isNotBlank() }?.let { AgentState.add("💭 $it") }
            if (act == "done") return a.optString("result", "完了しました")

            val el = if (a.has("id")) sc.els.getOrNull(a.optInt("id", -1)) else null
            val v = Safety.check(a, el, sc)
            when (v.risk) {
                Risk.BLOCK -> {
                    AgentState.add("⛔ ブロック: ${v.reason}")
                    Notifs.alert(s, "安全装置が作動しました", v.reason)
                    return "安全装置により停止: ${v.reason}"
                }
                Risk.CONFIRM -> {
                    if (Prefs.safetyMode == "strict") {
                        AgentState.add("⛔ 危険な操作のため停止: ${v.reason}")
                        Notifs.alert(s, "安全装置が作動しました", v.reason)
                        return "危険な操作のため停止: ${v.reason}"
                    }
                    AgentState.add("⚠ 確認待ち: ${v.reason}")
                    if (!askUser(v.reason)) return "許可されなかったため停止: ${v.reason}"
                    AgentState.add("✓ 許可されました")
                }
                Risk.OK -> {}
            }

            AgentState.status.value = "実行中… ($step/$max)"
            val res = exec(act, a, el)
            val what = buildString {
                append(act)
                el?.label?.takeIf { it.isNotEmpty() }?.let { append(" \"").append(it.take(25)).append('"') }
                if (act == "type") append(" '").append(a.optString("text").take(25)).append('\'')
                if (act == "open_app") append(' ').append(a.optString("name"))
                if (act == "scroll") append(' ').append(a.optString("dir", "down"))
            }
            AgentState.add("→ $what : $res")
            history.addLast("$what=$res"); trim(history)
            if (act != "wait") delay(1300)
        }
        return "最大ステップ数（$max）に達したため停止しました"
    }

    private fun trim(h: ArrayDeque<String>) { while (h.size > 6) h.removeFirst() }

    private suspend fun waitUnlocked() {
        if (!s.isLocked()) return
        AgentState.status.value = "画面ロックの解除待ち…"
        Notifs.alert(s, "ロック解除が必要です", "解除するとタスクを続行します（60秒）")
        repeat(60) { delay(1000); if (!s.isLocked()) return }
        throw IOException("画面ロック中のため実行できませんでした")
    }

    private suspend fun askUser(reason: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        AgentState.approval = d
        AgentState.pending.value = reason
        AgentState.status.value = "確認待ち"
        Notifs.approval(s, reason)
        return try {
            withTimeoutOrNull(90_000) { d.await() } ?: false
        } finally {
            AgentState.pending.value = null
            AgentState.approval = null
            Notifs.cancel(s, Notifs.ID_ASK)
        }
    }

    private suspend fun exec(act: String, a: JSONObject, el: El?): String = when (act) {
        "tap" -> when {
            el == null -> "idが無効"
            s.clickNode(el.node) -> "ok"
            s.tap(el.rect.exactCenterX(), el.rect.exactCenterY()) -> "ok"
            else -> "失敗"
        }
        "long_press" -> if (el == null) "idが無効" else if (s.tap(el.rect.exactCenterX(), el.rect.exactCenterY(), true)) "ok" else "失敗"
        "type" -> s.typeText(el, a.optString("text"))
        "enter" -> s.enter(el)
        "scroll" -> s.scroll(el, a.optString("dir", "down"))
        "swipe" -> {
            val v = listOf("x1", "y1", "x2", "y2").map { a.optDouble(it, Double.NaN) }
            if (v.any { it.isNaN() }) "座標が無効"
            else if (s.swipe(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat())) "ok" else "失敗"
        }
        "back" -> ok(s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        "home" -> ok(s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        "recents" -> ok(s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS))
        "notifications" -> ok(s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
        "open_app" -> s.openApp(a.optString("name"))
        "wait" -> { delay((a.optDouble("sec", 2.0).coerceIn(0.5, 15.0) * 1000).toLong()); "ok" }
        else -> "不明なアクション"
    }

    private fun ok(b: Boolean) = if (b) "ok" else "失敗"
}
