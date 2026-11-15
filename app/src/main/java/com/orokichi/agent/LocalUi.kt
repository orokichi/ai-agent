@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.orokichi.agent

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

private fun gb(b: Long) = if (b < 0) "?" else "%.1fGB".format(b / 1_000_000_000.0)

/** モデル1行：ダウンロード/進捗/選択/削除 */
@Composable
fun ModelRow(m: LocalModel, selected: Boolean, onSelect: () -> Unit) {
    val ctx = LocalContext.current
    val prog by LocalLlm.progress.collectAsState()
    val errs by LocalLlm.error.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val ready = remember(prog, tick) { LocalLlm.isReady(ctx, m) }
    val p = prog[m.id]
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (selected && ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect, enabled = ready)
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(if (m.id == "custom") gb(m.size) else "${gb(m.size)}・${m.desc}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                p != null -> {
                    LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ダウンロード中 ${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { LocalLlm.cancelDownload(ctx) }) { Text("一時停止") }
                    }
                }
                ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("ダウンロード済み", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { LocalLlm.delete(ctx, m); tick++ }) { Text("削除") }
                }
                else -> {
                    val resume = LocalLlm.part(ctx, m).exists()
                    FilledTonalButton(onClick = { LocalLlm.startDownload(ctx, m) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp))
                        Text(if (resume) "続きからダウンロード" else "ダウンロード（${gb(m.size)}）")
                    }
                }
            }
            errs[m.id]?.let { Text("✕ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** 設定タブ：端末内AI */
@Composable
fun LocalSection(onTutorial: () -> Unit) {
    var mode by remember { mutableStateOf(Prefs.localMode) }
    var sel by remember { mutableStateOf(Prefs.localModel) }
    var gpu by remember { mutableStateOf(Prefs.localGpu) }
    var token by remember { mutableStateOf(Prefs.hfToken) }
    var url by remember { mutableStateOf(Prefs.customModelUrl) }
    var adv by remember { mutableStateOf(false) }

    Text(
        "Hugging Faceから小型モデルをスマホに保存して、ネット不要・トークン制限なしで動かします（Wi‑Fi推奨）。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text("使い方", style = MaterialTheme.typography.labelLarge)
    val modes = listOf("off" to "API", "first" to "端末内優先", "only" to "端末内のみ")
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        modes.forEachIndexed { i, (k, l) ->
            ToggleButton(
                checked = mode == k, onCheckedChange = { mode = k; Prefs.localMode = k },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f)
            ) { Text(l, maxLines = 1) }
        }
    }
    Text(
        when (mode) {
            "first" -> "端末内AIで動かし、失敗したときだけクラウドAPIを使います。"
            "only" -> "常に端末内AIだけを使います（オフラインOK）。"
            else -> "クラウドAPIだけを使います。"
        }, style = MaterialTheme.typography.bodySmall
    )
    LocalLlm.all().forEach { m ->
        ModelRow(m, sel == m.id) { sel = m.id; Prefs.localModel = m.id }
    }
    SwitchRow(Icons.Rounded.Memory, "GPUで高速化", "動かないときはオフにしてCPUで実行", gpu) { gpu = it; Prefs.localGpu = it }
    TextButton(onClick = { adv = !adv }) { Text(if (adv) "詳細設定を閉じる" else "詳細設定（ほかのモデル）") }
    if (adv) {
        OutlinedTextField(
            url, { url = it; Prefs.customModelUrl = it.trim() },
            label = { Text(".litertlm のURL（任意）") },
            placeholder = { Text("https://huggingface.co/litert-community/…/resolve/main/xxx.litertlm") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            token, { token = it; Prefs.hfToken = it.trim() },
            label = { Text("Hugging Faceトークン（ゲート付きモデル用・任意）") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
    }
    OutlinedButton(onClick = onTutorial, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.School, null); Spacer(Modifier.width(8.dp)); Text("チュートリアルを見る")
    }
}

/* ---------------- チュートリアル ---------------- */

private data class Page(val icon: ImageVector, val title: String, val body: String)

@Composable
fun TutorialDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val pages = listOf(
        Page(Icons.Rounded.AutoAwesome, "AIエージェントへようこそ", "やりたいことを文章で伝えると、AIがあなたの代わりにスマホを操作します。\n\n例：「時計アプリで明日7時のアラームを設定して」「Chromeで岡山の天気を調べて」"),
        Page(Icons.Rounded.Accessibility, "① ユーザー補助をオン", "画面を読み取ってタップするために必要です。\n\n設定 → ユーザー補助 → インストール済みアプリ →「AIエージェント」をオン。\n\nグレーで押せないときは、アプリ情報の右上「︙」→「制限付き設定を許可」を先に行ってください。"),
        Page(Icons.Rounded.Mouse, "② 重ねて表示を許可", "AIが「どこを押すか・何を入力するか」を仮想カーソルで、考えていることを吹き出しで画面に表示します。\n\n「他のアプリの上に重ねて表示」を許可してください。"),
        Page(Icons.Rounded.Memory, "③ AIを準備", "2つの方法があります。両方使うのもOK。\n\nA. 端末内AI：モデルをスマホに保存。トークン制限なし・オフラインで動作。下のボタンでおすすめモデルを入れられます（Wi‑Fi推奨・約2.6GB）。\n\nB. クラウドAPI：設定タブでGroq / Gemini / OpenRouter / GLMのキーを入力。高精度で速いが回数制限あり。"),
        Page(Icons.Rounded.Shield, "④ 使い方と安全", "エージェントタブで指示を入力 →「開始」。\n\n止めるとき：画面右上の「■ 停止」、通知の「緊急停止」、音量キー上下の同時押し。\n\n購入・送信・削除などは実行前に確認を求め、送金・初期化・パスワード入力などは常にブロックします。"),
    )
    val state = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    var a11y by remember { mutableStateOf(AgentService.isEnabled(ctx)) }
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var mode by remember { mutableStateOf(Prefs.localMode) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        a11y = AgentService.isEnabled(ctx); overlay = Settings.canDrawOverlays(ctx)
    }
    val rec = LocalLlm.MODELS.first()

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("チュートリアル ${state.currentPage + 1}/${pages.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDone) { Text("スキップ") }
                }
                HorizontalPager(state, Modifier.weight(1f)) { i ->
                    val pg = pages[i]
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Spacer(Modifier.height(24.dp))
                        Box(
                            Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                            contentAlignment = Alignment.Center
                        ) { Icon(pg.icon, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        Text(pg.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                        Text(pg.body, style = MaterialTheme.typography.bodyLarge)
                        when (i) {
                            1 -> if (a11y) Done("ユーザー補助はオンです") else Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("ユーザー補助を開く") }
                            2 -> if (overlay) Done("許可されています") else Button(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                            }) { Text("許可する") }
                            3 -> {
                                ModelRow(rec, Prefs.localModel == rec.id) { Prefs.localModel = rec.id }
                                if (mode == "off") FilledTonalButton(onClick = {
                                    mode = "first"; Prefs.localMode = "first"
                                    if (Prefs.localModel.isEmpty()) Prefs.localModel = rec.id
                                }) { Text("端末内AIを優先して使う") } else Done("端末内AIを使う設定です")
                                Text("ダウンロードは閉じても通知で続きます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                    repeat(pages.size) { i ->
                        Box(
                            Modifier.padding(4.dp).size(if (i == state.currentPage) 22.dp else 8.dp, 8.dp)
                                .background(if (i == state.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { scope.launch { state.animateScrollToPage(state.currentPage - 1) } },
                        enabled = state.currentPage > 0, modifier = Modifier.weight(1f)
                    ) { Text("戻る") }
                    Button(onClick = {
                        if (state.currentPage == pages.lastIndex) onDone()
                        else scope.launch { state.animateScrollToPage(state.currentPage + 1) }
                    }, modifier = Modifier.weight(1f)) { Text(if (state.currentPage == pages.lastIndex) "はじめる" else "次へ") }
                }
            }
        }
    }
}

@Composable
private fun Done(t: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.width(6.dp)); Text(t, style = MaterialTheme.typography.titleSmall)
}
