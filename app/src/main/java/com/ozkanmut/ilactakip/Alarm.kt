package com.ozkanmut.ilactakip

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object AlarmScheduler {
    private const val PREFS = "dosefolk_alarm_scheduler"
    private const val KEY_TIMES = "scheduled_times"

    fun scheduleAll(c: Context, meds: List<Medication>, observeProgramChanges: Boolean = true) {
        if (observeProgramChanges) ProgramSync.observeLocal(c, meds)
        val zone = TravelGuard.effectiveMedicationZone(c)
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = p.getStringSet(KEY_TIMES, emptySet()).orEmpty().toSet()
        val scheduled = mutableSetOf<String>()
        meds.flatMap { med -> med.times.map { it to med } }
            .groupBy({ it.first }, { it.second })
            .forEach { (time, list) -> if (scheduleNextForTime(c, time, list, zone)) scheduled += time }
        (previous - scheduled).forEach { cancelGroup(c, it) }
        p.edit().putStringSet(KEY_TIMES, scheduled).apply()
    }

    private fun cancelGroup(c: Context, time: String) = cancelByKey(c, "group-$time")
    fun cancelSnooze(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) = cancelByKey(c, snoozeKey(time, scheduledDate))
    internal fun snoozeKey(time: String, scheduledDate: String) = "snooze-$scheduledDate-$time"

    private fun cancelByKey(c: Context, key: String) {
        val alarmManager = c.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getBroadcast(c, key.hashCode(), Intent(c, AlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pendingIntent != null) { alarmManager.cancel(pendingIntent); pendingIntent.cancel() }
    }

    private fun scheduleNextForTime(c: Context, time: String, meds: List<Medication>, zone: ZoneId): Boolean {
        val parsed = runCatching { LocalTime.parse(time) }.getOrNull() ?: return false
        val now = ZonedDateTime.now(zone)
        val candidates = meds.mapNotNull { med ->
            var from = now.toLocalDate()
            if (!from.atTime(parsed).atZone(zone).isAfter(now)) from = from.plusDays(1)
            ProgramRuleStore.nextActiveDate(c, med, from)?.let { date -> med to date }
        }
        val earliest = candidates.minOfOrNull { it.second } ?: return false
        val due = candidates.filter { it.second == earliest }.map { it.first }
        val trigger = earliest.atTime(parsed).atZone(zone).toInstant().toEpochMilli()
        scheduleAt(c, time, due, trigger, "group-$time", false, earliest.toString())
        return true
    }

    private fun scheduleAt(c: Context, time: String, meds: List<Medication>, triggerAtMillis: Long, requestKey: String, isSnooze: Boolean, scheduledDate: String) {
        if (meds.isEmpty()) return
        val alarmManager = c.getSystemService(AlarmManager::class.java)
        val names = meds.joinToString("|#|") { med -> med.name + if (med.dose.isBlank()) "" else " (${med.dose})" }
        val ids = meds.joinToString("|#|") { it.id }
        val deliveryId = "$requestKey|$triggerAtMillis"
        val intent = Intent(c, AlarmReceiver::class.java)
            .putExtra("time", time)
            .putExtra("names", names)
            .putExtra("ids", ids)
            .putExtra("isSnooze", isSnooze)
            .putExtra("scheduledDate", scheduledDate)
            .putExtra("deliveryId", deliveryId)
        val pendingIntent = PendingIntent.getBroadcast(c, requestKey.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent) }
        catch (_: SecurityException) { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent) }
    }

    fun scheduleSnoozeUntil(c: Context, time: String, meds: List<Medication>, triggerAtMillis: Long, scheduledDate: String = LocalDate.now().toString()): Long {
        val safeTrigger = maxOf(System.currentTimeMillis() + 1_000L, triggerAtMillis)
        scheduleAt(c, time, meds, safeTrigger, snoozeKey(time, scheduledDate), true, scheduledDate)
        return safeTrigger
    }

    fun scheduleSnoozeIfActive(c: Context, time: String, meds: List<Medication>, triggerAtMillis: Long, scheduledDate: String): Boolean {
        if (meds.isEmpty() || triggerAtMillis <= System.currentTimeMillis()) return false
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrNull() ?: return false
        if (DoseStateEngine.stateForTime(c, time, date).status != DoseSessionStatus.SNOOZED) return false
        scheduleSnoozeUntil(c, time, meds, triggerAtMillis, scheduledDate)
        if (DoseStateEngine.stateForTime(c, time, date).status != DoseSessionStatus.SNOOZED) {
            cancelSnooze(c, time, scheduledDate)
            return false
        }
        return true
    }

    fun snoozeGroup(c: Context, time: String, meds: List<Medication>, minutes: Int, scheduledDate: String = LocalDate.now().toString()): Long {
        val trigger = System.currentTimeMillis() + minutes * 60_000L
        scheduleSnoozeUntil(c, time, meds, trigger, scheduledDate)
        return trigger
    }

    fun restoreActiveSnoozes(c: Context) {
        SnoozeRecovery.reconcileToday(c)
    }
}

