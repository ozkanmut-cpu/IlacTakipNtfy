package com.ozkanmut.ilactakip

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

internal data class NtfyReprovisionUiModel(
    val titleTr: String,
    val bodyTr: String,
    val actionTr: String,
    val titleEn: String,
    val bodyEn: String,
    val actionEn: String
)

object NtfyReprovisionUx {
    internal fun model(reprovisionRequired: Boolean): NtfyReprovisionUiModel? =
        if (!reprovisionRequired) null else NtfyReprovisionUiModel(
            titleTr = "Bildirim bağlantısını yenile",
            bodyTr = "Güvenli bildirim bağlantısı artık geçerli değil. Size gönderilen yeni bağlantı linkini bu telefonda açın.",
            actionTr = "Nasıl yenilerim?",
            titleEn = "Refresh notification connection",
            bodyEn = "The secure notification connection is no longer valid. Open the new connection link sent to you on this phone.",
            actionEn = "How do I refresh it?"
        )

    internal fun isTurkish(): Boolean = Locale.getDefault().language.equals("tr", ignoreCase = true)
}

object NtfyReprovisionNotifier {
    private const val CHANNEL_ID = "dosefolk_reprovision"
    private const val NOTIFICATION_ID = 40901

    fun show(c: Context) {
        val context = c.applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val model = NtfyReprovisionUx.model(true) ?: return
        val tr = NtfyReprovisionUx.isTurkish()
        val title = if (tr) model.titleTr else model.titleEn
        val body = if (tr) model.bodyTr else model.bodyEn
        val action = if (tr) model.actionTr else model.actionEn
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                if (tr) "Bağlantı güvenliği" else "Connection security",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = if (tr) "Güvenli bildirim bağlantısı yenileme uyarıları" else "Secure notification connection refresh alerts"
            }
        )

        val helpIntent = Intent(context, NtfyReprovisionHelpActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val helpPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            helpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(helpPendingIntent)
            .addAction(Notification.Action.Builder(null, action, helpPendingIntent).build())
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(c: Context) {
        c.applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

class NtfyReprovisionHelpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tr = NtfyReprovisionUx.isTurkish()
        val model = requireNotNull(NtfyReprovisionUx.model(true))
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(TextView(this).apply {
            text = if (tr) model.titleTr else model.titleEn
            textSize = 24f
            setPadding(0, 0, 0, dp(16))
        })
        root.addView(TextView(this).apply {
            text = if (tr) {
                "1. Size gönderilen yeni Dosefolk bağlantı linkini bu telefonda açın.\n\n" +
                    "2. Dosefolk açıldığında bağlantı otomatik olarak güvenli biçimde yenilenir.\n\n" +
                    "3. Yeni linkiniz yoksa bağlantıyı yöneten kişiden yeni bir link isteyin.\n\n" +
                    "Eski token veya topic bilgisini elle girmeniz gerekmez."
            } else {
                "1. Open the new Dosefolk connection link sent to you on this phone.\n\n" +
                    "2. When Dosefolk opens, the secure connection is refreshed automatically.\n\n" +
                    "3. If you do not have a new link, ask the person managing the connection for a new one.\n\n" +
                    "You do not need to enter old tokens or topic details manually."
            }
            textSize = 17f
            setPadding(0, 0, 0, dp(24))
        })
        root.addView(Button(this).apply {
            text = if (tr) "Kapat" else "Close"
            setOnClickListener { finish() }
        })
        setContentView(root)
    }
}
