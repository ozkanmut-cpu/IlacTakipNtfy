package com.ozkanmut.ilactakip

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.time.LocalDate

/**
 * Silent reliability check. The normal state is no UI at all; only actionable
 * problems are surfaced to the user.
 */
data class DosefolkIssue(
    val id: String,
    val title: String,
    val detail: String,
    val fix: (Context) -> Unit
)

object DosefolkCheck {
    private const val SYNC_STALE_MS = 6 * 60 * 60 * 1000L

    fun issues(context: Context): List<DosefolkIssue> {
        val result = mutableListOf<DosefolkIssue>()

        TravelGuard.pendingNotice(context)?.let { notice ->
            result += DosefolkIssue(
                id = "travel_timezone",
                title = tr("Saat dilimi değişti", "Timezone changed"),
                detail = tr(
                    "${notice.fromZone} → ${notice.toZone}. Dosefolk yeni saat diliminde alarmları yeniden kurdu; ilaç saatlerinin yerel saate göre devam etmesinin doğru olduğunu kontrol et.",
                    "${notice.fromZone} → ${notice.toZone}. Dosefolk rebuilt alarms in the new timezone; verify that continuing on local clock time is correct for this regimen."
                )
            ) { c -> TravelGuard.acknowledge(c) }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            result += DosefolkIssue(
                id = "notifications",
                title = tr("Dosefolk bildirimleri kapalı", "Dosefolk notifications are off"),
                detail = tr(
                    "Bildirim izni olmadan ilaç hatırlatmaları görünemez.",
                    "Medication reminders cannot appear until notifications are allowed."
                )
            ) { c ->
                c.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        if (Build.VERSION.SDK_INT >= 31) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                result += DosefolkIssue(
                    id = "exact_alarm",
                    title = tr("Kesin alarm izni gerekli", "Precise alarms need permission"),
                    detail = tr(
                        "Android kesin alarm izni olmadan ilaç hatırlatmalarını geciktirebilir.",
                        "Android may delay medication reminders unless precise alarms are allowed."
                    )
                ) { c ->
                    runCatching {
                        c.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:${c.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure {
                        c.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${c.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }

        // Do not nag users who only use local reminders. Battery/background limits mainly
        // threaten ntfy/WorkManager catch-up, so surface them only when Circle is in use.
        if (Store.people(context).isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 28) {
                val activityManager = context.getSystemService(ActivityManager::class.java)
                if (activityManager.isBackgroundRestricted) {
                    result += DosefolkIssue(
                        id = "background_restricted",
                        title = tr("Arka plan çalışması kısıtlı", "Background activity is restricted"),
                        detail = tr(
                            "Android Dosefolk'un Circle güncellemelerini arka planda geciktirebilir. Uygulama pil/arka plan ayarını 'Kısıtlanmamış' veya eşdeğer seçeneğe getir.",
                            "Android may delay Dosefolk Circle updates in the background. Set the app's battery/background setting to Unrestricted or the equivalent option."
                        )
                    ) { c -> openAppDetails(c) }
                }
            }

            if (Build.VERSION.SDK_INT >= 23) {
                val power = context.getSystemService(PowerManager::class.java)
                if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
                    result += DosefolkIssue(
                        id = "battery_optimization",
                        title = tr("Pil optimizasyonu Circle'ı geciktirebilir", "Battery optimization may delay Circle"),
                        detail = tr(
                            "Telefon Dosefolk'u uykuya alırsa ntfy senkronu gecikebilir. Pil optimizasyonu listesinden Dosefolk için kısıtlamayı kaldır.",
                            "If Android puts Dosefolk to sleep, ntfy sync may be delayed. Remove Dosefolk from battery optimization restrictions."
                        )
                    ) { c ->
                        runCatching {
                            c.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure { openAppDetails(c) }
                    }
                }
            }
        }

        val pending = EventStore.pending(context).size
        if (pending > 0) {
            result += DosefolkIssue(
                id = "pending_sync",
                title = tr("$pending kayıt senkronizasyon bekliyor", "$pending event(s) are waiting to sync"),
                detail = tr(
                    "Kayıtlar telefonda güvende. Dosefolk ağ bağlantısı olduğunda yeniden gönderecek.",
                    "The records are safe on this phone. Dosefolk will retry when network access is available."
                )
            ) { c -> DosefolkSyncScheduler.kick(c) }
        }

        val pendingAlerts = AlertOutbox.actionablePendingCount(context)
        if (pendingAlerts > 0) {
            result += DosefolkIssue(
                id = "pending_alerts",
                title = tr("$pendingAlerts takipçi uyarısı gönderilmeyi bekliyor", "$pendingAlerts caregiver alert(s) are waiting to send"),
                detail = tr(
                    "Uyarılar telefonda kalıcı olarak saklandı. Ağ bağlantısı geldiğinde otomatik yeniden gönderilecek.",
                    "The alerts are durably stored on this phone and will retry automatically when network access returns."
                )
            ) { c -> DosefolkSyncScheduler.kick(c) }
        }

        val unsupportedVersion = InboundProtocolHealth.unsupportedVersion(context)
        if (unsupportedVersion > IncomingEventGuard.MAX_PROTOCOL_VERSION) {
            result += DosefolkIssue(
                id = "protocol_update_required",
                title = tr("Dosefolk güncellenmeli", "Dosefolk needs an update"),
                detail = tr(
                    "Circle'dan bu sürümün anlayamadığı daha yeni bir veri biçimi (v$unsupportedVersion) geldi. Yerel alarmlar çalışmaya devam eder; Circle güncellemelerini eksiksiz almak için uygulamayı güncelle.",
                    "Circle received a newer data format (v$unsupportedVersion) that this version cannot understand. Local alarms continue to work; update Dosefolk to receive all Circle changes."
                )
            ) { c -> openStoreListing(c) }
        }

        if (Store.people(context).isNotEmpty()) {
            val last = SyncEngine.lastSuccess(context)
            if (last == 0L || System.currentTimeMillis() - last > SYNC_STALE_MS) {
                result += DosefolkIssue(
                    id = "stale_sync",
                    title = tr("Circle senkronu gecikmiş", "Circle sync is stale"),
                    detail = tr(
                        "Dosefolk bir süredir Circle güncellemesi alamadı. Yerel alarmlar çalışmaya devam eder.",
                        "Dosefolk has not completed a Circle sync recently. Local alarms continue to work."
                    )
                ) { c -> DosefolkSyncScheduler.kick(c) }
            }
        }

        val meds = Store.load(context)
        if (meds.isNotEmpty()) {
            val expected = meds
                .flatMap { med -> med.times.map { it to med } }
                .groupBy({ it.first }, { it.second })
                .filterValues { group ->
                    group.any { med -> ProgramRuleStore.nextActiveDate(context, med, LocalDate.now()) != null }
                }
                .keys
            val scheduled = context
                .getSharedPreferences("dosefolk_alarm_scheduler", Context.MODE_PRIVATE)
                .getStringSet("scheduled_times", emptySet())
                .orEmpty()
            if (expected != scheduled) {
                result += DosefolkIssue(
                    id = "alarm_plan",
                    title = tr("Alarm planı onarılmalı", "Alarm plan needs repair"),
                    detail = tr(
                        "Kayıtlı ilaç saatleri ile Android'deki alarm planı eşleşmiyor.",
                        "Saved medication times do not match the Android alarm plan."
                    )
                ) { c -> AlarmScheduler.scheduleAll(c, Store.load(c)) }
            }
        }

        return result
    }

    private fun openStoreListing(c: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${c.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { c.startActivity(market) }.onFailure { openAppDetails(c) }
    }

    private fun openAppDetails(c: Context) {
        c.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun tr(tr: String, en: String) = if (I18n.language() == "tr") tr else en
}
