package com.ozkanmut.ilactakip

import java.time.LocalTime

data class ImportDraftMedication(
    val name: String,
    val dose: String = "",
    val times: List<String> = emptyList(),
    val routineLabel: String = "",
    val confidence: Float = 0f,
    val missing: Set<String> = emptySet()
)

data class SmartImportDraft(
    val medications: List<ImportDraftMedication>,
    val questions: List<String>,
    val warnings: List<String>
)

/**
 * Local deterministic first pass for pasted text / OCR text.
 * It only prepares a draft. It never activates a medication or schedule silently.
 */
object SmartImport {
    private val timeRegex = Regex("(?<!\\d)([01]?\\d|2[0-3])[:.]([0-5]\\d)(?!\\d)")
    private val amountRegex = Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(mg|mcg|µg|g|ml|damla|drop|tablet|tab|kapsül|capsule|puf|puff)\\b")

    fun parse(text: String): SmartImportDraft {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val warnings = mutableListOf<String>()
        val drafts = lines.mapNotNull { parseLine(it) }.distinctBy { d -> d.name.lowercase() + d.times.joinToString() }

        if (drafts.isEmpty() && text.isNotBlank()) warnings += tr(
            "Metinden güvenilir bir ilaç taslağı çıkaramadım.",
            "I couldn't extract a reliable medication draft from the text."
        )

        val questions = buildList {
            drafts.forEach { d ->
                if ("time" in d.missing) add(tr("${d.name} hangi saatte alınacak?", "What time should ${d.name} be taken?"))
                if ("name" in d.missing) add(tr("İlacın adı nedir?", "What is the medication name?"))
            }
        }.distinct()

        return SmartImportDraft(drafts, questions, warnings)
    }

    private fun parseLine(line: String): ImportDraftMedication? {
        val times = timeRegex.findAll(line).mapNotNull { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val min = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            runCatching { LocalTime.of(h, min).toString() }.getOrNull()
        }.distinct().toList()

        val dose = amountRegex.find(line)?.value?.replace(',', '.') ?: ""
        val stripped = line
            .replace(timeRegex, " ")
            .replace(amountRegex, " ")
            .replace(Regex("(?i)\\b(sabah|öğle|ogle|akşam|aksam|gece|morning|noon|evening|night|daily|günlük|gunluk|her gün|hergun)\\b"), " ")
            .replace(Regex("[-–—•,;()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val name = stripped.takeIf { it.length >= 2 } ?: return null
        val routine = when {
            Regex("(?i)\\bsabah|morning\\b").containsMatchIn(line) -> tr("Sabah", "Morning")
            Regex("(?i)\\bakşam|aksam|evening\\b").containsMatchIn(line) -> tr("Akşam", "Evening")
            Regex("(?i)\\bgece|night\\b").containsMatchIn(line) -> tr("Gece", "Night")
            else -> ""
        }
        val missing = buildSet { if (times.isEmpty()) add("time") }
        val confidence = when {
            times.isNotEmpty() && dose.isNotBlank() -> 0.9f
            times.isNotEmpty() -> 0.75f
            else -> 0.5f
        }
        return ImportDraftMedication(name, dose, times, routine, confidence, missing)
    }

    fun commitConfirmed(draft: SmartImportDraft): List<Medication> = draft.medications
        .filter { it.name.isNotBlank() && it.times.isNotEmpty() }
        .map { d -> Medication(java.util.UUID.randomUUID().toString(), d.name, d.dose, d.times) }

    private fun tr(tr: String, en: String) = if (I18n.language() == "tr") tr else en
}
