package com.orokichi.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/** days: bit0=月 … bit6=日 */
data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    val sh: Int, val sm: Int,
    val eh: Int, val em: Int,
    val days: Int = 0b1111111,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("prompt", prompt)
        .put("sh", sh).put("sm", sm).put("eh", eh).put("em", em).put("days", days).put("enabled", enabled)

    companion object {
        fun from(o: JSONObject) = AgentTask(
            o.getString("id"), o.optString("name"), o.optString("prompt"),
            o.optInt("sh"), o.optInt("sm"), o.optInt("eh"), o.optInt("em"),
            o.optInt("days", 127), o.optBoolean("enabled", true)
        )
    }
}

object Tasks {
    val flow = MutableStateFlow<List<AgentTask>>(emptyList())

    fun load() {
        flow.value = runCatching {
            val a = JSONArray(Prefs.sp.getString("tasks", "[]"))
            (0 until a.length()).map { AgentTask.from(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<AgentTask>) {
        Prefs.sp.edit().putString("tasks", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
        flow.value = list
    }

    fun upsert(c: Context, t: AgentTask) {
        val l = flow.value.toMutableList()
        val i = l.indexOfFirst { it.id == t.id }
        if (i >= 0) l[i] = t else l += t
        save(l)
        Scheduler.schedule(c, t)
    }

    fun delete(c: Context, t: AgentTask) {
        Scheduler.cancel(c, t)
        save(flow.value.filter { it.id != t.id })
    }

    fun get(id: String) = flow.value.firstOrNull { it.id == id }
}

object Scheduler {
    fun next(t: AgentTask, now: Long = System.currentTimeMillis()): Long? {
        if (!t.enabled || t.days == 0) return null
        for (d in 0..7) {
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, t.sh); set(Calendar.MINUTE, t.sm)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
            if ((t.days and (1 shl dow)) != 0 && c.timeInMillis > now) return c.timeInMillis
        }
        return null
    }

    fun deadline(t: AgentTask, start: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = start
            set(Calendar.HOUR_OF_DAY, t.eh); set(Calendar.MINUTE, t.em)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= start) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pi(c: Context, id: String) = PendingIntent.getBroadcast(
        c, id.hashCode(), Intent(c, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun canExact(c: Context) = c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(c: Context, t: AgentTask) {
        cancel(c, t)
        val at = next(t) ?: return
        val am = c.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, t.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, t.id))
    }

    fun cancel(c: Context, t: AgentTask) = c.getSystemService(AlarmManager::class.java).cancel(pi(c, t.id))

    fun all(c: Context) = Tasks.flow.value.forEach { schedule(c, it) }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Prefs.init(c)
        if (Tasks.flow.value.isEmpty()) Tasks.load()
        val t = Tasks.get(i.getStringExtra("id") ?: return) ?: return
        val now = System.currentTimeMillis()
        val svc = AgentService.instance
        when {
            svc == null -> Notifs.alert(c, "「${t.name}」を実行できません", "ユーザー補助サービスが無効です")
            !svc.startRun(t.prompt, Scheduler.deadline(t, now), t.name) ->
                Notifs.alert(c, "「${t.name}」をスキップしました", "別のタスクを実行中です")
        }
        Scheduler.schedule(c, t)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Prefs.init(c)
        Tasks.load()
        Scheduler.all(c)
    }
}
