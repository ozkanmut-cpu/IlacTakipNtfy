package com.ozkanmut.ilactakip

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

/**
 * Quiet-success escalation policy.
 * Stage 0: first Circle member after 10 minutes.
 * Stage 1: second Circle member after 25 minutes.
 * Any resolved dose or active Care Baton suppresses escalation.
 */
object SmartEscalation {
    private const val FIRST_DELAY_MIN = 10L
    private const val SECOND_DELAY_MIN = 25L

    fun schedule(c: Context, time: String) {
        cancel(c, time)
        scheduleStage(c, time, 0, FIRST_DELAY_MIN)
        scheduleStage(c, time, 1, SECOND_DELAY_MIN)
    }

    fun cancel(c: Context, time: String) {
        val alarmManager = c.getSystemService(AlarmManager::class.java)
        for (stage in 0..1) {
            alarmManager.cancel(pendingIntent(c, time, stage))
        }
        AttentionBudget.clear(c, time)
    }

    private fun scheduleStage(c: Context, time: String, stage: Int, delayMinutes: Long) {
        val alarmManager = c.getSystemService(AlarmManager::class.java)
        val trigger = System.currentTimeMillis() + delayMinutes * 60_000L
        val pi = pendingIntent(c, time, stage)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    private fun pendingIntent(c: Context, time: String, stage: Int): PendingIntent {
        val intent = Intent(c, EscalationReceiver::class.java)
            .putExtra("time", time)
            .putExtra("stage", stage)
        return PendingIntent.getBroadcast(
            c,
            ("dosefolk-escalation-$time-$stage").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class EscalationReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val time = i.getStringExtra("time") ?: return
        val stage = i.getIntExtra("stage", 0)

        SyncEngine.pullOnce(c)
        val state = DoseStateEngine.stateForTime(c, time)
        val unresolved = state.status == DoseSessionStatus.PENDING ||
            state.status == DoseSessionStatus.SNOOZED ||
            state.status == DoseSessionStatus.CONFLICT
        if (!unresolved) return
        if (CareBatonStore.active(c, time) != null) return

        val people = Store.people(c)
        val target = people.getOrNull(stage) ?: return
        if (!AttentionBudget.allow(c, time, target.topic, stage)) return

        val medNames = state.medications.joinToString(", ") { it.name }
        val title = if (I18n.language() == "tr") "Dosefolk • ilgilenme gerekiyor" else "Dosefolk • attention needed"
        val body = if (I18n.language() == "tr") {
            if (medNames.isBlank()) "$time ilaç kaydı hâlâ açık." else "$time • $medNames hâlâ açık."
        } else {
            if (medNames.isBlank()) "The $time medication session is still unresolved." else "$time • $medNames is still unresolved."
        }
        Ntfy.sendTo(target.topic, title, body)
        AttentionBudget.mark(c, time, target.topic, stage)
    }
}

/** Prevent duplicate caregiver notifications for the same dose/stage/day. */
object AttentionBudget {
    private const val PREFS = "dosefolk_attention_budget"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(time: String, topic: String, stage: Int) = "${LocalDate.now()}|$time|$topic|$stage"

    fun allow(c: Context, time: String, topic: String, stage: Int): Boolean =
        !prefs(c).getBoolean(key(time, topic, stage), false)

    fun mark(c: Context, time: String, topic: String, stage: Int) {
        prefs(c).edit().putBoolean(key(time, topic, stage), true).apply()
    }

    fun clear(c: Context, time: String) {
        val prefix = "${LocalDate.now()}|$time|"
        val editor = prefs(c).edit()
        prefs(c).all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }
}
