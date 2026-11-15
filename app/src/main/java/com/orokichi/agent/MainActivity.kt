@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package com.orokichi.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AgentTheme { Root() } }
    }
}

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<AgentTask?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val pending by AgentState.pending.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("エージェント", Icons.Rounded.AutoAwesome, 0),
                    Triple("定期タスク", Icons.Rounded.Schedule, 1),
                    Triple("設定", Icons.Rounded.Tune, 2),
                ).forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        },
        floatingActionButton = {
            if (tab == 1) ExtendedFloatingActionButton(
                onClick = { editing = AgentTask(name = "", prompt = "", sh = 7, sm = 0, eh = 7, em = 30) },
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("タスクを追加") }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> AgentScreen()
                1 -> TasksScreen(onEdit = { editing = it })
                else -> SettingsScreen()
            }
        }
    }
    editing?.let { t -> TaskEditor(t, onDismiss = { editing = null }) { Tasks.upsert(ctx, it); editing = null } }
    pending?.let { reason ->
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Rounded.GppMaybe, null) },
            title = { Text("この操作を許可しますか？") },
            text = { Text("$reason\n\n90秒以内に許可しないとエージェントは停止します。") },
            confirmButton = { Button(onClick = { AgentState.answer(true) }) { Text("許可して続行") } },
            dismissButton = {
                TextButton(onClick = { AgentState.answer(false); AgentState.stop("確認を拒否") }) { Text("拒否して停止") }
            }
        )
    }
}

/* ---------------- エージェント ---------------- */

