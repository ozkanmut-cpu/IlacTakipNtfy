package com.ozkanmut.ilactakip

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.util.UUID

@Composable
fun SmartImportScreen(c: Context, onDone: (List<Medication>) -> Unit) {
    var source by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<SmartImportDraft?>(null) }
    var rows by remember { mutableStateOf<List<ImportDraftMedication>>(emptyList()) }

    fun analyze() {
        val parsed = SmartImport.parse(source)
        draft = parsed
        rows = parsed.medications
    }

    fun commit() {
        val valid = rows.filter { it.name.isNotBlank() && it.times.isNotEmpty() }
        if (valid.isEmpty()) return
        val existing = Store.load(c)
        val created = valid.map { d -> Medication(UUID.randomUUID().toString(), d.name.trim(), d.dose.trim(), d.times.distinct().sorted()) }
        val signatures = existing.map { it.name.trim().lowercase() to it.times.sorted() }.toSet()
        val fresh = created.filter { (it.name.trim().lowercase() to it.times.sorted()) !in signatures }
        Store.save(c, existing + fresh)
        fresh.zip(valid).forEach { (med, d) ->
            if (d.routineLabel.isNotBlank()) ProgramRuleStore.save(c, ProgramRule(med.id, routineLabel = d.routineLabel))
        }
        onDone(fresh)
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(t("Akıllı İçe Aktar", "Smart Import"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(t("Listeyi yapıştır. Dosefolk yalnız eksik olan bilgiyi sorar; hiçbir ilaç onaysız eklenmez.", "Paste a list. Dosefolk asks only for missing information; nothing is added without confirmation."))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = source,
                onValueChange = { source = it; draft = null; rows = emptyList() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                label = { Text(t("İlaç listesi veya OCR metni", "Medication list or OCR text")) },
                placeholder = { Text("Vasoxen 5 mg 20:00\nLasix 40 mg sabah") }
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = ::analyze, enabled = source.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(t("Taslağı çıkar", "Create draft"))
            }
        }

        draft?.warnings?.forEach { warning -> item { AssistChip(onClick = {}, label = { Text("⚠ $warning") }) } }

        if (rows.isNotEmpty()) {
            item { Text(t("Önizleme", "Preview"), fontWeight = FontWeight.Bold) }
            itemsIndexed(rows) { index, row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.name, fontWeight = FontWeight.Bold)
                        if (row.dose.isNotBlank()) Text(row.dose)
                        if (row.times.isNotEmpty()) Text(row.times.joinToString(" • "))
                        if (row.routineLabel.isNotBlank()) Text(row.routineLabel, style = MaterialTheme.typography.bodySmall)
                        if (row.times.isEmpty()) {
                            Text(t("Saat eksik", "Time is missing"), fontWeight = FontWeight.Bold)
                            Button(onClick = {
                                val now = LocalTime.now()
                                TimePickerDialog(c, { _, h, m ->
                                    val value = String.format("%02d:%02d", h, m)
                                    rows = rows.mapIndexed { i, r -> if (i == index) r.copy(times = listOf(value), missing = r.missing - "time") else r }
                                }, now.hour, now.minute, true).show()
                            }) { Text(t("Saati seç", "Choose time")) }
                        }
                        Text(t("Güven: ${(row.confidence * 100).toInt()}%", "Confidence: ${(row.confidence * 100).toInt()}%"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val missingCount = rows.count { it.times.isEmpty() || it.name.isBlank() }
            item {
                if (missingCount > 0) Text(t("$missingCount kayıtta eksik bilgi var.", "$missingCount item(s) still need information."))
                Button(
                    onClick = ::commit,
                    enabled = rows.isNotEmpty() && missingCount == 0,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(t("Onayla ve ekle", "Confirm and add")) }
            }
        }
    }
}

private fun t(tr: String, en: String) = if (I18n.language() == "tr") tr else en
