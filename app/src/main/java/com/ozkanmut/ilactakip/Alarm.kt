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
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.concurrent.thread

object AlarmScheduler {
    fun scheduleAll(c: Context, meds: List<Medication>) {
        meds.flatMap { med -> med.times.map { it to med } }
            .groupBy({ it.first }, { it.second })
            .forEach { (time, list) -> scheduleDaily(c, time, list) }
    }

    private fun scheduleDaily(c: Context, time: String, meds: List<Medication>) {
        val parts = time.split(":")
        if (parts.size != 2) return
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(parts[0].toInt(), parts[1].toInt()).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        scheduleAt(c, time, meds, next.toInstant().toEpochMilli())
    }

    private fun scheduleAt(c: Context, time: String, meds: List<Medication>, triggerAtMillis: Long) {
        val alarmManager = c.getSystemService(AlarmManager::class.java)
        val names = meds.joinToString("|#|") { med -> med.name + if (med.dose.isBlank()) "" else " (${med.dose})" }
        val intent = Intent(c, AlarmReceiver::class.java).putExtra("time", time).putExtra("names", names)
        val pendingIntent = PendingIntent.getBroadcast(c, ("group-$time").hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    fun snoozeGroup(c: Context, time: String, meds: List<Medication>, minutes: Int) {
        val trigger = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        scheduleAt(c, time, meds, trigger)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val time = i.getStringExtra("time") ?: return
        val names = i.getStringExtra("names")?.split("|#|") ?: return
        val channel = "medication"
        val notificationManager = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) notificationManager.createNotificationChannel(NotificationChannel(channel, I18n.t("channel"), NotificationManager.IMPORTANCE_HIGH))

        fun action(actionName: String): PendingIntent = PendingIntent.getBroadcast(
            c,
            (time + actionName).hashCode(),
            Intent(c, ActionReceiver::class.java).putExtra("action", actionName).putExtra("time", time).putExtra("names", names.joinToString("|#|")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = names.joinToString(", ")
        val notification = NotificationCompat.Builder(c, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$time • ${I18n.t("med_count", names.size)}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .addAction(0, I18n.t("all_taken"), action("taken"))
            .addAction(0, I18n.t("snooze30"), action("snooze"))
            .addAction(0, I18n.t("notif_missed"), action("missed"))
            .build()
        notificationManager.notify(("group-$time").hashCode(), notification)

        val alarmMeds = names.mapIndexed { index, label -> Medication(index.toString(), label, "", listOf(time)) }
        Ntfy.sendEvent(c, "alarm", time, alarmMeds)
        SmartEscalation.schedule(c, time)
        AlarmScheduler.scheduleAll(c, Store.load(c))
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val action = i.getStringExtra("action") ?: return
        val time = i.getStringExtra("time") ?: return
        val names = i.getStringExtra("names")?.split("|#|") ?: emptyList()
        val meds = Store.load(c).filter { time in it.times }
        val resolvedMeds = meds.ifEmpty { names.mapIndexed { index, label -> Medication(index.toString(), label, "", listOf(time)) } }
        if (action == "snooze") {
            AlarmScheduler.snoozeGroup(c, time, resolvedMeds, 30)
            SmartEscalation.schedule(c, time)
        } else {
            SmartEscalation.cancel(c, time)
            CareBatonStore.resolve(c, time)
        }
        Ntfy.sendEvent(c, if (action == "snooze") "snoozed" else action, time, resolvedMeds)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        AlarmScheduler.scheduleAll(c, Store.load(c))
        Ntfy.retryPending(c)
        SyncEngine.pullOnce(c)
    }
}

object Ntfy {
    fun sendEvent(c: Context, type: String, time: String, meds: List<Medication>) {
        val event = DoseEvent(
            eventId = UUID.randomUUID().toString(),
            type = type,
            time = time,
            actor = Store.myName(c),
            actorTopic = Store.topic(c),
            timestamp = System.currentTimeMillis(),
            medications = meds,
            syncState = "pending"
        )
        EventStore.append(c, event)
        deliverEvent(c.applicationContext, event)
    }

    fun retryPending(c: Context) {
        EventStore.pending(c).take(100).forEach { deliverEvent(c.applicationContext, it) }
    }

    private fun deliverEvent(c: Context, event: DoseEvent) = thread {
        val payload = EventStore.payload(event).toString()
        val topics = (listOf(Store.topic(c)) + Store.people(c).map { it.topic }).distinct()
        val allDelivered = topics.all { topic -> post(topic, "Dosefolk sync", payload, "min") }
        if (allDelivered) EventStore.markSynced(c, event.eventId)
    }

    fun sendTo(topic: String, title: String, message: String) = thread {
        post(topic, title, message, "high")
    }

    private fun post(topic: String, title: String, message: String, priority: String): Boolean {
        return try {
            val connection = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Title", title)
            connection.setRequestProperty("Priority", priority)
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.outputStream.use { it.write(message.toByteArray()) }
            val ok = connection.responseCode in 200..299
            if (ok) connection.inputStream.close() else connection.errorStream?.close()
            connection.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }
}
