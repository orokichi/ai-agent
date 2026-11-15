package com.orokichi.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

object Notifs {
    const val ID_RUN = 1
    const val ID_ASK = 2
    const val ID_RES = 3
    const val ID_ALERT = 4
    private const val CH_RUN = "run"
    private const val CH_ASK = "ask"
    private const val CH_RES = "result"

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    fun channels(c: Context) {
        nm(c).createNotificationChannels(
            listOf(
                NotificationChannel(CH_RUN, "実行中", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_ASK, "確認・安全装置", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_RES, "結果", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    private fun open(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun action(c: Context, title: String, act: String, req: Int) = Notification.Action.Builder(
        null as Icon?, title,
        PendingIntent.getBroadcast(c, req, Intent(c, ControlReceiver::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)
    ).build()

    private fun base(c: Context, ch: String, title: String, text: String) = Notification.Builder(c, ch)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(open(c))

    private fun post(c: Context, id: Int, n: Notification) {
        try { nm(c).notify(id, n) } catch (_: SecurityException) {}
    }

    fun ongoing(c: Context, goal: String) = post(
        c, ID_RUN, base(c, CH_RUN, "AIエージェント実行中", goal)
            .setOngoing(true)
            .addAction(action(c, "■ 緊急停止", ControlReceiver.STOP, 1))
            .build()
    )

    fun approval(c: Context, reason: String) = post(
        c, ID_ASK, base(c, CH_ASK, "⚠ 確認が必要です", "$reason\n90秒以内に許可しないと停止します")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(action(c, "許可して続行", ControlReceiver.ALLOW, 2))
            .addAction(action(c, "拒否して停止", ControlReceiver.DENY, 3))
            .build()
    )

    fun result(c: Context, title: String, text: String) =
        post(c, ID_RES, base(c, CH_RES, title, text).setAutoCancel(true).build())

    fun alert(c: Context, title: String, text: String) =
        post(c, ID_ALERT, base(c, CH_ASK, title, text).setAutoCancel(true).build())

    fun cancel(c: Context, id: Int) = nm(c).cancel(id)
}

class ControlReceiver : BroadcastReceiver() {
    companion object {
        const val STOP = "com.orokichi.agent.STOP"
        const val ALLOW = "com.orokichi.agent.ALLOW"
        const val DENY = "com.orokichi.agent.DENY"
    }

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            STOP -> AgentState.stop("通知から停止")
            ALLOW -> AgentState.answer(true)
            DENY -> { AgentState.answer(false); AgentState.stop("確認を拒否") }
        }
    }
}
