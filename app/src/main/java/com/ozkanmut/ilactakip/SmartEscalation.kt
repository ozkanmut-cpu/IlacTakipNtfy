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
 * Resolution cancels the chain. An active Care Baton defers, rather than
 * consumes, the escalation so attention resumes if the baton expires unresolved.
 */
object SmartEscalation {
    private const val FIRST_DELAY_MIN = 10L
    private const val SECOND_DELAY_MIN = 25L
    private const val BATON_GRACE_MS = 5_000L

    fun schedule(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) {
        cancelAlarms(c, time, scheduledDate)
        AttentionBudget.clear(c, time, scheduledDate)
        scheduleFresh(c, time, scheduledDate)
    }

    fun cancel(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) {
        cancelAlarms(c, time, scheduledDate)
        AttentionBudget.clear(c, time, scheduledDate)
    }

    fun deferUntil(c: Context, time: String, expiresAt: Long, scheduledDate: String = LocalDate.now().toString()) {
        cancelAlarms(c, time, scheduledDate)
        val base = maxOf(System.currentTimeMillis(), expiresAt) + BATON_GRACE_MS
        val people = TemporaryCareStore.prioritizedPeople(c)
        if (people.isNotEmpty()) scheduleStage(c, time, scheduledDate, 0, base)
        if (people.size > 1) scheduleStage(c, time, scheduledDate, 1, base + 15 * 60_000L)
    }

    /**
     * Rebuilds only operational escalation alarms lost with AlarmManager state.
     * AttentionBudget is intentionally preserved so a reboot never re-sends a
     * caregiver stage that was already delivered before the restart.
     */
    fun restore(c: Context) {
        CareBatonStore.cleanup(c)
        DoseStateEngine.unresolved(c).forEach { state ->
            val scheduledDate = state.scheduledDate
            val baton = CareBatonStore.active(c, state.time, scheduledDate)
            when (state.status) {
                DoseSessionStatus.PENDING,
                DoseSessionStatus.CONFLICT -> {
                    if (baton != null) deferUntil(c, state.time, baton.expiresAt, scheduledDate)
                    else {
                        cancelAlarms(c, state.time, scheduledDate)
                        scheduleFresh(c, state.time, scheduledDate)
                    }
                }
                DoseSessionStatus.SNOOZED -> {
                    // The explicit snooze alarm is restored separately. Do not
                    // escalate before the user's snooze window ends.
                    if (baton != null) deferUntil(c, state.time, baton.expiresAt, scheduledDate)
                    else cancelAlarms(c, state.time, scheduledDate)
                }
                else -> cancelAlarms(c, state.time, scheduledDate)
            }
        }
    }

    private fun scheduleFresh(c: Context, time: String, scheduledDate: String) {
        val now = System.currentTimeMillis()
        scheduleStage(c, time, scheduledDate, 0, now + FIRST_DELAY_MIN * 60_000L)
        scheduleStage(c, time, scheduledDate, 1, now + SECOND_DELAY_MIN * 60_000L)
    }

    private fun cancelAlarms(c: Context, time: String, scheduledDate: String) {
        val alarmManager = c.getSystemService(AlarmManager::class.java)
        for (stage in 0..1) {
            val pi = pendingIntent(c, time, scheduledDate, stage)
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    private fun scheduleStage(c: Context, time: String, scheduledDate: String, stage: Int, trigger: Long) {
        val alarmManager = c.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(c, time, scheduledDate, stage)
        try { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi) }
        catch (_: SecurityException) { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi) }
    }

    private fun pendingIntent(c: Context, time: String, scheduledDate: String, stage: Int): PendingIntent {
        val intent = Intent(c, EscalationReceiver::class.java)
            .putExtra("time", time)
            .putExtra("scheduledDate", scheduledDate)
            .putExtra("stage", stage)
        return PendingIntent.getBroadcast(
            c, ("dosefolk-escalation-$scheduledDate-$time-$stage").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class EscalationReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val time = i.getStringExtra("time") ?: return
        val scheduledDate = i.getStringExtra("scheduledDate") ?: LocalDate.now().toString()
        val stage = i.getIntExtra("stage", 0)
        val pendingResult = goAsync()
        Thread {
            try {
                SyncEngine.pullBlocking(c.applicationContext)
                val date = runCatching { LocalDate.parse(scheduledDate) }.getOrDefault(LocalDate.now())
                val state = DoseStateEngine.stateForTime(c, time, date)
                val unresolved = state.status == DoseSessionStatus.PENDING || state.status == DoseSessionStatus.SNOOZED || state.status == DoseSessionStatus.CONFLICT
                if (!unresolved) return@Thread

                val baton = CareBatonStore.active(c, time, scheduledDate)
                if (baton != null) {
                    SmartEscalation.deferUntil(c, time, baton.expiresAt, scheduledDate)
                    return@Thread
                }

                val people = TemporaryCareStore.prioritizedPeople(c)
                val target = people.getOrNull(stage) ?: return@Thread
                if (!AttentionBudget.allow(c, time, target.topic, stage, scheduledDate)) return@Thread

                val medNames = state.medications.joinToString(", ") { it.name }
                val title = if (I18n.language() == "tr") "Dosefolk • ilgilenme gerekiyor" else "Dosefolk • attention needed"
                val body = if (I18n.language() == "tr") {
                    if (medNames.isBlank()) "$time ilaç kaydı hâlâ açık." else "$time • $medNames hâlâ açık."
                } else {
                    if (medNames.isBlank()) "The $time medication session is still unresolved." else "$time • $medNames is still unresolved."
                }
                Ntfy.sendTo(c, target.topic, title, body)
                AttentionBudget.mark(c, time, target.topic, stage, scheduledDate)
            } finally { pendingResult.finish() }
        }.start()
    }
}

object AttentionBudget {
    private const val PREFS = "dosefolk_attention_budget"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(time: String, topic: String, stage: Int, scheduledDate: String) = "$scheduledDate|$time|$topic|$stage"

    fun allow(c: Context, time: String, topic: String, stage: Int, scheduledDate: String = LocalDate.now().toString()): Boolean =
        !prefs(c).getBoolean(key(time, topic, stage, scheduledDate), false)

    fun mark(c: Context, time: String, topic: String, stage: Int, scheduledDate: String = LocalDate.now().toString()) {
        prefs(c).edit().putBoolean(key(time, topic, stage, scheduledDate), true).apply()
    }

    fun clear(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) {
        val prefix = "$scheduledDate|$time|"
        val editor = prefs(c).edit()
        prefs(c).all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }
}
