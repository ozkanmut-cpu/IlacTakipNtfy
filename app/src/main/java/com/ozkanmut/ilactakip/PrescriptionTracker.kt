package com.ozkanmut.ilactakip

import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

data class PrescriptionRecord(
    val id: String,
    val medicationId: String = "",
    val medicationName: String,
    val prescriptionNo: String = "",
    val prescriptionDate: String = "",
    val fillDate: String = "",
    val doseEndDate: String,
    val boxCount: Int? = null,
    val dosePattern: String = "",
    val period: String = "",
    val diagnosis: String = "",
    val continuous: Boolean = false,
    val source: String = "sgk_pdf"
) {
    fun endDate(): LocalDate? = parseDate(doseEndDate)
    fun eligibleDate(): LocalDate? = if (continuous) endDate()?.minusDays(15) else null
}

object PrescriptionRecordStore {
    private const val PREFS = "dosefolk_prescription_tracker"
    private const val KEY = "records"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<PrescriptionRecord> = load(c)

    @Synchronized
    fun upsertAll(c: Context, records: List<PrescriptionRecord>) {
        if (records.isEmpty()) return
        val old = load(c).toMutableList()
        records.forEach { incoming ->
            val keyIndex = old.indexOfFirst {
                MedicationIdentity.same(it.medicationName, incoming.medicationName) &&
                    it.doseEndDate == incoming.doseEndDate &&
                    it.prescriptionNo == incoming.prescriptionNo
            }
            if (keyIndex >= 0) old[keyIndex] = incoming.copy(id = old[keyIndex].id) else old += incoming
        }
        save(c, old.sortedByDescending { parseDate(it.doseEndDate) })
    }

    @Synchronized
    fun update(c: Context, record: PrescriptionRecord) {
        save(c, listOf(record) + load(c).filterNot { it.id == record.id })
    }

    fun due(c: Context, today: LocalDate = LocalDate.now()): List<PrescriptionRecord> =
        PrescriptionLifecycle.due(c, today)

    fun upcoming(c: Context, today: LocalDate = LocalDate.now(), days: Long = 45): List<PrescriptionRecord> =
        PrescriptionLifecycle.upcoming(c, today, days)

    private fun load(c: Context): List<PrescriptionRecord> = runCatching {
        val a = JSONArray(prefs(c).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("medicationName")
            val end = o.optString("doseEndDate")
            if (name.isBlank() || end.isBlank()) return@mapNotNull null
            PrescriptionRecord(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                medicationId = o.optString("medicationId"),
                medicationName = name,
                prescriptionNo = o.optString("prescriptionNo"),
                prescriptionDate = o.optString("prescriptionDate"),
                fillDate = o.optString("fillDate"),
                doseEndDate = end,
                boxCount = if (o.isNull("boxCount")) null else o.optInt("boxCount"),
                dosePattern = o.optString("dosePattern"),
                period = o.optString("period"),
                diagnosis = o.optString("diagnosis"),
                continuous = o.optBoolean("continuous", false),
                source = o.optString("source", "sgk_pdf")
            )
        }
    }.getOrDefault(emptyList())

    private fun save(c: Context, records: List<PrescriptionRecord>) {
        val a = JSONArray()
        records.forEach { r ->
            a.put(JSONObject()
                .put("id", r.id)
                .put("medicationId", r.medicationId)
                .put("medicationName", r.medicationName)
                .put("prescriptionNo", r.prescriptionNo)
                .put("prescriptionDate", r.prescriptionDate)
                .put("fillDate", r.fillDate)
                .put("doseEndDate", r.doseEndDate)
                .put("boxCount", r.boxCount ?: JSONObject.NULL)
                .put("dosePattern", r.dosePattern)
                .put("period", r.period)
                .put("diagnosis", r.diagnosis)
                .put("continuous", r.continuous)
                .put("source", r.source))
        }
        prefs(c).edit().putString(KEY, a.toString()).commit()
    }
}

data class SgkImportRow(
    val prescriptionNo: String,
    val prescriptionDate: String,
    val medicationName: String,
    val boxCount: Int?,
    val dosePattern: String,
    val period: String,
    val fillDate: String,
    val doseEndDate: String,
    val diagnosis: String,
    val likelyContinuous: Boolean,
    val raw: String,
    val times: List<String> = emptyList(),
    val form: MedicationForm = MedicationForm.OTHER,
    val administrationQuantity: Double? = null,
    val administrationUnit: String = ""
)