object AlarmDeliveryGuard {
    fun shouldDeliver(c: Context, time: String, scheduledDate: String, ids: List<String>, isSnooze: Boolean): Boolean {
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrNull() ?: return false
        val state = DoseStateEngine.stateForTime(c, time, date).status
        if (isSnooze) return state == DoseSessionStatus.SNOOZED
        if (state !in setOf(DoseSessionStatus.UNKNOWN, DoseSessionStatus.PENDING)) return false
        val current = Store.load(c)
        val candidates = if (ids.isNotEmpty()) current.filter { it.id in ids } else current.filter { time in it.times }
        return candidates.any { med -> time in med.times && ProgramRuleStore.isActiveOn(c, med.id, date) }
    }
}

object ActionDeliveryGuard {
    fun shouldApply(c: Context, action: String, time: String, scheduledDate: String): Boolean {
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrNull() ?: return false
        val state = DoseStateEngine.stateForTime(c, time, date).status
        return when (action) {
            "snooze" -> state == DoseSessionStatus.UNKNOWN || state == DoseSessionStatus.PENDING
            "taken", "missed" -> state == DoseSessionStatus.UNKNOWN || state == DoseSessionStatus.PENDING || state == DoseSessionStatus.SNOOZED
            else -> false
        }
    }
}

