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
    private const val PREFS = "dosefolk_smart_escalation"
    private const val ANCHOR_PREFIX = "anchor|"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun anchorKey(time: String, scheduledDate: String) = "$ANCHOR_PREFIX$scheduledDate|$time"

    fun schedule(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) {
        cancelAlarms(c, time, scheduledDate)
        AlertOutbox.dropEscalationSession(c.applicationContext, time, scheduledDate)
        AttentionBudget.clear(c, time, scheduledDate)
        val anchor = ensureAnchor(c, time, scheduledDate)
        scheduleFromAnchor(c, time, scheduledDate, anchor)
    }

    fun cancel(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) {
        cancelAlarms(c, time, scheduledDate)
        AlertOutbox.dropEscalationSession(c.applicationContext, time, scheduledDate)
        AttentionBudget.clear(c, time, scheduledDate)
        prefs(c).edit().remove(anchorKey(time, scheduledDate)).commit()
    }

    fun deferUntil(c: Context, time: String, expiresAt: Long, scheduledDate: String = LocalDate.now().toString()) {
        cancelAlarms(c, time, scheduledDate)
        val base = maxOf(System.currentTimeMillis(), expiresAt) + BATON_GRACE_MS
        val people = TemporaryCareStore.prioritizedPeople(c)
        if (people.isNotEmpty()) scheduleStage(c, time, scheduledDate, 0, base)
        if (people.size > 1) scheduleStage(c, time, scheduledDate, 1, base + 15 * 60_000L)
    }

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
                        val anchor = ensureAnchor(c, state.time, scheduledDate)
                        scheduleFromAnchor(c, state.time, scheduledDate, anchor)
                    }
                }
                DoseSessionStatus.SNOOZED -> {
                    if (baton != null) deferUntil(c, state.time, baton.expiresAt, scheduledDate)
                    else cancelAlarms(c, state.time, scheduledDate)
                }
                else -> {
                    cancelAlarms(c, state.time, scheduledDate)
                    prefs(c).edit().remove(anchorKey(state.time, scheduledDate)).commit()
                }
            }
        }
    }

    private fun ensureAnchor(c: Context, time: String, scheduledDate: String): Long {
        val key = anchorKey(time, scheduledDate)
        val stored = prefs(c).getLong(key, 0L)
        if (stored > 0L) return stored

        // The canonical alarm event is durable before presentation/escalation. If the
        // process died before the anchor was saved, recover the original session time
        // from that event instead of restarting the 10/25 minute clocks from reboot.
        val eventAnchor = EventStore.load(c)
            .asSequence()
            .filter { it.type == "alarm" && it.time == time && it.scheduledDate == scheduledDate }
            .minOfOrNull { it.timestamp }
            ?.takeIf { it > 0L }
        val anchor = eventAnchor ?: System.currentTimeMillis()
        prefs(c).edit().putLong(key, anchor).commit()
        return anchor
    }

    private fun scheduleFromAnchor(c: Context, time: String, scheduledDate: String, anchor: Long) {
        val now = System.currentTimeMillis()
        scheduleStage(c, time, scheduledDate, 0, triggerFor(anchor, FIRST_DELAY_MIN, now))
        scheduleStage(c, time, scheduledDate, 1, triggerFor(anchor, SECOND_DELAY_MIN, now))
    }

    internal fun triggerFor(anchor: Long, delayMinutes: Long, now: Long): Long =
        maxOf(now + 1_000L, anchor + delayMinutes * 60_000L)

    internal fun storedAnchor(c: Context, time: String, scheduledDate: String): Long =
        prefs(c).getLong(anchorKey(time, scheduledDate), 0L)

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

                // Persist the alert first under a deterministic stage ID, then
                // durably mark the attention budget, and only then let WorkManager
                // drain the queue. Crash at any boundary converges without creating
                // a second caregiver alert row or silently losing the first one.
                val alertId = "escalation|$scheduledDate|$time|${target.topic}|$stage"
                AlertOutbox.enqueue(c.applicationContext, target.topic, title, body, id = alertId, kick = false)
                AttentionBudget.mark(c, time, target.topic, stage, scheduledDate)
                DosefolkSyncScheduler.kick(c)
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
        prefs(c).edit().putBoolean(key(time, topic, stage, scheduledDate), true).commit()
    }

    fun clear(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) {
        val prefix = "$scheduledDate|$time|"
        val editor = prefs(c).edit()
        prefs(c).all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.commit()
    }
}
