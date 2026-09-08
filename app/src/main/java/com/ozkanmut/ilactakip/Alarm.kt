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
import org.json.JSONArray
import org.json.JSONObject
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
        var next = now.toLocalDate()
            .atTime(parts[0].toInt(), parts[1].toInt())
            .atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        scheduleAt(c, time, meds, next.toInstant().toEpochMilli())
    }

    private fun scheduleAt(c: Context, time: String, meds: List<Medication>, triggerAtMillis: Long) {
        val alarmManager = c.getSystemService(AlarmManager::class.java)
        val names = meds.joinToString("|#|") { med ->
            med.name + if (med.dose.isBlank()) "" else " (${med.dose})"
        }
        val intent = Intent(c, AlarmReceiver::class.java)
            .putExtra("time", time)
            .putExtra("names", names)
        val pendingIntent = PendingIntent.getBroadcast(
            c,
            ("group-$time").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channel, I18n.t("channel"), NotificationManager.IMPORTANCE_HIGH)
            )
        }

        fun action(actionName: String): PendingIntent {
            return PendingIntent.getBroadcast(
                c,
                (time + actionName).hashCode(),
                Intent(c, ActionReceiver::class.java)
                    .putExtra("action", actionName)
                    .putExtra("time", time)
                    .putExtra("names", names.joinToString("|#|")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val text = names.joinToString(", ")
        val title = "$time • ${I18n.t("med_count", names.size)}"
        val notification = NotificationCompat.Builder(c, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .addAction(0, I18n.t("all_taken"), action("taken"))
            .addAction(0, I18n.t("snooze30"), action("snooze"))
            .addAction(0, I18n.t("notif_missed"), action("missed"))
            .build()

        notificationManager.notify(("group-$time").hashCode(), notification)

        val alarmMeds = names.mapIndexed { index, label ->
            Medication(index.toString(), label, "", listOf(time))
        }
        Ntfy.sendEvent(c, "alarm", time, alarmMeds)

        // Keep the next regular daily alarm alive independently of any snooze.
        AlarmScheduler.scheduleAll(c, Store.load(c))
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val action = i.getStringExtra("action") ?: return
        val time = i.getStringExtra("time") ?: return
        val names = i.getStringExtra("names")?.split("|#|") ?: emptyList()
        val meds = Store.load(c).filter { time in it.times }
        val resolvedMeds = meds.ifEmpty {
            names.mapIndexed { index, label -> Medication(index.toString(), label, "", listOf(time)) }
        }

        if (action == "snooze") {
            AlarmScheduler.snoozeGroup(c, time, resolvedMeds, 30)
        }
        Ntfy.sendEvent(c, if (action == "snooze") "snoozed" else action, time, resolvedMeds)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        AlarmScheduler.scheduleAll(c, Store.load(c))
    }
}

object Ntfy {
    fun sendEvent(c: Context, type: String, time: String, meds: List<Medication>) {
        val payload = JSONObject()
            .put("v", 2)
            .put("eventId", UUID.randomUUID().toString())
            .put("type", type)
            .put("time", time)
            .put("actor", Store.myName(c))
            .put("actorTopic", Store.topic(c))
            .put("timestamp", System.currentTimeMillis())
            .put(
                "medications",
                JSONArray(meds.map { med ->
                    JSONObject().put("id", med.id).put("name", med.name).put("dose", med.dose)
                })
            )

        sendTo(Store.topic(c), title(type), payload.toString())
        Store.people(c).forEach { person -> sendTo(person.topic, title(type), payload.toString()) }
    }

    private fun title(type: String): String = when (type) {
        "taken" -> I18n.t("event_taken")
        "missed" -> I18n.t("event_missed")
        "snoozed" -> I18n.t("event_snoozed")
        else -> I18n.t("event_alarm")
    }

    fun sendTo(topic: String, title: String, message: String) = thread {
        try {
            val connection = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Title", title)
            connection.setRequestProperty("Priority", "high")
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.outputStream.use { it.write(message.toByteArray()) }
            connection.inputStream.close()
            connection.disconnect()
        } catch (_: Exception) {
            // Delivery failures are handled by the local-first reliability layer in later revisions.
        }
    }
}
