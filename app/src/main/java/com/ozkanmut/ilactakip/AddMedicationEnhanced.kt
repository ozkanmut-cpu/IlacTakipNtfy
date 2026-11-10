package com.ozkanmut.ilactakip

import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.input.KeyboardType
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddMedicationDialogEnhanced(c: Context, close: () -> Unit, existing: Medication? = null, mode: DailyViewMode = DailyViewMode.STANDARD, add: (Medication) -> Unit) {
    val original = remember(existing?.id) { existing?.let { MedicationMetaStore.get(c, it.id) } }
    val originalPrn = remember(existing?.id) { existing?.let { med -> PrnEngine.all(c).firstOrNull { it.medicationId == med.id } } }
    val tr = I18n.language() == "tr"
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var doseNote by remember { mutableStateOf(original?.noteFrom(existing?.dose.orEmpty()) ?: existing?.dose.orEmpty()) }
    var times by remember { mutableStateOf(existing?.times.orEmpty()) }
    var prnOnly by remember { mutableStateOf(existing?.times?.isEmpty() == true) }
    var shortCourse by remember { mutableStateOf(false) }
    var durationDays by remember { mutableStateOf("") }
    var minInterval by remember { mutableStateOf(originalPrn?.minimumIntervalMinutes?.toString().orEmpty()) }
    var maxPerDay by remember { mutableStateOf(originalPrn?.maximumPerDay?.toString().orEmpty()) }
    var form by remember { mutableStateOf(original?.form ?: MedicationForm.OTHER) }
    var amount by remember { mutableStateOf(MedicationAmount.existing(original?.quantity)) }
    var unitOverride by remember { mutableStateOf(original?.doseUnitOverride.orEmpty()) }
    var site by remember { mutableStateOf(original?.administrationSite.orEmpty()) }
    var packCount by remember { mutableStateOf(original?.packageCount?.toString().orEmpty()) }
    var packUnit by remember { mutableStateOf(original?.packageUnit.orEmpty()) }
    var formMenu by remember { mutableStateOf(false) }
    var suggestionText by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(original?.source ?: "manual") }
    var editedFormOrAmount by remember { mutableStateOf(existing != null) }
    var showDetails by remember { mutableStateOf(mode == DailyViewMode.ADVANCED) }

    fun chooseForm(next: MedicationForm, unit: String = "") {
        if (form == next && unitOverride == unit) return
        form = next
        unitOverride = unit
        site = ""
        amount = if (next == MedicationForm.TABLET && !unit.isCapsuleUnit()) MedicationAmount.newTablet()
                 else MedicationAmount.existing(null)
    }

    fun applySuggestion(ocr: String) {
        val s = MedicationSuggestionEngine.suggest(name, ocr)
        if (!editedFormOrAmount) {
            if (s.form != MedicationForm.OTHER) chooseForm(s.form, s.doseUnit)
            if (s.form != MedicationForm.OTHER) s.quantity?.let { amount = MedicationAmount.existing(it) }
        }
        if (site.isBlank()) site = s.administrationSite
        if (packCount.isBlank()) s.packageCount?.let { packCount = it.toString() }
        if (packUnit.isBlank()) packUnit = s.packageUnit
        if (name.isBlank()) name = s.suggestedName
        if (ocr.isNotBlank()) suggestionText = if (tr) "Önerileri kaydetmeden önce kontrol et." else "Review suggestions before saving."
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
    val tablet = form == MedicationForm.TABLET && !unitOverride.isCapsuleUnit()
    val amountRequired = tablet && (existing == null || original?.form != form || original.quantity != null)
    val validAmount = amount.quantity != null || (amount.text.isBlank() && !amountRequired)
    val canSave = validAmount && name.isNotBlank() && (prnOnly || times.isNotEmpty()) && (!shortCourse || duration != null)
    val unit = unitOverride.ifBlank { if (tr) form.unitTr else form.unitEn }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (existing == null) I18n.t("new_med") else if (tr) "İlacı düzenle" else "Edit medication") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, {
                    name = it
                    if (existing == null && it.length >= 3) applySuggestion("")
                }, label = { Text(I18n.t("med_name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = formMenu, onExpandedChange = { formMenu = it }) {
                    OutlinedTextField(
                        value = if (unitOverride.isCapsuleUnit()) (if (tr) "Kapsül" else "Capsule") else formLabel(form),
                        onValueChange = {}, readOnly = true,
                        label = { Text(if (tr) "İlaç biçimi" else "Medication form") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(formMenu) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = formMenu, onDismissRequest = { formMenu = false }) {
                        MedicationForm.entries.forEach { f ->
                            DropdownMenuItem(text = { Text(formLabel(f)) }, onClick = {
                                editedFormOrAmount = true
                                chooseForm(f, if (f == MedicationForm.TABLET) "tablet" else "")
                                formMenu = false
                            })
                            if (f == MedicationForm.TABLET) DropdownMenuItem(text = { Text(if (tr) "Kapsül" else "Capsule") }, onClick = {
                                editedFormOrAmount = true
                                chooseForm(f, if (tr) "kapsül" else "capsule")
                                formMenu = false
                            })
                        }
                    }
                }
                if (tablet) {
                    Text(if (tr) "Her kullanımda" else "Amount per dose", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TabletChoice.entries.forEach { choice ->
                            FilterChip(selected = amount.choice == choice, onClick = { editedFormOrAmount = true; amount = amount.select(choice) },
                                label = { Text(when (choice) {
                                    TabletChoice.ONE -> "1 tablet"
                                    TabletChoice.QUARTER -> "¼ tablet"
                                    TabletChoice.HALF -> "½ tablet"
                                    TabletChoice.CUSTOM -> if (tr) "Diğer miktar" else "Other amount"
                                }) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                if (!tablet || amount.choice == TabletChoice.CUSTOM) {
                    OutlinedTextField(amount.text, { editedFormOrAmount = true; amount = MedicationAmount(TabletChoice.CUSTOM, it) },
                        label = { Text(if (tr) "Kullanım miktarı ($unit)" else "Amount per dose ($unit)") },
                        isError = amount.text.isNotBlank() && amount.quantity == null,
                        supportingText = if (amount.text.isNotBlank() && amount.quantity == null) {
                            { Text(if (tr) "Sıfırdan büyük bir sayı gir." else "Enter a number greater than zero.") }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(doseNote, { doseNote = it }, label = { Text(if (tr) "Kullanım notu (isteğe bağlı)" else "Instructions (optional)") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (tr) if (showDetails) "Ayrıntıları gizle" else "İsteğe bağlı ayrıntılar" else if (showDetails) "Hide details" else "Optional details")
                }
                if (showDetails) {
                    OutlinedButton(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (tr) "Kutu fotoğrafından öner" else "Suggest from box photo")
                    }
                    suggestionText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (form == MedicationForm.DROP || form == MedicationForm.CREAM || form == MedicationForm.INJECTION) {
                        OutlinedTextField(site, { site = it }, label = { Text(if (I18n.language() == "tr") "Uygulama yeri (opsiyonel)" else "Administration site (optional)") }, modifier = Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(packCount, { packCount = it.filter(Char::isDigit) }, label = { Text(if (I18n.language() == "tr") "Kutudaki adet" else "Pack count") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(packUnit, { packUnit = it }, label = { Text(if (I18n.language() == "tr") "Birim" else "Unit") }, modifier = Modifier.weight(1f))
                    }
                }

                Text(if (tr) "Hatırlatma saatleri" else "Reminder times", style = MaterialTheme.typography.titleMedium)
                if (existing == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (I18n.language() == "tr") "Sadece gerektiğinde (PRN)" else "As needed only (PRN)")
                    Switch(checked = prnOnly, onCheckedChange = { prnOnly = it })
                }
                if (prnOnly) {
                    if (showDetails) {
                        OutlinedTextField(minInterval, { minInterval = it.filter(Char::isDigit) }, label = { Text(if (I18n.language() == "tr") "Minimum aralık, dk (opsiyonel)" else "Minimum interval, min (optional)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(maxPerDay, { maxPerDay = it.filter(Char::isDigit) }, label = { Text(if (I18n.language() == "tr") "Günlük maksimum (opsiyonel)" else "Daily maximum (optional)") }, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    times.forEach { time ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(time, style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { times = times - time }) { Text(if (tr) "$time kaldır" else "Remove $time") }
                        }
                    }
                    OutlinedButton(onClick = { val now = LocalTime.now(); TimePickerDialog(c, { _, h, m -> times = (times + String.format(java.util.Locale.ROOT, "%02d:%02d", h, m)).distinct().sorted() }, now.hour, now.minute, true).show() }) { Text(I18n.t("add_time")) }
                }

                if (showDetails && existing == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (I18n.language() == "tr") "Kısa süreli kullanım" else "Short-term course")
                        Switch(checked = shortCourse, onCheckedChange = { shortCourse = it })
                    }
                    if (shortCourse) {
                        OutlinedTextField(durationDays, { durationDays = it.filter(Char::isDigit) }, label = { Text(if (I18n.language() == "tr") "Kaç gün kullanılacak?" else "How many days?") }, modifier = Modifier.fillMaxWidth())
                        duration?.let { val start = LocalDate.now(); val end = start.plusDays((it - 1).toLong()); Text(if (I18n.language() == "tr") "$start – $end arasında aktif" else "Active from $start to $end") }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = {
                val id = existing?.id ?: UUID.randomUUID().toString()
                val meta = MedicationMeta(id, form, amount.quantity, site.trim(), packCount.toIntOrNull(), packUnit.trim(), source, unitOverride)
                val finalDose = if (existing != null && original?.quantity == meta.quantity && original?.form == meta.form &&
                    original.administrationSite == meta.administrationSite && original.doseUnitOverride == meta.doseUnitOverride &&
                    original.noteFrom(existing.dose) == doseNote) existing.dose
                else listOf(meta.doseLabel(), doseNote.trim()).filter { it.isNotBlank() }.joinToString(" • ")
                val med = Medication(id, name.trim(), finalDose, if (prnOnly) emptyList() else times)
                MedicationMetaStore.save(c, meta)
                add(med)
                if (existing == null) meta.packageCount?.takeIf { it > 0 }?.let { StockEngine.configure(c, med, it) }
                if (prnOnly && originalPrn != null) {
                    PrnEngine.upsert(c, originalPrn.copy(name = med.name, doseNote = med.dose,
                        minimumIntervalMinutes = minInterval.toIntOrNull()?.takeIf { it > 0 },
                        maximumPerDay = maxPerDay.toIntOrNull()?.takeIf { it > 0 }))
                } else if (prnOnly && existing == null) PrnEngine.create(c, med, minInterval.toIntOrNull(), maxPerDay.toIntOrNull())
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
    MedicationForm.TABLET -> "Tablet"
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

private fun String.isCapsuleUnit() = lowercase() in setOf("capsule", "kapsül", "kapsul")
