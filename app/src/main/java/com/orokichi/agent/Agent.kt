package com.orokichi.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException

class Agent(private val s: AgentService) {
    companion object {
        val SYSTEM = """
You are an expert Android operator controlling a real phone to complete the user's goal.
Each turn you receive: goal, current time, your plan and notes from earlier turns, recent actions with results, and the current screen as numbered elements:
[id] Class "label" flags @x,y   (flags: c=clickable e=editable s=scrollable ✓/☐=checkbox p=password; @x,y = center in pixels)
Reply with ONE JSON object only, no prose:
{"thought":"what you see and why this action (1-2 short sentences)","plan":"remaining steps, short","note":"facts to remember (results found etc.) or empty","action":"...", ...params, "risk":"low|high"}
Actions:
 tap{id} | long_press{id} | type{id,text} (replaces the field's text) | enter{id} (submit/search key) | scroll{id?,dir:"up|down|left|right"} (down = see content below) | swipe{x1,y1,x2,y2} | back | home | recents | notifications | quick_settings | open_app{name} | open_settings{page} (page: wifi internet bluetooth display sound volume battery apps notifications location storage date language nfc airplane data_usage dnd wallpaper settings) | wait{sec} | done{result,lesson}
On done, "lesson" = one short reusable tip you learned about operating the app(s) in this task (e.g. where a button is), or empty.
Strategy:
- Read the whole screen first; pick the element whose label best matches. Prefer ids over swipe coordinates.
- Launch apps with open_app (app name as shown on the home screen). Do not hunt for icons.
- To search: tap/type into the search field, then enter or tap the search button.
- If the target is not visible, scroll. If a popup/dialog blocks you, dismiss it (close/back) unless it is needed.
- If the screen did not change after an action, do something different. Never repeat the same failing action 3 times.
- When loading, use wait 2. When the goal is achieved, call done with the concrete result (e.g. the info found).
Safety: never enter passwords, PINs or card numbers. Do not buy, pay, send money, delete, post, send messages, call or change security settings unless the goal explicitly asks. Set risk "high" for any irreversible action. If unsafe or impossible, done with the reason.
Write thought, plan, note and result in the user's language (Japanese if the goal is Japanese).
""".trimIndent() + "\n\n" + Knowledge.ANDROID17
    }

    private val cur get() = s.cursor

