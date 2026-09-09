package com.ozkanmut.ilactakip

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Composable
fun AddMedicationDialogEnhanced(c: Context, close: () -> Unit, add: (Medication) -> Unit) {
    var name by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var times by remember { mutableStateOf(listOf<String>()) }
    var prnOnly by remember { mutableStateOf(false) }
    var shortCourse by remember { mutableStateOf(false) }
    var durationDays by remember { mutableStateOf("") }
    var minInterval by remember { mutableStateOf("") }
    var maxPerDay by remember { mutableStateOf("") }

    val duration = durationDays.toIntOrNull()?.takeIf { it > 0 }
    val canSave = name.isNotBlank() && (prnOnly || times.isNotEmpty()) && (!shortCourse || duration != null)

    AlertDialog(
        onDismissRequest = close,
        title = { Text(I18n.t("new_med")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(I18n.t("med_name")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dose, { dose = it }, label = { Text(I18n.t("dose_note")) }, modifier = Modifier.fillMaxWidth())

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (I18n.language() == "tr") "Sadece gerektiğinde (PRN)" else "As needed only (PRN)")
                    Switch(checked = prnOnly, onCheckedChange = { value ->
                        prnOnly = value
                        if (value) times = emptyList()
                    })
                }

                if (prnOnly) {
                    OutlinedTextField(
                        minInterval,
                        { minInterval = it.filter(Char::isDigit) },
                        label = { Text(if (I18n.language() == "tr") "Minimum aralık, dk (opsiyonel)" else "Minimum interval, min (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        maxPerDay,
                        { maxPerDay = it.filter(Char::isDigit) },
                        label = { Text(if (I18n.language() == "tr") "Günlük maksimum (opsiyonel)" else "Daily maximum (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    times.forEach { Text(it) }
                    OutlinedButton(onClick = {
                        val now = LocalTime.now()
                        TimePickerDialog(c, { _, h, m ->
                            times = (times + String.format("%02d:%02d", h, m)).distinct().sorted()
                        }, now.hour, now.minute, true).show()
                    }) { Text(I18n.t("add_time")) }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (I18n.language() == "tr") "Kısa süreli kullanım" else "Short-term course")
                    Switch(checked = shortCourse, onCheckedChange = { shortCourse = it })
                }
                if (shortCourse) {
                    OutlinedTextField(
                        durationDays,
                        { durationDays = it.filter(Char::isDigit) },
                        label = { Text(if (I18n.language() == "tr") "Kaç gün kullanılacak?" else "How many days?") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    duration?.let {
                        val start = LocalDate.now()
                        val end = start.plusDays((it - 1).toLong())
                        Text(if (I18n.language() == "tr") "$start – $end arasında aktif" else "Active from $start to $end")
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = {
                val med = Medication(
                    UUID.randomUUID().toString(),
                    name.trim(),
                    dose.trim(),
                    if (prnOnly) emptyList() else times
                )
                add(med)

                if (prnOnly) {
                    PrnEngine.create(c, med, minInterval.toIntOrNull(), maxPerDay.toIntOrNull())
                }

                if (shortCourse && duration != null) {
                    val start = LocalDate.now()
                    ProgramRuleStore.save(
                        c,
                        ProgramRule(
                            medicationId = med.id,
                            startDate = start.toString(),
                            endDate = start.plusDays((duration - 1).toLong()).toString(),
                            anchorDate = start.toString()
                        )
                    )
                }
            }) { Text(I18n.t("save")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(I18n.t("cancel")) } }
    )
}
