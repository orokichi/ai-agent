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

        /** 端末内の小型モデル向け：短く、例つき、フラットなJSON */
        val SYSTEM_LOCAL = """
You control an Android phone (Japanese UI) step by step to reach the user's goal.
You get the goal, recent actions and the current screen as lines: [id] Class "label" flags @x,y (c=clickable e=editable s=scrollable).
Reply with exactly ONE flat JSON object and nothing else. Keys: thought, action, plus the parameters of that action at the top level.
Actions and parameters:
- open_app: name   (launch an app by its name, e.g. "設定", "Chrome", "時計")
- tap: id          (id = number in [ ] on the screen)
- long_press: id
- type: id, text   (write text into an editable field)
- enter: id        (press search/enter on a field)
- scroll: dir      ("down" shows more below, or "up")
- back | home | notifications | quick_settings
- open_settings: page (wifi bluetooth display sound battery apps notifications location storage)
- wait: sec
- done: result     (when the goal is reached or impossible; result = answer in Japanese)
Examples:
{"thought":"設定アプリを開く","action":"open_app","name":"設定"}
{"thought":"「ネットワークとインターネット」を押す","action":"tap","id":7}
{"thought":"検索欄に入力する","action":"type","id":3,"text":"岡山 天気"}
{"thought":"下に続きがある","action":"scroll","dir":"down"}
{"thought":"目的を達成した","action":"done","result":"Wi-Fiをオンにしました"}
Rules: one action per reply. Use only ids that exist on the current screen. If the screen did not change, try something else. Never enter passwords or pay. Write thought and result in Japanese.
""".trimIndent()

        val ACTIONS = setOf(
            "tap", "long_press", "type", "enter", "scroll", "swipe", "back", "home", "recents",
            "notifications", "quick_settings", "open_app", "open_settings", "wait", "done"
        )
        private val ALIAS = mapOf(
            "click" to "tap", "press" to "tap", "touch" to "tap", "select" to "tap", "tap_element" to "tap", "click_element" to "tap",
            "longpress" to "long_press", "long_click" to "long_press", "long_tap" to "long_press", "hold" to "long_press",
            "input" to "type", "type_text" to "type", "input_text" to "type", "set_text" to "type", "write" to "type", "fill" to "type",
            "submit" to "enter", "search" to "enter", "press_enter" to "enter",
            "scroll_down" to "scroll", "scroll_up" to "scroll", "swipe_up" to "scroll", "swipe_down" to "scroll",
            "go_back" to "back", "press_back" to "back", "navigate_back" to "back",
            "go_home" to "home", "press_home" to "home",
            "launch" to "open_app", "launch_app" to "open_app", "open" to "open_app", "start_app" to "open_app", "openapp" to "open_app",
            "settings" to "open_settings", "open_setting" to "open_settings",
            "open_notifications" to "notifications", "open_quick_settings" to "quick_settings",
            "sleep" to "wait", "pause" to "wait",
            "finish" to "done", "complete" to "done", "finished" to "done", "stop" to "done", "answer" to "done", "end" to "done"
        )

        /** JSON Schema（端末内モデルの制約付き生成用） */
        val SCHEMA = """
{"type":"object","properties":{
 "thought":{"type":"string"},
 "action":{"type":"string","enum":["tap","long_press","type","enter","scroll","back","home","notifications","quick_settings","open_app","open_settings","wait","done"]},
 "id":{"type":"integer"},"text":{"type":"string"},"name":{"type":"string"},"page":{"type":"string"},
 "dir":{"type":"string","enum":["up","down","left","right"]},"sec":{"type":"number"},"result":{"type":"string"}
},"required":["thought","action"]}
""".trimIndent()

        /** Groq strict モード用（全項目必須・不要な項目は null） */
        val STRICT_SCHEMA: JSONObject by lazy {
            fun t(vararg ty: String) = JSONObject().put("type", org.json.JSONArray(ty.toList()))
            val props = JSONObject()
                .put("thought", JSONObject().put("type", "string").put("description", "短い日本語で、画面の状況と次の一手の理由"))
                .put("plan", t("string", "null")).put("note", t("string", "null"))
                .put("action", JSONObject().put("type", "string").put("enum", org.json.JSONArray(ACTIONS.toList())))
                .put("id", t("integer", "null")).put("text", t("string", "null"))
                .put("dir", t("string", "null"))
                .put("name", t("string", "null")).put("page", t("string", "null"))
                .put("sec", t("number", "null"))
                .put("x1", t("number", "null")).put("y1", t("number", "null")).put("x2", t("number", "null")).put("y2", t("number", "null"))
                .put("result", t("string", "null")).put("lesson", t("string", "null"))
            JSONObject().put("type", "object").put("properties", props)
                .put("required", org.json.JSONArray(props.keys().asSequence().toList()))
                .put("additionalProperties", false)
        }

        private fun idOf(v: Any?): Int? = when (v) {
            is Number -> v.toInt()
            is String -> Regex("\\d+").find(v)?.value?.toIntOrNull()
            else -> null
        }

        /** モデルごとの書き方の揺れを吸収して、実行できる形にそろえる */
        fun normalize(src: JSONObject): JSONObject {
            val a = JSONObject(src.toString())
            a.keys().asSequence().toList().forEach { if (a.isNull(it)) a.remove(it) }
            // パラメータが入れ子の場合は展開
            for (k in listOf("params", "parameters", "args", "arguments", "input")) {
                val o = a.optJSONObject(k) ?: continue
                o.keys().forEach { if (!a.has(it) || it == "id") a.put(it, o.get(it)) }
            }
            var act: Any? = a.opt("action") ?: a.opt("type").takeIf { !a.has("text") || a.opt("type") !is String }
            if (act == null) for (k in listOf("action_type", "command", "cmd", "tool", "function")) if (a.has(k)) { act = a.opt(k); break }
            // {"action":{"tap":{"id":3}}} / {"action":{"name":"tap","id":3}}
            if (act is JSONObject) {
                val o = act
                val n = o.optString("name").ifEmpty { o.optString("type").ifEmpty { o.optString("action") } }
                if (n.isNotEmpty()) { o.keys().forEach { if (it !in setOf("name", "type", "action")) a.put(it, o.get(it)) }; act = n }
                else o.keys().asSequence().firstOrNull()?.let { k -> o.optJSONObject(k)?.let { p -> p.keys().forEach { a.put(it, p.get(it)) } }; act = k }
            }
            var name = (act as? String ?: "").trim()
            // "tap{id:5}" / "tap(5)" / "tap 5"
            Regex("^([A-Za-z_]+)\\s*[({\\[ ]\\s*(.*?)[)}\\]]?$").find(name)?.let { m ->
                name = m.groupValues[1]
                val inner = m.groupValues[2]
                Regex("([A-Za-z_]+)\\s*[:=]\\s*\"?([^,\"}]+)\"?").findAll(inner).forEach { kv -> if (!a.has(kv.groupValues[1]) || kv.groupValues[1] == "id") a.put(kv.groupValues[1], kv.groupValues[2].trim()) }
                if (!a.has("id")) idOf(inner)?.let { a.put("id", it) }
            }
            name = name.lowercase().replace('-', '_').replace(' ', '_')
            when (name) {
                "scroll_down", "swipe_up" -> if (!a.has("dir")) a.put("dir", "down")
                "scroll_up", "swipe_down" -> if (!a.has("dir")) a.put("dir", "up")
            }
            name = ALIAS[name] ?: name
            a.put("action", name)
            // id のゆれ
            for (k in listOf("id", "element", "element_id", "elementId", "index", "target", "node")) {
                if (a.has(k)) { idOf(a.opt(k))?.let { a.put("id", it) }; if (a.has("id") && a.opt("id") is Int) break }
            }
            if (a.has("id") && a.opt("id") !is Int) idOf(a.opt("id"))?.let { a.put("id", it) } ?: a.remove("id")
            // 文字列パラメータのゆれ
            if (name == "open_app" && a.optString("name").isEmpty())
                listOf("app", "app_name", "appName", "package", "target", "text").firstNotNullOfOrNull { a.optString(it).takeIf { v -> v.isNotBlank() } }?.let { a.put("name", it) }
            if (name == "type" && a.optString("text").isEmpty())
                listOf("value", "content", "input_text", "string").firstNotNullOfOrNull { a.optString(it).takeIf { v -> v.isNotBlank() } }?.let { a.put("text", it) }
            if (name == "scroll") {
                val d = a.optString("dir").ifEmpty { a.optString("direction") }.lowercase()
                a.put("dir", if (d in setOf("up", "down", "left", "right")) d else "down")
            }
            if (name == "open_settings" && a.optString("page").isEmpty()) a.put("page", a.optString("name").ifEmpty { "settings" })
            if (name == "done" && a.optString("result").isEmpty())
                listOf("answer", "message", "text", "thought").firstNotNullOfOrNull { a.optString(it).takeIf { v -> v.isNotBlank() } }?.let { a.put("result", it) }
            if (a.optString("thought").isEmpty()) listOf("reason", "reasoning", "thinking", "explanation").firstNotNullOfOrNull { a.optString(it).takeIf { v -> v.isNotBlank() } }?.let { a.put("thought", it) }
            return a
        }
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
            s.waitIdle(min = 400)
        }
        var fails = 0
        for (step in 1..max) {
            if (deadline != null && System.currentTimeMillis() > deadline) return "指定の時間帯を過ぎたため終了しました"
            waitUnlocked()
            val sc = s.readScreenStable()
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
                append("\nScreen ").append(s.screenSize()).append(":\n").append(screenFor(sc.text))
                append("\n\nGoal (again): ").append(goal).append("\nReply with ONE JSON object for the next action.")
            }
            val a = try { decide(user) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                if (++fails >= 3) throw e
                AgentState.add("⚠ AIエラー、再試行: ${e.message?.take(60)}")
                delay(800); null
            }
            if (a != null) fails = 0
            if (a == null) { history.addLast("invalid reply (reply with ONE JSON object)"); trim(history); continue }
            val act = a.optString("action")
            if (act !in ACTIONS) {
                AgentState.add("⚠ 不明なアクション「$act」")
                history.addLast("unknown action \"$act\" (use one of: ${ACTIONS.joinToString(" ")})"); trim(history)
                continue
            }
            if (act in setOf("tap", "long_press") && (!a.has("id") || sc.els.getOrNull(a.optInt("id", -1)) == null)) {
                AgentState.add("⚠ 存在しないid: ${a.opt("id")}")
                history.addLast("$act failed: id ${a.opt("id")} does not exist on this screen, choose an id from the list"); trim(history)
                continue
            }
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
            if (act != "wait") s.waitIdle(min = if (act in setOf("open_app", "open_settings", "back", "home")) 500 else 250)
        }
        return "最大ステップ数（$max）に達したため停止しました"
    }

    /** 応答をJSONとして解析。失敗したら1回だけ言い直させる */
    private suspend fun decide(user: String): JSONObject? {
        val first = Llm.ask(SYSTEM, user, SYSTEM_LOCAL)
        return try { normalize(Llm.parseJson(first)) } catch (_: Exception) {
            AgentState.add("🧾 解析できない応答: ${first.take(120).replace('\n', ' ')}")
            val again = Llm.ask(SYSTEM, user + "\n\nYour previous reply was not valid JSON. Reply with ONE JSON object only, like {\"thought\":\"...\",\"action\":\"tap\",\"id\":3}", SYSTEM_LOCAL)
            try { normalize(Llm.parseJson(again)) }
            catch (_: Exception) { AgentState.add("🧾 解析できない応答: ${again.take(120).replace('\n', ' ')}"); null }
        }
    }

    /** 端末内モデルは文脈が短いので、画面情報を要素単位で切り詰める（ゴールが切れないように） */
    private fun screenFor(text: String): String {
        if (text.isEmpty()) return "(no readable elements)"
        val m = if (Prefs.localMode != "off") LocalLlm.readyModel(s) else null
        val limit = when {
            m != null -> if (m.ctx <= 4096) 1500 else 4000
            Prefs.provider == "groq" -> 5000 // Groq無料枠はTPMが小さいので画面情報を節約
            else -> 12000
        }
        if (text.length <= limit) return text
        val out = StringBuilder()
        for (line in text.lineSequence()) {
            if (out.length + line.length + 1 > limit) { out.append("…(more elements: scroll to see)\n"); break }
            out.append(line).append('\n')
        }
        return out.toString()
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
