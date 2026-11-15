package com.orokichi.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Android 17（Pixel・日本語UI）の基本操作知識 ＋ 実行から学んだコツ */
object Knowledge {
    val ANDROID17 = """
## Android 17 (Pixel, Japanese UI) basics
Navigation: use actions back/home/recents. notifications = pull down shade once; quick_settings = full Quick Settings (Wi-Fi「インターネット」, Bluetooth, 機内モード, ライト, 画面の回転, 着信音/マナー, 明るさスライダー; QPR2 adds keyboard switcher and brightness override). Tiles: tap = toggle, long_press = open its settings.
Home screen: swipe up from the bottom (or scroll up on home) opens the app drawer; its top has a search bar「アプリを検索」(may open keyboard automatically). Prefer open_app instead.
Long-press an app icon: menu split into ショートカット and actions (「バブル」= float app as bubble, 「ウィジェット」,「アプリ情報」). Long-press empty home area: 「壁紙とスタイル」「ウィジェット」「ホームの設定」.
Recents: horizontal cards; swipe a card up to close it; tap the app icon above a card for「分割画面」「固定」. 「すべてクリア」 at the far left.
Bubbles (new in 17): an app floating as a round icon; tap to expand, drag to the bottom 'X' to close.
Settings app (top has「設定を検索」, the fastest way to find any option): ネットワークとインターネット / 接続済みのデバイス / アプリ / 通知 / 電池と充電 / ストレージ / 音とバイブレーション / ディスプレイとタップ / 壁紙とスタイル / ユーザー補助 / セキュリティとプライバシー / 位置情報 / 安全と緊急情報 / パスワード、パスキー、アカウント / Google / システム(言語、日付と時刻、ジェスチャー、システム アップデート) / デバイス情報. Menus are compact; scroll to see more. Prefer the open_settings action when a page exists.
Dialogs: permission prompts offer「アプリの使用時のみ」「今回のみ」「許可しない」. Generic buttons: OK, キャンセル, 次へ, 完了, 閉じる, 後で, スキップ. 「︙」/「その他のオプション」 = overflow menu. A down-arrow / chevron expands sections.
Keyboard (Gboard): type replaces field text; to submit use enter on the field or tap the search/send button. Dismiss keyboard with back.
Common apps: Chrome address bar「検索するか URL を入力」; Google app / search bar on home; Gmail「作成」; Google マップ「ここで検索」; YouTube search = magnifier icon; 時計 app tabs アラーム/時計/タイマー/ストップウォッチ, add with「+」; カレンダー「+」→ 予定; Play ストア is an installer (blocked); カメラ shutter「写真を撮影」.
Status bar privacy dot/chip (green) means camera/mic/location in use — ignore it.
If the screen is the lock screen you cannot proceed: done with the reason.
""".trimIndent()

    /** open_settings で開けるページ（セキュリティ系は意図的に含めない） */
    val PAGES = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "internet" to "android.settings.panel.action.INTERNET_CONNECTIVITY",
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "volume" to "android.settings.panel.action.VOLUME",
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "notifications" to "android.settings.ALL_APPS_NOTIFICATION_SETTINGS",
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS,
        "nfc" to Settings.ACTION_NFC_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "data_usage" to Settings.ACTION_DATA_USAGE_SETTINGS,
        "dnd" to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
        "wallpaper" to "android.intent.action.SET_WALLPAPER",
        "settings" to Settings.ACTION_SETTINGS
    )

    fun openSettings(ctx: Context, page: String): String {
        val action = PAGES[page.lowercase().trim()] ?: return "未対応のページ（${PAGES.keys.joinToString(",")}）"
        return runCatching {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            "ok"
        }.getOrElse { "開けませんでした" }
    }

    // ---- 学習（成功した実行から得たコツをアプリごとに保存） ----
    private const val MAX = 12

    fun lessons(pkg: String): List<String> =
        Prefs.raw("lessons_$pkg").split('\n').filter { it.isNotBlank() }

    fun learn(pkg: String, lesson: String) {
        val l = lesson.replace('\n', ' ').trim().take(160)
        if (l.length < 6 || pkg.isBlank() || pkg == Safety.OWN) return
        val cur = lessons(pkg).filterNot { it == l }
        Prefs.putRaw("lessons_$pkg", (cur + l).takeLast(MAX).joinToString("\n"))
        val pk = Prefs.raw("lesson_pkgs").split(',').filter { it.isNotBlank() }.toMutableSet()
        pk += pkg
        Prefs.putRaw("lesson_pkgs", pk.joinToString(","))
    }

    fun count(): Int = Prefs.raw("lesson_pkgs").split(',').filter { it.isNotBlank() }.sumOf { lessons(it).size }

    fun clear() {
        Prefs.raw("lesson_pkgs").split(',').filter { it.isNotBlank() }.forEach { Prefs.putRaw("lessons_$it", "") }
        Prefs.putRaw("lesson_pkgs", "")
    }

    /** プロンプトに添える知識 */
    fun forPrompt(pkg: String, visited: Set<String>): String = buildString {
        val user = Prefs.customKnowledge.trim()
        if (user.isNotEmpty()) append("\nUser's notes about this phone: ").append(user.take(800))
        (visited + pkg).filter { it.isNotBlank() }.forEach { p ->
            val ls = lessons(p)
            if (ls.isNotEmpty()) append("\nLearned tips for ").append(p).append(": ").append(ls.joinToString(" / "))
        }
    }
}
