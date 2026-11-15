package com.orokichi.agent

import org.json.JSONObject

enum class Risk { OK, CONFIRM, BLOCK }
data class Verdict(val risk: Risk, val reason: String = "")

/**
 * 安全装置。AIが選んだ操作を実行前に検査する。
 * BLOCK = 常に即停止 / CONFIRM = 一時停止してユーザーに確認（厳格モードでは即停止）
 */
object Safety {
    const val OWN = "com.orokichi.agent"
    private val INSTALLERS = setOf(
        "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller"
    )
    private val HARD = listOf(
        "アンインストール", "uninstall", "初期化", "factory reset", "工場出荷", "リセット",
        "送金", "振込", "振り込み", "wire transfer", "アカウントを削除", "アカウント削除",
        "delete account", "すべて削除", "全て削除", "erase all", "ユーザー補助", "accessibility",
        "デバイス管理", "device admin", "開発者向けオプション", "developer options", "画面ロック", "screen lock",
        "パスワード", "password", "暗証番号", "pin"
    )
    private val JA_CONFIRM = listOf(
        "購入", "買う", "支払", "決済", "注文", "お会計", "レジ", "送信", "投稿", "ツイート", "ポスト",
        "削除", "消去", "ゴミ箱", "発信", "通話", "電話をかける", "許可", "インストール", "予約", "契約",
        "登録", "ログアウト", "共有", "転送", "確定", "同意", "承認", "申し込", "申込", "チャージ", "解約", "退会"
    )
    private val EN_CONFIRM = Regex(
        "\\b(pay|buy|purchase|checkout|order|place order|send|post|tweet|delete|remove|erase|trash|call|dial|allow|install|book|reserve|subscribe|sign up|register|sign out|log out|share|forward|confirm|agree|approve|accept|submit|transfer)\\b",
        RegexOption.IGNORE_CASE
    )
    private val CARD = Regex("(?:\\d[ -]?){13,19}")
    private val NAV = setOf("back", "home", "recents", "notifications", "wait", "scroll", "done")

    fun userBlocked(): Set<String> =
        Prefs.blockedPkgs.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun isBlockedPkg(pkg: String) = pkg == OWN || pkg in INSTALLERS || pkg in userBlocked()

    fun check(a: JSONObject, el: El?, sc: Screen): Verdict {
        val act = a.optString("action")
        if (act in NAV) return Verdict(Risk.OK)
        if (act == "open_app") return Verdict(Risk.OK) // 起動先の保護はopenApp内で検査

        if (isBlockedPkg(sc.pkg)) return Verdict(Risk.BLOCK, "保護対象アプリ（${sc.pkg}）内での操作")

        val label = el?.label ?: ""
        val text = if (act == "type") a.optString("text") else ""
        if (act == "type") {
            if (el?.node?.isPassword == true) return Verdict(Risk.BLOCK, "パスワード欄への入力")
            if (CARD.containsMatchIn(text)) return Verdict(Risk.BLOCK, "カード番号らしき数字の入力")
        }
        val low = label.lowercase()
        HARD.firstOrNull { w ->
            val k = w.lowercase()
            if (k.all { it in 'a'..'z' || it == ' ' }) Regex("\\b${Regex.escape(k)}\\b").containsMatchIn(low) else low.contains(k)
        }?.let { return Verdict(Risk.BLOCK, "「$label」は保護対象の操作") }

        if (act in setOf("tap", "long_press", "enter")) {
            if (JA_CONFIRM.any { label.contains(it) } || EN_CONFIRM.containsMatchIn(label))
                return Verdict(Risk.CONFIRM, "「$label」をタップしようとしています")
        }
        if (act == "swipe" && el == null && a.optString("risk") == "high")
            return Verdict(Risk.CONFIRM, "高リスクのスワイプ操作")
        if (a.optString("risk").equals("high", true))
            return Verdict(Risk.CONFIRM, "AIが取り消せない操作と判断: ${a.optString("thought").take(60)}")
        return Verdict(Risk.OK)
    }
}