@Composable
fun AgentScreen() {
    val ctx = LocalContext.current
    var a11y by remember { mutableStateOf(AgentService.isEnabled(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { a11y = AgentService.isEnabled(ctx) }
    val running by AgentState.running.collectAsState()
    val status by AgentState.status.collectAsState()
    val log by AgentState.log.collectAsState()
    var goal by rememberSaveable { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("AIエージェント", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)

        if (!a11y) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ユーザー補助がオフです", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "設定 → ユーザー補助 → インストール済みアプリ →「AIエージェント」をオンにしてください。グレーで押せない場合は、アプリ情報の右上メニューから「制限付き設定を許可」を先に行ってください。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("ユーザー補助を開く") }
                }
            }
        }

        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) LoadingIndicator(Modifier.size(48.dp))
                else Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(if (running) "実行中" else "待機中", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        if (running) status else "${Prefs.providerLabel(Prefs.provider)} · ${Prefs.model(Prefs.provider)}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        OutlinedTextField(
            value = goal, onValueChange = { goal = it },
            label = { Text("やってほしいこと") },
            placeholder = { Text("例: YouTubeで今日のニュースを検索して再生") },
            minLines = 2, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(), enabled = !running
        )
        if (!running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("Wi-Fiの設定を開いて状態を教えて", "明日7時のアラームを設定", "Chromeで岡山の天気を調べて").forEach {
                    SuggestionChip(onClick = { goal = it }, label = { Text(it) })
                }
            }
        }

        if (running) {
            Button(
                onClick = { AgentState.stop("アプリの緊急停止ボタン") },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(72.dp)
            ) {
                Icon(Icons.Rounded.StopCircle, null, Modifier.size(28.dp)); Spacer(Modifier.width(10.dp))
                Text("緊急停止", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = {
                    err = null
                    val svc = AgentService.instance
                    when {
                        goal.isBlank() -> err = "やってほしいことを入力してください"
                        svc == null -> err = "ユーザー補助を有効にしてください"
                        !Prefs.hasAnyKey() -> err = "設定タブでAPIキーを入力してください"
                        else -> svc.startRun(goal.trim(), null, "手動")
                    }
                },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text("実行", style = MaterialTheme.typography.titleMedium)
            }
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "停止方法: 画面右上の「■ 停止」/ 通知の「緊急停止」/ 音量キー上下を同時押し",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ログ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (log.isNotEmpty()) TextButton(onClick = { AgentState.log.value = emptyList() }) { Text("クリア") }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (log.isEmpty()) Text("まだ記録はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                log.asReversed().take(120).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------- 定期タスク ---------------- */

private val DAYS = listOf("月", "火", "水", "木", "金", "土", "日")
private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)
private fun daysText(d: Int) = when (d) {
    127 -> "毎日"; 31 -> "平日"; 96 -> "週末"
    else -> DAYS.filterIndexed { i, _ -> (d and (1 shl i)) != 0 }.joinToString("・").ifEmpty { "なし" }
}

@Composable
fun TasksScreen(onEdit: (AgentTask) -> Unit) {
    val ctx = LocalContext.current
    val tasks by Tasks.flow.collectAsState()
    var exact by remember { mutableStateOf(Scheduler.canExact(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exact = Scheduler.canExact(ctx); if (exact) Scheduler.all(ctx) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("定期タスク", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
        Text("指定した時間帯にエージェントが自動で実行します。終了時刻を過ぎると自動停止します。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!exact) PermRow(Icons.Rounded.Alarm, "アラームとリマインダー", "時間ちょうどに実行するために必要です", false) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        }
        if (tasks.isEmpty()) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.EventRepeat, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("タスクはまだありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        tasks.forEach { t ->
            Surface(
                onClick = { onEdit(t) }, shape = RoundedCornerShape(28.dp),
                color = if (t.enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(hm(t.sh, t.sm) + " 〜 " + hm(t.eh, t.em), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("${t.name.ifBlank { "無題" }} · ${daysText(t.days)}", style = MaterialTheme.typography.titleSmall)
                        }
                        Switch(checked = t.enabled, onCheckedChange = { Tasks.upsert(ctx, t.copy(enabled = it)) })
                    }
                    Text(t.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Row {
                        TextButton(onClick = {
                            AgentService.instance?.startRun(t.prompt, null, t.name)
                        }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("今すぐ実行") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { Tasks.delete(ctx, t) }) { Icon(Icons.Rounded.DeleteOutline, "削除") }
                    }
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
fun TaskEditor(init: AgentTask, onDismiss: () -> Unit, onSave: (AgentTask) -> Unit) {
    var name by remember { mutableStateOf(init.name) }
    var prompt by remember { mutableStateOf(init.prompt) }
    var sh by remember { mutableIntStateOf(init.sh) }
    var sm by remember { mutableIntStateOf(init.sm) }
    var eh by remember { mutableIntStateOf(init.eh) }
    var em by remember { mutableIntStateOf(init.em) }
    var days by remember { mutableIntStateOf(init.days) }
    var picking by remember { mutableIntStateOf(0) } // 1=開始 2=終了

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (init.name.isEmpty() && init.prompt.isEmpty()) "タスクを追加" else "タスクを編集") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(prompt, { prompt = it }, label = { Text("やること（AIへの指示）") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("時間帯", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    ToggleButton(checked = picking == 1, onCheckedChange = { picking = 1 },
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(), modifier = Modifier.weight(1f)) { Text("開始 " + hm(sh, sm)) }
                    ToggleButton(checked = picking == 2, onCheckedChange = { picking = 2 },
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(), modifier = Modifier.weight(1f)) { Text("終了 " + hm(eh, em)) }
                }
                Text("曜日", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAYS.forEachIndexed { i, d ->
                        val on = (days and (1 shl i)) != 0
                        FilledIconToggleButton(checked = on, onCheckedChange = { days = days xor (1 shl i) }, modifier = Modifier.size(36.dp)) { Text(d) }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = prompt.isNotBlank() && days != 0, onClick = {
                onSave(init.copy(name = name.trim(), prompt = prompt.trim(), sh = sh, sm = sm, eh = eh, em = em, days = days))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )

    if (picking != 0) {
        val st = rememberTimePickerState(if (picking == 1) sh else eh, if (picking == 1) sm else em, true)
        AlertDialog(
            onDismissRequest = { picking = 0 },
            title = { Text(if (picking == 1) "開始時刻" else "終了時刻") },
            text = { TimeInput(state = st) },
            confirmButton = {
                Button(onClick = {
                    if (picking == 1) { sh = st.hour; sm = st.minute } else { eh = st.hour; em = st.minute }
                    picking = 0
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = 0 }) { Text("キャンセル") } }
        )
    }
}

/* ---------------- 設定 ---------------- */

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var provider by remember { mutableStateOf(Prefs.provider) }
    var auto by remember { mutableStateOf(Prefs.autoSwitch) }
    val test = remember { mutableStateMapOf<String, String?>() }
    var mode by remember { mutableStateOf(Prefs.safetyMode) }
    var steps by remember { mutableFloatStateOf(Prefs.maxSteps.toFloat()) }
    var blocked by remember { mutableStateOf(Prefs.blockedPkgs) }
    var overlay by remember { mutableStateOf(Prefs.overlayStop) }
    var volume by remember { mutableStateOf(Prefs.volumeStop) }
    var cursorOn by remember { mutableStateOf(Prefs.cursor) }
    var learnOn by remember { mutableStateOf(Prefs.learning) }
    var custom by remember { mutableStateOf(Prefs.customKnowledge) }
    var lessonCount by remember { mutableStateOf(Knowledge.count()) }
    var overlayPerm by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    val scope = rememberCoroutineScope()

    var a11y by remember { mutableStateOf(AgentService.isEnabled(ctx)) }
    var notif by remember { mutableStateOf(true) }
    var exact by remember { mutableStateOf(true) }
    var battery by remember { mutableStateOf(true) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = it }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        a11y = AgentService.isEnabled(ctx)
        notif = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        exact = Scheduler.canExact(ctx)
        overlayPerm = Settings.canDrawOverlays(ctx)
        battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("設定", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)

        Section("AIプロバイダー")
        Text("優先して使うAI", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            Prefs.providers.forEachIndexed { i, p ->
                ToggleButton(
                    checked = provider == p,
                    onCheckedChange = { provider = p; Prefs.provider = p },
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        Prefs.providers.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(Prefs.providerLabel(p), maxLines = 1) }
            }
        }
        SwitchRow(Icons.Rounded.SwapHoriz, "制限時に自動切り替え", "429などの制限で次のキー/AIへ。全部制限中なら1分待機", auto) { auto = it; Prefs.autoSwitch = it }
        Prefs.providers.forEach { p -> ProviderCard(p, test[p]) { test[p] = it } }
        FilledTonalButton(onClick = {
            scope.launch {
                Prefs.providers.forEach { p ->
                    val k = Prefs.keys(p)
                    if (k.isEmpty()) { test[p] = null; return@forEach }
                    test[p] = "テスト中…"
                    var ok = 0
                    val errs = mutableListOf<String>()
                    k.forEach { key ->
                        try { Llm.chat(LlmCfg(p, key, Prefs.model(p)), "Reply only with JSON.", "Return {\"ok\":true}"); ok++ }
                        catch (e: Exception) { errs += (e.message ?: "エラー").take(60) }
                    }
                    test[p] = if (errs.isEmpty()) "✓ ${ok}個のキーが接続OK" else "✓$ok ✕${errs.size}: ${errs.first()}"
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.NetworkCheck, null); Spacer(Modifier.width(8.dp)); Text("すべて接続テスト") }

        Section("表示")
        SwitchRow(Icons.Rounded.Mouse, "仮想カーソルと思考表示", "操作する場所・入力内容・AIの考えを画面に重ねて表示", cursorOn) { cursorOn = it; Prefs.cursor = it }
        PermRow(Icons.Rounded.Layers, "他のアプリの上に重ねて表示", "仮想カーソルと思考バブルの表示に使います", overlayPerm) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }

        Section("知識と学習")
        Text(
            "Android 17（Pixel）の基本操作（ナビゲーション、クイック設定、設定アプリの構成、バブル、主要アプリなど）を最初から知っています。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SwitchRow(Icons.Rounded.School, "実行から学習", "成功したタスクで覚えたコツをアプリごとに保存（学習済み: ${lessonCount}件）", learnOn) { learnOn = it; Prefs.learning = it }
        OutlinedTextField(
            value = custom, onValueChange = { custom = it; Prefs.customKnowledge = it },
            label = { Text("AIに教えておくこと（任意）") },
            placeholder = { Text("例: 3ボタンナビを使用 / LINEはホーム2ページ目") },
            minLines = 2, modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = { Knowledge.clear(); lessonCount = 0 }, enabled = lessonCount > 0) { Text("学習データを消去") }

        Section("安全装置")
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ToggleButton(checked = mode == "confirm", onCheckedChange = { mode = "confirm"; Prefs.safetyMode = "confirm" },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(), modifier = Modifier.weight(1f)) { Text("停止して確認") }
            ToggleButton(checked = mode == "strict", onCheckedChange = { mode = "strict"; Prefs.safetyMode = "strict" },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(), modifier = Modifier.weight(1f)) { Text("即停止（厳格）") }
        }
        Text(
            "購入・支払い・送信・削除・発信・許可などは一時停止して確認します（厳格では即停止）。アンインストール・初期化・送金・パスワード/カード番号入力・ユーザー補助やセキュリティ設定の変更・本アプリ自身の操作は常にブロックします。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column {
            Text("1回の最大ステップ数: ${steps.toInt()}", style = MaterialTheme.typography.labelLarge)
            Slider(value = steps, onValueChange = { steps = it; Prefs.maxSteps = it.toInt() }, valueRange = 5f..60f, steps = 10)
        }
        OutlinedTextField(
            value = blocked, onValueChange = { blocked = it; Prefs.blockedPkgs = it },
            label = { Text("操作禁止アプリ（パッケージ名、カンマ区切り）") },
            placeholder = { Text("例: jp.co.bank.app, com.paypay.android") },
            modifier = Modifier.fillMaxWidth()
        )
        SwitchRow(Icons.Rounded.BackHand, "画面上の停止ボタン", "実行中は右上に「■ 停止」を表示", overlay) { overlay = it; Prefs.overlayStop = it }
        SwitchRow(Icons.Rounded.VolumeUp, "音量キーで緊急停止", "音量の上下を同時押しで停止", volume) { volume = it; Prefs.volumeStop = it }

        Section("権限")
        PermRow(Icons.Rounded.Accessibility, "ユーザー補助", "画面の読み取りと操作に必要", a11y) {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        PermRow(Icons.Rounded.Notifications, "通知", "停止ボタン・確認・結果の表示", notif) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermRow(Icons.Rounded.Alarm, "アラームとリマインダー", "定期タスクを正確な時刻に実行", exact) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        }
        PermRow(Icons.Rounded.BatteryChargingFull, "電池の最適化を除外", "バックグラウンドで止まらないように", battery) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }
        Text("APIキーはこの端末内にのみ保存されます。画面ロック中は操作できないため、定期タスクはロック解除中に実行されます。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProviderCard(p: String, result: String?, onResult: (String?) -> Unit) {
    var key by remember { mutableStateOf(Prefs.key(p)) }
    var model by remember { mutableStateOf(Prefs.model(p)) }
    val n = key.split(',', '\n', ' ').count { it.isNotBlank() }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Prefs.providerLabel(p), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (n > 0) AssistChip(onClick = {}, label = { Text("キー ${n}個") })
            }
            OutlinedTextField(
                value = key, onValueChange = { key = it; Prefs.setKey(p, it); onResult(null) },
                label = { Text("APIキー（カンマ区切りで複数可）") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                supportingText = {
                    Text(when (p) {
                        "gemini" -> "aistudio.google.com で取得"
                        "openrouter" -> "openrouter.ai/keys で取得"
                        else -> "console.groq.com/keys で取得"
                    })
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it; Prefs.setModel(p, it) },
                label = { Text("モデル") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("既定: ${Prefs.defaultModel(p)}") }
            )
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun PermRow(icon: ImageVector, title: String, desc: String, ok: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (ok) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (ok) Icon(Icons.Rounded.CheckCircle, "許可済み", tint = MaterialTheme.colorScheme.primary)
            else Button(onClick = onClick) { Text("許可") }
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
