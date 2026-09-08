package com.ozkanmut.ilactakip

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun ProgramRulesScreen(c: Context, meds: List<Medication>) {
    var editingId by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(txt("Program", "Schedule"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(txt("Yalnız farklı bir düzen gerekiyorsa değiştir. Normal ilaçlar her gün çalışır.", "Change only when a medication needs a special schedule. Normal medications run every day."))
        }
        items(meds, key = { it.id }) { med ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(med.name, fontWeight = FontWeight.Bold)
                    val description = ProgramRuleStore.describe(c, med.id)
                    Text(if (description.isBlank()) txt("Her gün", "Every day") else description)
                    TextButton(onClick = { editingId = if (editingId == med.id) null else med.id }) {
                        Text(if (editingId == med.id) txt("Kapat", "Close") else txt("Düzeni değiştir", "Change schedule"))
                    }
                    if (editingId == med.id) ProgramRuleEditor(c, med) { editingId = null }
                }
            }
        }
    }
}

@Composable
private fun ProgramRuleEditor(c: Context, med: Medication, onSaved: () -> Unit) {
    val initial = remember(med.id) { ProgramRuleStore.get(c, med.id) }
    var weekdays by remember(med.id) { mutableStateOf(initial.weekdays) }
    var everyN by remember(med.id) { mutableStateOf(initial.everyNDays.toString()) }
    var start by remember(med.id) { mutableStateOf(initial.startDate.orEmpty()) }
    var end by remember(med.id) { mutableStateOf(initial.endDate.orEmpty()) }
    var label by remember(med.id) { mutableStateOf(initial.routineLabel) }
    val dayLabels = if (I18n.language() == "tr") listOf("Pzt","Sal","Çar","Per","Cum","Cmt","Paz") else listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")

    Text(txt("Günler", "Days"), fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        dayLabels.forEachIndexed { index, name ->
            val day = index + 1
            FilterChip(
                selected = day in weekdays,
                onClick = { weekdays = if (day in weekdays) weekdays - day else weekdays + day },
                label = { Text(name) }
            )
        }
    }
    Text(txt("Hiç gün seçmezsen her gün.", "No selected days means every day."), style = MaterialTheme.typography.labelSmall)

    OutlinedTextField(
        value = everyN,
        onValueChange = { everyN = it.filter(Char::isDigit).take(3) },
        label = { Text(txt("Kaç günde bir", "Every N days")) },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = start,
        onValueChange = { start = it.take(10) },
        label = { Text(txt("Başlangıç (YYYY-MM-DD), isteğe bağlı", "Start (YYYY-MM-DD), optional")) },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = end,
        onValueChange = { end = it.take(10) },
        label = { Text(txt("Bitiş (YYYY-MM-DD), isteğe bağlı", "End (YYYY-MM-DD), optional")) },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text(txt("Rutin etiketi, isteğe bağlı", "Routine label, optional")) },
        modifier = Modifier.fillMaxWidth()
    )

    val n = everyN.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val validStart = start.isBlank() || runCatching { LocalDate.parse(start) }.isSuccess
    val validEnd = end.isBlank() || runCatching { LocalDate.parse(end) }.isSuccess
    val rangeOk = if (validStart && validEnd && start.isNotBlank() && end.isNotBlank()) !LocalDate.parse(end).isBefore(LocalDate.parse(start)) else true

    if (!validStart || !validEnd || !rangeOk) Text(txt("Tarihleri kontrol et.", "Check the dates."), color = MaterialTheme.colorScheme.error)
    Button(
        enabled = validStart && validEnd && rangeOk,
        onClick = {
            ProgramRuleStore.save(
                c,
                ProgramRule(
                    medicationId = med.id,
                    weekdays = weekdays,
                    startDate = start.ifBlank { null },
                    endDate = end.ifBlank { null },
                    everyNDays = n,
                    anchorDate = start.ifBlank { initial.anchorDate ?: LocalDate.now().toString() },
                    routineLabel = label.trim()
                )
            )
            onSaved()
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(txt("Kaydet", "Save")) }
}

private fun txt(tr: String, en: String) = if (I18n.language() == "tr") tr else en
