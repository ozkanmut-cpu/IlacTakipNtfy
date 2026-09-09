package com.ozkanmut.ilactakip

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Emits one low-stock notification per low-stock episode.
 * The latch is reset only after stock rises above the configured threshold,
 * so repeated dose events and ntfy history cannot spam the user.
 */
object LowStockNotifier {
    private const val PREFS = "dosefolk_low_stock_alerts"
    private const val CHANNEL_ID = "dosefolk_low_stock"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(stock: MedicationStock) = "alerted_${stock.medicationId}"

    fun evaluate(c: Context, stock: MedicationStock) {
        val p = prefs(c)
        val low = stock.remainingDoses <= stock.lowThreshold
        val alerted = p.getBoolean(key(stock), false)
        if (!low) {
            if (alerted) p.edit().remove(key(stock)).commit()
            return
        }
        if (alerted) return

        // Latch before notifying. If Android blocks notifications, the stock engine still won't
        // generate a notification storm on every subsequent dose event.
        p.edit().putBoolean(key(stock), true).commit()
        notify(c, stock)
    }

    fun reset(c: Context, medicationId: String) {
        prefs(c).edit().remove("alerted_$medicationId").commit()
    }

    private fun notify(c: Context, stock: MedicationStock) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    if (I18n.language() == "tr") "Düşük ilaç stoku" else "Low medication stock",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = if (I18n.language() == "tr") "İlaç stoku belirlenen eşiğe düştüğünde uyarır." else "Alerts when medication stock reaches its configured threshold." }
            )
        }
        val title = if (I18n.language() == "tr") "İlaç stoku azalıyor" else "Medication stock is low"
        val text = if (I18n.language() == "tr") "${stock.medicationName}: ${stock.remainingDoses} kaldı" else "${stock.medicationName}: ${stock.remainingDoses} remaining"
        val notification = NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(c).notify(("low_stock_${stock.medicationId}").hashCode(), notification)
    }
}