object DoseNotificationLifecycle {
    fun cancel(c: Context, time: String, scheduledDate: String) {
        c.getSystemService(NotificationManager::class.java)
            .cancel(("group-$scheduledDate-$time").hashCode())
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val time = i.getStringExtra("time") ?: return
        val intentNames = i.getStringExtra("names")?.split("|#|") ?: return
        val intentIds = i.getStringExtra("ids")?.split("|#|")?.filter { it.isNotBlank() } ?: emptyList()
        val scheduledDate = i.getStringExtra("scheduledDate") ?: LocalDate.now().toString()
        val intentIsSnooze = i.getBooleanExtra("isSnooze", false)
        val deliveryId = i.getStringExtra("deliveryId")?.takeIf { it.isNotBlank() }
            ?: "legacy-alarm|$scheduledDate|$time|${if (intentIsSnooze) "snooze" else "regular"}|${intentIds.sorted().joinToString(",")}"        
        val canonical = AlarmPresentationLedger.canonicalAlarmForRedelivery(c, deliveryId, time, scheduledDate)
        val canonicalMeds = canonical?.medications.orEmpty()
        val ids = if (canonicalMeds.isNotEmpty()) canonicalMeds.map { it.id }.filter { it.isNotBlank() } else intentIds
        val names = if (canonicalMeds.isNotEmpty()) canonicalMeds.map { med -> med.name + if (med.dose.isBlank()) "" else " (${med.dose})" } else intentNames
        val isSnooze = canonical?.snoozeUntil?.let { it > 0L } ?: intentIsSnooze

        if (!AlarmDeliveryGuard.shouldDeliver(c, time, scheduledDate, ids, isSnooze)) {
            if (canonical != null) AlarmPresentationLedger.markPresented(c, deliveryId)
            AlarmScheduler.scheduleAll(c, Store.load(c))
            return
        }

        val stored = Store.load(c).associateBy { it.id }
        val alarmMeds = if (canonicalMeds.isNotEmpty()) canonicalMeds else ids.mapNotNull { stored[it] }.ifEmpty { names.mapIndexed { index, label -> Medication("legacy-$index", label, "", listOf(time)) } }

        if (canonical != null) {
            if (AlarmPresentationLedger.isPresented(c, deliveryId)) return
        } else if (!Ntfy.sendEvent(c, "alarm", time, alarmMeds, scheduledDate, eventId = deliveryId)) {
            return
        }

        if (!AlarmDeliveryGuard.shouldDeliver(c, time, scheduledDate, ids, isSnooze)) {
            AlarmPresentationLedger.markPresented(c, deliveryId)
            AlarmScheduler.scheduleAll(c, Store.load(c))
            return
        }

        val channel = "medication"
        val notificationManager = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) notificationManager.createNotificationChannel(NotificationChannel(channel, I18n.t("channel"), NotificationManager.IMPORTANCE_HIGH))
        fun action(actionName: String): PendingIntent = PendingIntent.getBroadcast(c,("$scheduledDate|$time|$actionName").hashCode(),Intent(c,ActionReceiver::class.java).putExtra("action",actionName).putExtra("time",time).putExtra("names",names.joinToString("|#|")).putExtra("ids",ids.joinToString("|#|")).putExtra("scheduledDate",scheduledDate),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = names.joinToString(", ")
        val notification = NotificationCompat.Builder(c, channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("$time • ${I18n.t("med_count", names.size)}").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setPriority(NotificationCompat.PRIORITY_MAX).setAutoCancel(true).addAction(0,I18n.t("all_taken"),action("taken")).addAction(0,I18n.t("snooze30"),action("snooze")).addAction(0,I18n.t("notif_missed"),action("missed")).build()
        notificationManager.notify(("group-$scheduledDate-$time").hashCode(), notification)
        SmartEscalation.schedule(c, time, scheduledDate)
        AlarmScheduler.scheduleAll(c, Store.load(c))

        if (!AlarmDeliveryGuard.shouldDeliver(c, time, scheduledDate, ids, isSnooze)) {
            DoseNotificationLifecycle.cancel(c, time, scheduledDate)
            SmartEscalation.cancel(c, time, scheduledDate)
        }
        AlarmPresentationLedger.markPresented(c, deliveryId)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val action = i.getStringExtra("action") ?: return
        val time = i.getStringExtra("time") ?: return
        val scheduledDate = i.getStringExtra("scheduledDate") ?: LocalDate.now().toString()
        if (!ActionDeliveryGuard.shouldApply(c, action, time, scheduledDate)) return
        val names = i.getStringExtra("names")?.split("|#|") ?: emptyList()
        val ids = i.getStringExtra("ids")?.split("|#|") ?: emptyList()
        val stored = Store.load(c).associateBy { it.id }
        val resolvedMeds = ids.mapNotNull { stored[it] }.ifEmpty { names.mapIndexed { index, label -> Medication("legacy-$index", label, "", listOf(time)) } }
        val snoozeUntil = if (action == "snooze") System.currentTimeMillis() + 30 * 60_000L else 0L
        Ntfy.sendEvent(c, if (action == "snooze") "snoozed" else action, time, resolvedMeds, scheduledDate, snoozeUntil)
    }
}

object RecoveryPolicy {
    fun shouldRebuildRegularAlarms(action: String?, timezoneConfirmationPending: Boolean): Boolean =
        action != Intent.ACTION_TIMEZONE_CHANGED || !timezoneConfirmationPending
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_TIMEZONE_CHANGED) TravelGuard.onTimezonePossiblyChanged(c) else TravelGuard.initialize(c)
        val timezonePending = TravelGuard.pendingNotice(c) != null
        if (RecoveryPolicy.shouldRebuildRegularAlarms(i.action, timezonePending)) AlarmScheduler.scheduleAll(c, Store.load(c))
        AlarmScheduler.restoreActiveSnoozes(c)
        UndoRecovery.recoverCurrent(c)
        SmartEscalation.restore(c)
        DosefolkSyncScheduler.ensure(c)
        DosefolkSyncScheduler.kick(c)
    }
}

object Ntfy {
    private val terminalTypes = setOf("taken", "missed", "conflict_resolved_taken", "conflict_resolved_missed")
    private val directActionTypes = setOf("taken", "missed", "snoozed")
    private val eventLocks = ConcurrentHashMap<String, Any>()
    private val actionLocks = ConcurrentHashMap<String, Any>()

