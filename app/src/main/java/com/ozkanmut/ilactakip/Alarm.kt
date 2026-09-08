package com.ozkanmut.ilactakip

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL
import java.time.*
import kotlin.concurrent.thread

object AlarmScheduler {
    fun scheduleAll(context: Context, meds: List<Medication>) {
        val am = context.getSystemService(AlarmManager::class.java)
        meds.forEach { med -> med.times.forEach { time ->
            val parts = time.split(":"); val now = ZonedDateTime.now()
            var next = now.toLocalDate().atTime(parts[0].toInt(), parts[1].toInt()).atZone(now.zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val intent = Intent(context, AlarmReceiver::class.java).putExtra("id", med.id).putExtra("name", med.name).putExtra("time", time)
            val pi = PendingIntent.getBroadcast(context, (med.id + time).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pi) }
            catch (_: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pi) }
        }}
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val name = intent.getStringExtra("name") ?: "İlaç"
        val time = intent.getStringExtra("time") ?: ""
        val channelId = "medication"
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(channelId, "İlaç hatırlatmaları", NotificationManager.IMPORTANCE_HIGH))

        val takenIntent = Intent(context, ActionReceiver::class.java).putExtra("action", "taken").putExtra("name", name).putExtra("time", time)
        val missedIntent = Intent(context, ActionReceiver::class.java).putExtra("action", "missed").putExtra("name", name).putExtra("time", time)
        val takenPi = PendingIntent.getBroadcast(context, (id+time+"t").hashCode(), takenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val missedPi = PendingIntent.getBroadcast(context, (id+time+"m").hashCode(), missedIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("İlaç saati: $name")
            .setContentText("$time dozu. İçtiysen 'İçildi'ye bas.")
            .setPriority(NotificationCompat.PRIORITY_MAX).setAutoCancel(true)
            .addAction(0, "İçildi", takenPi).addAction(0, "İçilmedi", missedPi).build()
        nm.notify((id+time).hashCode(), n)
        Ntfy.send(context, "İLAÇ SAATİ", "$name - $time | Hatırlatma oluştu")
        AlarmScheduler.scheduleAll(context, Store.load(context))
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: "İlaç"; val time = intent.getStringExtra("time") ?: ""
        val taken = intent.getStringExtra("action") == "taken"
        Ntfy.send(context, if (taken) "İLAÇ İÇİLDİ" else "İLAÇ İÇİLMEDİ", "$name - $time")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { AlarmScheduler.scheduleAll(context, Store.load(context)) }
}

object Ntfy {
    fun send(context: Context, title: String, message: String) = thread {
        try {
            val topic = Store.topic(context); val c = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Title", title); c.setRequestProperty("Priority", "high")
            c.outputStream.use { it.write(message.toByteArray(Charsets.UTF_8)) }; c.inputStream.close(); c.disconnect()
        } catch (_: Exception) { }
    }
}