object SgkMedicationParser {
    private val dateRegex = Regex("(?<!\\d)(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})\\s*[./-]\\s*(\\d{4})(?!\\d)")
    private val doseRegex = Regex("(?i)(?<!\\d)\\d+\\s*[x×]\\s*\\d+(?:[.,]\\d+)?(?!\\d)")
    private val periodRegex = Regex("(?i)\\b\\d+\\s*(?:Günde|Gunde|Haftada|Ayda|Yılda|Yilda|day|week|month|year)\\b")
    private val boxRegex = Regex("(?i)\\b(\\d{1,3})\\s*Adet\\b")
    private val strengthRegex = Regex("(?i)(\\d+(?:[.,/]\\d+)*)\\s*(MG|MCG|UG|U/ML|IU/ML|ML|GR|G|%)")
    private val formRegex = Regex("(?i)tablet|tb\\.?|kapsül|kapsul|capsule|neb|nebul|flakon|flk|damla|solusyon|solüsyon|enjeks|kalem|inhal|krem|jel|pomad|şurup|surup")
    private val headerRegex = Regex("(?i)reçete|recete|ilaç adı|ilac adi|doz bitiş|doz bitis|eczane|doktor|sağlık tesisi|saglik tesisi")

    fun parse(text: String): List<SgkImportRow> {
        val lines = normalizeOcr(text)
        val chunks = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null
        lines.forEach { line ->
            val code = extractPrescriptionCode(line)
            val startsRow = code != null && !headerRegex.containsMatchIn(line)
            if (startsRow) {
                current = mutableListOf(line)
                chunks += current!!
            } else current?.add(line)
        }
        return chunks.mapNotNull(::parseChunk)
            .distinctBy { "${it.prescriptionNo}|${MedicationIdentity.canonical(it.medicationName)}|${it.doseEndDate}" }
    }

