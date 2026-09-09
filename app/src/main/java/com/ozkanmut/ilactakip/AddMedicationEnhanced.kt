package com.ozkanmut.ilactakip

import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMedicationDialogEnhanced(c: Context, close: () -> Unit, add: (Medication) -> Unit) {
    var name by remember { mutableStateOf("") }
    var doseNote by remember { mutableStateOf("") }
    var times by remember { mutableStateOf(listOf<String>()) }
    var prnOnly by remember { mutableStateOf(false) }
    var shortCourse by remember { mutableStateOf(false) }
    var durationDays by remember { mutableStateOf("") }
    var minInterval by remember { mutableStateOf("") }
    var maxPerDay by remember { mutableStateOf("") }
    var form by remember { mutableStateOf(MedicationForm.OTHER) }
    var quantity by remember { mutableStateOf("") }
    var site by remember { mutableStateOf("") }
    var packCount by remember { mutableStateOf("") }
    var packUnit by remember { mutableStateOf("") }
    var formMenu by remember { mutableStateOf(false) }
    var suggestionText by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("manual") }

    fun applySuggestion(ocr: String = "") {
        val s = MedicationSuggestionEngine.suggest(name, ocr)
        if (s.form != MedicationForm.OTHER) form = s.form
        if (quantity.isBlank()) s.quantity?.let { quantity = if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
        if (site.isBlank() && s.administrationSite.isNotBlank()) site = s.administrationSite
        if (packCount.isBlank()) s.packageCount?.let { packCount = it.toString() }
        if (packUnit.isBlank() && s.packageUnit.isNotBlank()) packUnit = s.packageUnit
        if (name.isBlank() && s.suggestedName.isNotBlank()) name = s.suggestedName
        suggestionText = if (I18n.language() == "tr") "Öneriler dolduruldu; kaydetmeden önce kontrol et." else "Suggestions filled in; review before saving."
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ImportSourceReader.read(c, uri) { result ->
            if (result.text.isNotBlank()) {
                source = "box_photo"
                applySuggestion(result.text)
            } else suggestionText = result.error ?: if (I18n.language() == "tr") "Kutudan bilgi okunamadı." else "No information could be read from the box."
        }
    }

    val duration = durationDays.toIntOrNull()?.takeIf { it > 0 }
    val canSave = name.isNotBlank() && (prnOnly || times.isNotEmpty()) && (!shortCourse || duration != null)
    val unit = if (I18n.language() == "tr") form.unitTr else form.unitEn

    AlertDialog(
        onDismissRequest = close,
        title = { Text(I18n.t("new_med")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; if (it.length >= 3) applySuggestion() }, label = { Text(I18n.t("med_name")) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (I18n.language() == "tr") "Kutu fotoğrafından öner" else "Suggest from box photo")
                }
                suggestionText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                ExposedDropdownMenuBox(expanded = formMenu, onExpandedChange = { formMenu = !formMenu }) {
                    OutlinedTextField(
                        value = formLabel(form), onValueChange = {}, readOnly = true,
                        label = { Text(if (I18n.language() == "tr") "Uygulama biçimi" else "Form") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(formMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = formMenu, onDismissRequest = { formMenu = false }) {
                        MedicationForm.entries.forEach { f -> DropdownMenuItem(text = { Text(formLabel(f)) }, onClick = { form = f; formMenu = false }) }
                    }
                }
                OutlinedTextField(quantity, { quantity = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' } }, label = { Text(if (I18n.language() == "tr") "Kullanım miktarı ($unit)" else "Amount ($unit)") }, modifier = Modifier.fillMaxWidth())
                if (form == MedicationForm.DROP || form == MedicationForm.CREAM || form == MedicationForm.INJECTION) {
                    OutlinedTextField(site, { site = it }, label = { Text(if (I18n.language() == "tr") "Uygulama yeri (opsiyonel)" else "Administration site (optional)") }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(doseNote, { doseNote = it }, label = { Text(I18n.t("dose_note")) }, modifier = Modifier.fillMaxWidth())

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(packCount, { packCount = it.filter(Char::isDigit) }, label = { Text(if (I18n.language() == "tr") "Kutudaki adet" else "Pack count") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(packUnit, { packUnit = it }, label = { Text(if (I18n.language() == "tr") "Birim" else "Unit") }, modifier = Modifier.weight(1f))
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (I18n.language() == "tr") "Sadece gerektiğinde (PRN)" else "As needed only (PRN)")
                    Switch(checked = prnOnly, onCheckedChange = { prnOnly = it; if (it) times = emptyList() })
                }
                if (prnOnly) {
                    OutlinedTextField(minInterval, { minInterval = it.filter(Char::isDigit) }, label = { Text(if (I18n.language() == "tr") "Minimum aralık, dk (opsiyonel)" else "Minimum interval, min (optional)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(maxPerDay, { maxPerDay = it.filter(Char::isDigit) }, label = { Text(if (I18n.language() == "tr") "Günlük maksimum (opsiyonel)" else "Daily maximum (optional)") }, modifier = Modifier.fillMaxWidth())
                } else {
                    times.forEach { Text(it) }
                    OutlinedButton(onClick = { val now = LocalTime.now(); TimePickerDialog(c, { _, h, m -> times = (times + String.format("%02d:%02d", h, m)).distinct().sorted() }, now.hour, now.minute, true).show() }) { Text(I18n.t("add_time")) }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (I18n.language() == "tr") "Kısa süreli kullanım" else "Short-term course")
                    Switch(checked = shortCourse, onCheckedChange = { shortCourse = it })
                }
                if (shortCourse) {
                    OutlinedTextField(durationDays, { durationDays = it.filter(Char::isDigit) }, label = { Text(if (I18n.language() == "tr") "Kaç gün kullanılacak?" else "How many days?") }, modifier = Modifier.fillMaxWidth())
                    duration?.let { val start = LocalDate.now(); val end = start.plusDays((it - 1).toLong()); Text(if (I18n.language() == "tr") "$start – $end arasında aktif" else "Active from $start to $end") }
                }
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = {
                val q = quantity.replace(',', '.').toDoubleOrNull()
                val doseLabel = MedicationMeta("", form, q, site.trim()).doseLabel()
                val finalDose = listOf(doseLabel, doseNote.trim()).filter { it.isNotBlank() }.joinToString(" • ")
                val med = Medication(UUID.randomUUID().toString(), name.trim(), finalDose, if (prnOnly) emptyList() else times)
                val meta = MedicationMeta(med.id, form, q, site.trim(), packCount.toIntOrNull(), packUnit.trim(), source)
                MedicationMetaStore.save(c, meta)
                add(med)
                meta.packageCount?.takeIf { it > 0 }?.let { StockEngine.configure(c, med, it) }
                if (prnOnly) PrnEngine.create(c, med, minInterval.toIntOrNull(), maxPerDay.toIntOrNull())
                if (shortCourse && duration != null) {
                    val start = LocalDate.now()
                    ProgramRuleStore.save(c, ProgramRule(medicationId = med.id, startDate = start.toString(), endDate = start.plusDays((duration - 1).toLong()).toString(), anchorDate = start.toString()))
                }
            }) { Text(I18n.t("save")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(I18n.t("cancel")) } }
    )
}

private fun formLabel(form: MedicationForm): String = when (form) {
    MedicationForm.TABLET -> if (I18n.language() == "tr") "Tablet / kapsül" else "Tablet / capsule"
    MedicationForm.INSULIN -> if (I18n.language() == "tr") "İnsülin" else "Insulin"
    MedicationForm.INJECTION -> if (I18n.language() == "tr") "Enjeksiyon" else "Injection"
    MedicationForm.NEBULE -> if (I18n.language() == "tr") "Nebül" else "Nebule"
    MedicationForm.INHALER -> if (I18n.language() == "tr") "İnhaler / sprey" else "Inhaler / spray"
    MedicationForm.DROP -> if (I18n.language() == "tr") "Damla" else "Drops"
    MedicationForm.LIQUID -> if (I18n.language() == "tr") "Şurup / solüsyon" else "Liquid"
    MedicationForm.CREAM -> if (I18n.language() == "tr") "Krem / merhem / jel" else "Cream / ointment / gel"
    MedicationForm.PATCH -> if (I18n.language() == "tr") "Yama" else "Patch"
    MedicationForm.OTHER -> if (I18n.language() == "tr") "Diğer" else "Other"
}
