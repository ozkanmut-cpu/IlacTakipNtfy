package com.ozkanmut.ilactakip

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/** One notification per active SGK refill cycle. A newer cycle replaces/cancels the old alert. */
object PrescriptionNotifier {
    private const val PREFS = "dosefolk_refill_alerts"
    private const val CHANNEL_ID = "dosefolk_refill"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun evaluate(c: Context, today: LocalDate = LocalDate.now()) {
        val context = c.applicationContext
        val current = PrescriptionLifecycle.current(context)
        val activeKeys = current.map(::medKey).toSet()
        val p = prefs(context)

        // Remove notification/latch state for medications no longer present in the current SGK set.
        p.all.keys.filter { it.startsWith("cycle|") }.forEach { storageKey ->
            val key = storageKey.removePrefix("cycle|")
            if (key !in activeKeys) {
                NotificationManagerCompat.from(context).cancel(notificationId(key))
                p.edit().remove("cycle|$key").remove("alerted|$key").commit()
            }
        }

        current.forEach { r ->
            val key = medKey(r)
            val cycle = cycleKey(r)
            val previousCycle = p.getString("cycle|$key", "").orEmpty()
            if (previousCycle != cycle) {
                NotificationManagerCompat.from(context).cancel(notificationId(key))
                p.edit().putString("cycle|$key", cycle).remove("alerted|$key").commit()
            }

            val eligible = r.eligibleDate()
            val due = r.continuous && eligible != null && !eligible.isAfter(today)
            if (!due) {
                NotificationManagerCompat.from(context).cancel(notificationId(key))
                return@forEach
            }
            if (p.getString("alerted|$key", "") == cycle) return@forEach

            // Latch before notifying so periodic workers cannot spam when notification permission is denied.
            p.edit().putString("alerted|$key", cycle).commit()
            notify(context, key, r)
        }
    }

    private fun notify(c: Context, key: String, record: PrescriptionRecord) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                if (I18n.language() == "tr") "Reçete / yeniden temin" else "Prescription / refill",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = if (I18n.language() == "tr") "SGK yeniden temin tarihi geldiğinde bir kez uyarır." else "Alerts once when SGK refill eligibility begins."
            })
        }
        val title = if (I18n.language() == "tr") "Yeniden temin zamanı" else "Refill available"
        val text = if (I18n.language() == "tr") "${record.medicationName} yeniden temin edilebilir." else "${record.medicationName} is eligible for refill."
        val notification = NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(c).notify(notificationId(key), notification)
    }

    private fun cycleKey(r: PrescriptionRecord) = listOf(r.prescriptionNo, r.fillDate, r.doseEndDate).joinToString("|")
    private fun medKey(r: PrescriptionRecord): String = if (r.medicationId.isNotBlank()) "id_${r.medicationId}" else "name_${normalize(r.medicationName)}"
    private fun notificationId(key: String) = ("refill_$key").hashCode()
    private fun normalize(v: String) = Normalizer.normalize(v, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
}