    fun sendEvent(
        c: Context,
        type: String,
        time: String,
        meds: List<Medication>,
        scheduledDate: String = LocalDate.now().toString(),
        snoozeUntil: Long = 0L,
        eventId: String? = null
    ): Boolean {
        val actionLock = actionLocks.computeIfAbsent("$scheduledDate|$time") { Any() }
        return synchronized(actionLock) {
            if (type in directActionTypes) {
                val action = if (type == "snoozed") "snooze" else type
                if (!ActionDeliveryGuard.shouldApply(c, action, time, scheduledDate)) return@synchronized false
            }

            val ownerId = OwnerScopeStore.ownerFor(c, meds)
            val meta = meds.mapNotNull { med -> if (ownerId == OwnerScopeStore.localOwnerId(c)) MedicationMetaStore.get(c, med.id) else MedicationMetaStore.remote(c, ownerId, med.id) }
            val event = DoseEvent(eventId ?: UUID.randomUUID().toString(), type, time, Store.myName(c), Store.topic(c),System.currentTimeMillis(), meds, "pending", EventStore.nextRevision(c), scheduledDate, snoozeUntil, ownerId, meta)
            if (!EventStore.appendIfAbsent(c, event)) return@synchronized false

            when {
                type in terminalTypes -> { AlarmScheduler.cancelSnooze(c, time, scheduledDate); SmartEscalation.cancel(c, time, scheduledDate); CareBatonStore.resolve(c, time, scheduledDate); DoseNotificationLifecycle.cancel(c, time, scheduledDate) }
                type == "snoozed" -> {
                    AlarmScheduler.scheduleSnoozeIfActive(c, time, meds, event.snoozeUntil, scheduledDate)
                    SmartEscalation.cancel(c, time, scheduledDate)
                    DoseNotificationLifecycle.cancel(c, time, scheduledDate)
                }
            }
            OwnerScopeStore.remember(c, event)
            PrnUsageLedger.observe(c, event)
            StockEngine.applyEvent(c, event)
            UndoRecovery.recoverEvent(c, event)
            thread { deliverEventBlocking(c.applicationContext, event) }
            DosefolkSyncScheduler.kick(c)
            true
        }
    }

    fun retryPending(c: Context) { DosefolkSyncScheduler.kick(c) }
    fun flushPendingBlocking(c: Context): Boolean {
        val pending = EventStore.pending(c).take(100)
        if (pending.isEmpty()) return true
        return pending.map { deliverEventBlocking(c.applicationContext, it) }.all { it }
    }

    private fun deliverEventBlocking(c: Context, event: DoseEvent): Boolean {
        val lock = eventLocks.computeIfAbsent(event.eventId) { Any() }
        return synchronized(lock) {
            if (EventStore.load(c).firstOrNull { it.eventId == event.eventId }?.syncState == "synced") return@synchronized true
            val payload = EventStore.payload(event).toString()
            val topics = (listOf(Store.topic(c)) + Store.people(c).map { it.topic }).filter { it.isNotBlank() }.distinct()
            var allDelivered = true
            topics.forEach { topic ->
                if (DeliveryLedger.delivered(c, event.eventId, topic)) return@forEach
                val ok = post(topic, "Dosefolk sync", payload, "min")
                if (ok) DeliveryLedger.markDelivered(c, event.eventId, topic) else allDelivered = false
            }
            if (allDelivered && topics.all { DeliveryLedger.delivered(c, event.eventId, it) }) { EventStore.markSynced(c, event.eventId); DeliveryLedger.clearEvent(c, event.eventId); eventLocks.remove(event.eventId, lock); true } else false
        }
    }

    fun sendTo(c: Context, topic: String, title: String, message: String) { AlertOutbox.enqueue(c.applicationContext, topic, title, message) }
    private fun post(topic: String, title: String, message: String, priority: String): Boolean = try {
        val connection = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
        connection.requestMethod="POST";connection.doOutput=true;connection.connectTimeout=10_000;connection.readTimeout=10_000
        connection.setRequestProperty("Title",title);connection.setRequestProperty("Priority",priority);connection.setRequestProperty("Content-Type","text/plain; charset=utf-8")
        connection.outputStream.use{it.write(message.toByteArray())};val ok=connection.responseCode in 200..299;if(ok)connection.inputStream.close() else connection.errorStream?.close();connection.disconnect();ok
    } catch (_: Exception) { false }
}
