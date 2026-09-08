package com.ozkanmut.ilactakip

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

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
    fun issues(context: Context): List<DosefolkIssue> {
        val result = mutableListOf<DosefolkIssue>()

        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            result += DosefolkIssue(
                id = "notifications",
                title = "Dosefolk notifications are off",
                detail = "Medication reminders cannot appear until notifications are allowed."
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
                    title = "Precise alarms need permission",
                    detail = "Android may delay medication reminders unless precise alarms are allowed."
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

        return result
    }
}