    suspend fun run(goal: String, deadline: Long?): String {
        val max = Prefs.maxSteps
        val history = ArrayDeque<String>()
        var plan = ""
        var notes = ""
        var prevScreen = ""
        var lastKey = ""
        var repeat = 0
        val visited = linkedSetOf<String>()
        if (s.readScreen().pkg == Safety.OWN) {
            s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            delay(900)
        }
        for (step in 1..max) {
            if (deadline != null && System.currentTimeMillis() > deadline) return "指定の時間帯を過ぎたため終了しました"
            waitUnlocked()
            val sc = s.readScreen()
            if (step > 1 && sc.text == prevScreen && history.isNotEmpty()) {
                history[history.lastIndex] = history.last() + " (screen unchanged!)"
            }
            prevScreen = sc.text
            if (sc.pkg.isNotEmpty()) visited += sc.pkg
            AgentState.status.value = "考え中… ($step/$max)"
            cur.think("考え中… $step/$max", plan.ifEmpty { goal })
            val user = buildString {
                append("Goal: ").append(goal)
                append("\nNow: ").append(java.text.SimpleDateFormat("yyyy-MM-dd(E) HH:mm", java.util.Locale.JAPAN).format(java.util.Date()))
                append("\nStep ").append(step).append('/').append(max)
                if (plan.isNotEmpty()) append("\nYour plan: ").append(plan)
                if (notes.isNotEmpty()) append("\nYour notes: ").append(notes)
                append("\nRecent actions: ").append(if (history.isEmpty()) "none" else history.joinToString(" | "))
                if (repeat >= 2) append("\nWARNING: you repeated the same action. Try a different approach.")
                append(Knowledge.forPrompt(sc.pkg, visited))
                append("\nApp: ").append(sc.pkg.ifEmpty { "unknown" })
                append("\nScreen ").append(s.screenSize()).append(":\n").append(sc.text.ifEmpty { "(no readable elements)" })
            }
            val a = decide(user)
            if (a == null) { history.addLast("invalid reply"); trim(history); continue }
            val act = a.optString("action")
            val thought = a.optString("thought")
            a.optString("plan").takeIf { it.isNotBlank() }?.let { plan = it.take(300) }
            a.optString("note").takeIf { it.isNotBlank() }?.let { notes = (notes + " / " + it).takeLast(500).trimStart(' ', '/') }
            if (thought.isNotBlank()) AgentState.add("💭 $thought")
            if (act == "done") {
                val r = a.optString("result", "完了しました")
                val lesson = a.optString("lesson")
                if (Prefs.learning && lesson.isNotBlank()) {
                    Knowledge.learn(visited.lastOrNull { it != Safety.OWN && !it.contains("launcher") } ?: sc.pkg, lesson)
                    AgentState.add("📘 学習: $lesson")
                }
                cur.think("完了", r)
                delay(1500)
                return r
            }

            val el = if (a.has("id")) sc.els.getOrNull(a.optInt("id", -1)) else null
            val what = describe(act, a, el)
            cur.think(what, thought)

            val v = Safety.check(a, el, sc)
            when (v.risk) {
                Risk.BLOCK -> {
                    cur.think("⛔ 安全装置が作動", v.reason)
                    AgentState.add("⛔ ブロック: ${v.reason}")
                    Notifs.alert(s, "安全装置が作動しました", v.reason)
                    delay(1500)
                    return "安全装置により停止: ${v.reason}"
                }
                Risk.CONFIRM -> {
                    if (Prefs.safetyMode == "strict") {
                        cur.think("⛔ 危険な操作のため停止", v.reason)
                        AgentState.add("⛔ 危険な操作のため停止: ${v.reason}")
                        Notifs.alert(s, "安全装置が作動しました", v.reason)
                        delay(1500)
                        return "危険な操作のため停止: ${v.reason}"
                    }
                    el?.let { cur.moveTo(it.rect.exactCenterX(), it.rect.exactCenterY(), "⚠ " + what) }
                    cur.think("⚠ 確認待ち（通知から許可）", v.reason)
                    AgentState.add("⚠ 確認待ち: ${v.reason}")
                    if (!askUser(v.reason)) return "許可されなかったため停止: ${v.reason}"
                    AgentState.add("✓ 許可されました")
                }
                Risk.OK -> {}
            }

            // 仮想カーソルを操作点へ
            when {
                el != null -> cur.moveTo(el.rect.exactCenterX(), el.rect.exactCenterY(), what)
                act == "swipe" -> cur.moveTo(a.optDouble("x1", 0.0).toFloat(), a.optDouble("y1", 0.0).toFloat(), what)
            }
            if (act in setOf("tap", "long_press", "enter")) cur.pulse()

            AgentState.status.value = "実行中… ($step/$max)"
            val res = exec(act, a, el)
            AgentState.add("→ $what : $res")
            val key = act + a.optInt("id", -1) + a.optString("text")
            repeat = if (key == lastKey) repeat + 1 else 0
            lastKey = key
            history.addLast("$what=$res"); trim(history)
            if (act != "wait") delay(1200)
        }
        return "最大ステップ数（$max）に達したため停止しました"
    }

    /** 応答をJSONとして解析。失敗したら1回だけ言い直させる */
    private suspend fun decide(user: String): JSONObject? {
        val first = Llm.ask(SYSTEM, user)
        return try { Llm.parseJson(first) } catch (_: Exception) {
            try { Llm.parseJson(Llm.ask(SYSTEM, user + "\n\nYour previous reply was not valid JSON. Reply with ONE JSON object only.")) }
            catch (_: Exception) { null }
        }
    }

    private fun describe(act: String, a: JSONObject, el: El?): String {
        val l = el?.label?.take(18)?.let { "「$it」" } ?: ""
        return when (act) {
            "tap" -> "${l}をタップ"
            "long_press" -> "${l}を長押し"
            "type" -> "${l}に入力: ${a.optString("text").take(24)}"
            "enter" -> "${l}で確定"
            "scroll" -> "スクロール ${when (a.optString("dir", "down")) { "up" -> "↑"; "left" -> "←"; "right" -> "→"; else -> "↓" }}"
            "swipe" -> "スワイプ"
            "back" -> "戻る"
            "home" -> "ホームへ"
            "recents" -> "最近のアプリ"
            "notifications" -> "通知を開く"
            "quick_settings" -> "クイック設定を開く"
            "open_settings" -> "設定「${a.optString("page")}」を開く"
            "open_app" -> "「${a.optString("name")}」を起動"
            "wait" -> "待機 ${a.optDouble("sec", 2.0).toInt()}秒"
            else -> act
        }
    }

    private fun trim(h: ArrayDeque<String>) { while (h.size > 8) h.removeFirst() }

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
        "quick_settings" -> ok(s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS))
        "open_settings" -> Knowledge.openSettings(s, a.optString("page"))
        "wait" -> { delay((a.optDouble("sec", 2.0).coerceIn(0.5, 15.0) * 1000).toLong()); "ok" }
        else -> "不明なアクション"
    }

    private fun ok(b: Boolean) = if (b) "ok" else "失敗"
}