    private fun normalizeOcr(text: String): List<String> = text
        .replace('\u000c', '\n')
        .replace('×', 'x')
        .lines()
        .map { line ->
            line.replace(Regex("[‐‑‒–—]"), "-")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        .filter { it.isNotBlank() }

    private fun extractPrescriptionCode(line: String): String? {
        if (headerRegex.containsMatchIn(line)) return null
        val beforeDate = dateRegex.find(line)?.range?.first?.let { line.substring(0, it) } ?: line
        val tokenArea = beforeDate.trim().take(24)
        val compact = tokenArea.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
        if (compact.length !in 6..9) return null
        if (!compact.any(Char::isLetter) || !compact.any(Char::isDigit)) return null
        val residue = tokenArea.replace(Regex("[A-Za-z0-9 ._-]"), "")
        if (residue.isNotBlank()) return null
        return compact
    }

    private fun normalizeDate(m: MatchResult): String {
        val d = m.groupValues[1].toIntOrNull() ?: return m.value
        val month = m.groupValues[2].toIntOrNull() ?: return m.value
        val year = m.groupValues[3].toIntOrNull() ?: return m.value
        return String.format("%02d.%02d.%04d", d, month, year)
    }

    private fun parseChunk(lines: List<String>): SgkImportRow? {
        val text = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        val dates = dateRegex.findAll(text).map(::normalizeDate).toList()
        if (dates.size < 2) return null
        val code = extractPrescriptionCode(lines.first()).orEmpty()
        if (code.isBlank()) return null
        val rxDate = dates.first()
        val fillDate = if (dates.size >= 3) dates[dates.size - 2] else dates.first()
        val endDate = dates.last()
        val dose = doseRegex.find(text)?.value?.replace(" ", "")?.replace('×', 'x') ?: ""
        val period = periodRegex.find(text)?.value ?: ""
        val doseIndex = doseRegex.find(text)?.range?.first ?: text.length
        val box = boxRegex.findAll(text.substring(0, doseIndex)).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
        val name = extractMedicationName(lines, text).ifBlank { return null }
        val endMatch = dateRegex.findAll(text).lastOrNull()
        val diagnosis = if (endMatch != null) text.substring(endMatch.range.last + 1).trim().take(220) else ""
        val start = parseDate(fillDate)
        val end = parseDate(endDate)
        val duration = if (start != null && end != null) ChronoUnit.DAYS.between(start, end) + 1 else 0
        val likelyContinuous = diagnosis.isNotBlank() && duration >= 28
        val suggestion = MedicationSuggestionEngine.suggest(name, text)
        val quantity = dose.substringAfter('x', "").replace(',', '.').toDoubleOrNull()
        val unit = sgkAdministrationUnit(suggestion.form, text)
        return SgkImportRow(code, rxDate, name, box, dose, period, fillDate, endDate, diagnosis, likelyContinuous, text, emptyList(), suggestion.form, quantity, unit)
    }

    private fun extractMedicationName(lines: List<String>, flat: String): String {
        val strengthLine = lines.indexOfFirst { strengthRegex.containsMatchIn(it) }
        val formLine = lines.indexOfFirst { formRegex.containsMatchIn(it) }
        val anchor = when {
            strengthLine >= 0 -> strengthLine
            formLine >= 0 -> formLine
            else -> -1
        }
        if (anchor >= 0) {
            val from = (anchor - 2).coerceAtLeast(0)
            val picked = lines.subList(from, (anchor + 1).coerceAtMost(lines.size))
                .filterNot { dateRegex.containsMatchIn(it) || extractPrescriptionCode(it) != null || it.equals("Adet", true) || headerRegex.containsMatchIn(it) }
                .joinToString(" ")
                .replace(Regex("(?i)^(NAR|BENGİ|BENGI)\\s+"), "")
                .trim()
            if (picked.length >= 3) return picked.take(100)
        }
        val m = strengthRegex.find(flat) ?: return ""
        val prefix = flat.substring(0, m.range.first).trim().split(' ').takeLast(3).joinToString(" ")
        return (prefix + " " + m.value).trim().take(100)
    }

    private fun sgkAdministrationUnit(form: MedicationForm, text: String): String = when (form) {
        MedicationForm.INSULIN -> "U"
        MedicationForm.TABLET -> if (Regex("(?i)kapsül|kapsul|capsule").containsMatchIn(text)) pt("kapsül", "capsule") else "tablet"
        MedicationForm.NEBULE -> when {
            Regex("(?i)flakon|flk|vial").containsMatchIn(text) -> pt("flakon", "vial")
            Regex("(?i)ampul|ampoule").containsMatchIn(text) -> pt("ampul", "ampoule")
            else -> pt("nebül", "nebule")
        }
        MedicationForm.INHALER -> if (Regex("(?i)kapsül|kapsul|capsule").containsMatchIn(text)) pt("kapsül", "capsule") else pt("puf", "puff")
        MedicationForm.DROP -> pt("damla", "drop")
        MedicationForm.LIQUID -> "mL"
        MedicationForm.CREAM -> pt("uygulama", "application")
        MedicationForm.PATCH -> pt("yama", "patch")
        MedicationForm.INJECTION -> pt("doz", "dose")
        MedicationForm.OTHER -> pt("doz", "dose")
    }
}

@Composable
fun PrescriptionTrackerScreen(c: Context, meds: List<Medication>, onMedsChanged: (List<Medication>) -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    var importRows by remember { mutableStateOf<List<SgkImportRow>>(emptyList()) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val due = remember(refresh) { PrescriptionRecordStore.due(c, today) }
    val upcoming = remember(refresh) { PrescriptionRecordStore.upcoming(c, today) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        error = null
        ImportSourceReader.read(c, uri) { result ->
            reading = false
            error = result.error
            importRows = if (result.text.isBlank()) emptyList() else SgkMedicationParser.parse(result.text)
            if (result.text.isNotBlank() && importRows.isEmpty()) {
                error = pt("SGK tablosu okunamadı. PDF'nin e-Devlet 'İlaç Kullanım Süresi' çıktısı olduğundan emin ol.", "The SGK table could not be parsed. Make sure this is the e-Government medication-duration PDF.")
            }
        }
    }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(pt("Reçete / Yeniden Temin", "Prescription / Refill"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(pt("Sürekli ilaçlarda yeniden temin tarihi, SGK doz bitiş tarihinden 15 gün önce hesaplanır. Stoktan bağımsızdır.", "For continuous medication, refill eligibility is calculated as 15 days before the SGK dose-end date. It is independent of stock."))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }, enabled = !reading, modifier = Modifier.fillMaxWidth()) {
                Text(if (reading) pt("e-Devlet listesi okunuyor…", "Reading e-Government list…") else pt("e-Devlet ilaç listesinden içe aktar", "Import from e-Government medication list"))
            }
            error?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
        }

        if (due.isNotEmpty()) {
            item { Text(pt("Zamanı gelenler", "Ready now"), fontWeight = FontWeight.Bold) }
            items(due, key = { it.id }) { r -> PrescriptionRecordCard(c, r) { refresh++ } }
        }
        if (upcoming.isNotEmpty()) {
            item { Text(pt("Yaklaşanlar", "Upcoming"), fontWeight = FontWeight.Bold) }
            items(upcoming, key = { it.id }) { r -> PrescriptionRecordCard(c, r) { refresh++ } }
        }
        if (due.isEmpty() && upcoming.isEmpty() && importRows.isEmpty()) item {
            Text(pt("Takip edilen sürekli ilaç yok.", "No continuous medication is being tracked yet."))
        }

        if (importRows.isNotEmpty()) {
            item { HorizontalDivider(); Text(pt("İçe aktarma önizlemesi", "Import preview"), fontWeight = FontWeight.Bold) }
            items(importRows.indices.toList(), key = { it }) { index ->
                val row = importRows[index]
                var name by remember(row.raw) { mutableStateOf(row.medicationName) }
                var continuous by remember(row.raw) { mutableStateOf(row.likelyContinuous) }
                var times by remember(row.raw) { mutableStateOf(row.times) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedTextField(name, { name = it; importRows = importRows.toMutableList().also { list -> list[index] = row.copy(medicationName = it) } }, label = { Text(pt("İlaç adı", "Medication name")) }, modifier = Modifier.fillMaxWidth())
                        Text("${row.dosePattern}${if (row.period.isBlank()) "" else " • ${row.period}"}${row.boxCount?.let { " • $it kutu" } ?: ""}")
                        if (row.form != MedicationForm.OTHER || row.administrationQuantity != null) {
                            val q = row.administrationQuantity?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
                            Text(listOf(formLabelForSgk(row.form), q?.let { "$it ${row.administrationUnit}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Text(pt("Alım: ${row.fillDate} • Doz bitiş: ${row.doseEndDate}", "Fill: ${row.fillDate} • Dose end: ${row.doseEndDate}"))
                        if (row.diagnosis.isNotBlank()) Text(row.diagnosis, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(pt("Sürekli kullanım", "Continuous use"))
                            Switch(checked = continuous, onCheckedChange = { v -> continuous = v; importRows = importRows.toMutableList().also { list -> list[index] = row.copy(medicationName = name, likelyContinuous = v, times = times) } })
                        }
                        if (continuous) parseDate(row.doseEndDate)?.minusDays(15)?.let { d -> Text(pt("Yeniden temin: ${formatDate(d)}", "Refill eligible: ${formatDate(d)}"), fontWeight = FontWeight.Bold) }
                        if (times.isNotEmpty()) Text(times.joinToString(" • "))
                        OutlinedButton(onClick = {
                            val now = LocalTime.now()
                            TimePickerDialog(c, { _, h, m ->
                                times = (times + String.format("%02d:%02d", h, m)).distinct().sorted()
                                importRows = importRows.toMutableList().also { list -> list[index] = row.copy(medicationName = name, likelyContinuous = continuous, times = times) }
                            }, now.hour, now.minute, true).show()
                        }) { Text(pt("İlaç programına saat ekle", "Add schedule time")) }
                        if (times.isEmpty()) Text(pt("Saat girmezsen ilaç yalnızca reçete takibine alınır; alarm programına eklenmez.", "Without a time, the medication is imported only for refill tracking and is not added to the alarm schedule."), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Button(onClick = {
                    val existing = Store.load(c).toMutableList()
                    val newMeds = mutableListOf<Medication>()
                    val records = importRows.map { row ->
                        val matched = (existing + newMeds).firstOrNull { MedicationIdentity.same(it.name, row.medicationName) }
                        val suggestion = MedicationSuggestionEngine.suggest(row.medicationName, row.raw)
                        val metaTemplate = MedicationMeta(
                            medicationId = "",
                            form = row.form,
                            quantity = row.administrationQuantity,
                            administrationSite = suggestion.administrationSite,
                            packageCount = suggestion.packageCount,
                            packageUnit = suggestion.packageUnit,
                            source = "sgk_pdf",
                            doseUnitOverride = row.administrationUnit
                        )
                        val med = when {
                            matched != null -> {
                                val currentMeta = MedicationMetaStore.get(c, matched.id)
                                if (currentMeta == null || currentMeta.form == MedicationForm.OTHER || currentMeta.quantity == null) {
                                    MedicationMetaStore.save(c, metaTemplate.copy(
                                        medicationId = matched.id,
                                        form = if (currentMeta?.form != null && currentMeta.form != MedicationForm.OTHER) currentMeta.form else metaTemplate.form,
                                        quantity = currentMeta?.quantity ?: metaTemplate.quantity,
                                        administrationSite = currentMeta?.administrationSite?.takeIf { it.isNotBlank() } ?: metaTemplate.administrationSite,
                                        packageCount = currentMeta?.packageCount ?: metaTemplate.packageCount,
                                        packageUnit = currentMeta?.packageUnit?.takeIf { it.isNotBlank() } ?: metaTemplate.packageUnit,
                                        source = currentMeta?.source ?: "sgk_pdf",
                                        doseUnitOverride = currentMeta?.doseUnitOverride?.takeIf { it.isNotBlank() } ?: metaTemplate.doseUnitOverride
                                    ))
                                }
                                matched
                            }
                            row.times.isNotEmpty() -> {
                                val doseLabel = metaTemplate.doseLabel().ifBlank { row.dosePattern }
                                Medication(UUID.randomUUID().toString(), row.medicationName.trim(), doseLabel, row.times.distinct().sorted()).also { created ->
                                    newMeds += created
                                    MedicationMetaStore.save(c, metaTemplate.copy(medicationId = created.id))
                                }
                            }
                            else -> null
                        }
                        PrescriptionRecord(
                            id = UUID.randomUUID().toString(),
                            medicationId = med?.id.orEmpty(),
                            medicationName = row.medicationName.trim(),
                            prescriptionNo = row.prescriptionNo,
                            prescriptionDate = row.prescriptionDate,
                            fillDate = row.fillDate,
                            doseEndDate = row.doseEndDate,
                            boxCount = row.boxCount,
                            dosePattern = row.dosePattern,
                            period = row.period,
                            diagnosis = row.diagnosis,
                            continuous = row.likelyContinuous,
                            source = "sgk_pdf"
                        )
                    }
                    if (newMeds.isNotEmpty()) {
                        Store.save(c, existing + newMeds)
                        onMedsChanged(existing + newMeds)
                    }
                    PrescriptionRecordStore.upsertAll(c, records)
                    importRows = emptyList()
                    refresh++
                }, modifier = Modifier.fillMaxWidth()) { Text(pt("Onayla ve içe aktar", "Confirm and import")) }
            }
        }
    }
}

@Composable
private fun PrescriptionRecordCard(c: Context, r: PrescriptionRecord, changed: () -> Unit) {
    val today = LocalDate.now()
    val eligible = r.eligibleDate()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(r.medicationName, fontWeight = FontWeight.Bold)
            eligible?.let {
                val days = ChronoUnit.DAYS.between(today, it)
                val status = when {
                    days < 0 -> pt("${-days} gündür yeniden temin edilebilir", "Refill eligible for ${-days} day(s)")
                    days == 0L -> pt("Bugün yeniden temin edilebilir", "Refill eligible today")
                    else -> pt("$days gün sonra yeniden temin edilebilir", "Refill eligible in $days day(s)")
                }
                Text(status)
            }
            Text(pt("Doz bitiş: ${r.doseEndDate}", "Dose end: ${r.doseEndDate}"), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(pt("Sürekli kullanım", "Continuous use"))
                Switch(checked = r.continuous, onCheckedChange = { PrescriptionRecordStore.update(c, r.copy(continuous = it)); changed() })
            }
        }
    }
}

private fun formLabelForSgk(form: MedicationForm): String = when (form) {
    MedicationForm.TABLET -> pt("Tablet / kapsül", "Tablet / capsule")
    MedicationForm.INSULIN -> pt("İnsülin", "Insulin")
    MedicationForm.INJECTION -> pt("Enjeksiyon", "Injection")
    MedicationForm.NEBULE -> pt("Nebül", "Nebule")
    MedicationForm.INHALER -> pt("İnhaler / inhalasyon", "Inhaler / inhalation")
    MedicationForm.DROP -> pt("Damla", "Drops")
    MedicationForm.LIQUID -> pt("Sıvı", "Liquid")
    MedicationForm.CREAM -> pt("Krem / merhem / jel", "Cream / ointment / gel")
    MedicationForm.PATCH -> pt("Yama", "Patch")
    MedicationForm.OTHER -> ""
}

private val sgkDateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value, sgkDateFormatter) }.getOrNull()
private fun formatDate(value: LocalDate): String = value.format(sgkDateFormatter)
private fun pt(tr: String, en: String) = if (I18n.language() == "tr") tr else en
