package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class MedicationForm(val unitTr: String, val unitEn: String) {
    TABLET("adet", "count"),
    INSULIN("U", "U"),
    INJECTION("doz", "dose"),
    NEBULE("nebül", "nebule"),
    INHALER("puf", "puff"),
    DROP("damla", "drop"),
    LIQUID("mL", "mL"),
    CREAM("uygulama", "application"),
    PATCH("adet", "patch"),
    OTHER("doz", "dose")
}

data class MedicationMeta(
    val medicationId: String,
    val form: MedicationForm = MedicationForm.OTHER,
    val quantity: Double? = null,
    val administrationSite: String = "",
    val packageCount: Int? = null,
    val packageUnit: String = "",
    val source: String = "manual"
) {
    fun unit(): String = if (I18n.language() == "tr") form.unitTr else form.unitEn
    fun doseLabel(): String = quantity?.let {
        val n = if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
        "$n ${unit()}${if (administrationSite.isBlank()) "" else " • $administrationSite"}"
    }.orEmpty()
}

data class MedicationSuggestion(
    val form: MedicationForm,
    val quantity: Double? = null,
    val administrationSite: String = "",
    val packageCount: Int? = null,
    val packageUnit: String = "",
    val suggestedName: String = "",
    val confidence: Float = 0f
)

object MedicationMetaStore {
    private const val PREFS = "dosefolk_medication_meta"
    private const val KEY = "items"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(c: Context, medicationId: String): MedicationMeta? = load(c).firstOrNull { it.medicationId == medicationId }

    @Synchronized
    fun save(c: Context, meta: MedicationMeta) {
        val all = listOf(meta) + load(c).filterNot { it.medicationId == meta.medicationId }
        val a = JSONArray()
        all.forEach { m ->
            a.put(JSONObject()
                .put("medicationId", m.medicationId)
                .put("form", m.form.name)
                .put("quantity", m.quantity ?: JSONObject.NULL)
                .put("administrationSite", m.administrationSite)
                .put("packageCount", m.packageCount ?: JSONObject.NULL)
                .put("packageUnit", m.packageUnit)
                .put("source", m.source))
        }
        prefs(c).edit().putString(KEY, a.toString()).commit()
    }

    private fun load(c: Context): List<MedicationMeta> = runCatching {
        val a = JSONArray(prefs(c).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            val id = o.optString("medicationId")
            if (id.isBlank()) return@let null
            MedicationMeta(
                medicationId = id,
                form = runCatching { MedicationForm.valueOf(o.optString("form")) }.getOrDefault(MedicationForm.OTHER),
                quantity = if (o.isNull("quantity")) null else o.optDouble("quantity"),
                administrationSite = o.optString("administrationSite"),
                packageCount = if (o.isNull("packageCount")) null else o.optInt("packageCount"),
                packageUnit = o.optString("packageUnit"),
                source = o.optString("source", "manual")
            )
        } }
    }.getOrDefault(emptyList())
}

object MedicationSuggestionEngine {
    private val quantityRegexes = listOf(
        MedicationForm.INSULIN to Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(?:U|IU|ünite|unite|unit)\\b"),
        MedicationForm.INHALER to Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(?:puf|puff|inhalasyon|inhalation)\\b"),
        MedicationForm.DROP to Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(?:damla|drop)\\b"),
        MedicationForm.NEBULE to Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(?:nebül|nebul|nebule|ampul|ampoule)\\b"),
        MedicationForm.LIQUID to Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*m[lL]\\b"),
        MedicationForm.TABLET to Regex("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(?:tablet|tab|kapsül|kapsul|capsule)\\b")
    )
    private val packRegex = Regex("(?i)\\b(\\d{1,4})\\s*(tablet|film tablet|kapsül|kapsul|capsule|ampul|ampoule|nebül|nebul|nebule|flakon|vial|doz|dose|puf|puff|adet|piece|patch|yama)\\b")

    fun suggest(name: String, ocrText: String = ""): MedicationSuggestion {
        val text = listOf(name, ocrText).filter { it.isNotBlank() }.joinToString("\n")
        val lower = text.lowercase()
        val form = when {
            Regex("(?i)insulin|insülin|insülinum|humalog|novorapid|fiasp|lantus|levemir|toujeo|tresiba").containsMatchIn(text) -> MedicationForm.INSULIN
            Regex("(?i)nebül|nebul|nebule|nebülizat|nebuliz|respule").containsMatchIn(text) -> MedicationForm.NEBULE
            Regex("(?i)inhaler|inhalasyon|puf|puff|turbuhaler|diskus|ellipta|aerosol").containsMatchIn(text) -> MedicationForm.INHALER
            Regex("(?i)göz damlası|goz damlasi|eye drops|kulak damlası|burun damlası|damla|drops?").containsMatchIn(text) -> MedicationForm.DROP
            Regex("(?i)şurup|surup|syrup|oral solüsyon|oral solusyon|solution|süspansiyon|suspension").containsMatchIn(text) -> MedicationForm.LIQUID
            Regex("(?i)krem|cream|merhem|ointment|jel|gel").containsMatchIn(text) -> MedicationForm.CREAM
            Regex("(?i)yama|patch|transdermal").containsMatchIn(text) -> MedicationForm.PATCH
            Regex("(?i)enjeksiyon|injection|enjektabl|ampul|ampoule|flakon|vial|prefilled|kalem|pen").containsMatchIn(text) -> MedicationForm.INJECTION
            Regex("(?i)tablet|kapsül|kapsul|capsule|draje|film tablet").containsMatchIn(text) -> MedicationForm.TABLET
            else -> MedicationForm.OTHER
        }
        val quantity = quantityRegexes.firstNotNullOfOrNull { (f, r) ->
            if (f != form && form != MedicationForm.OTHER) null else r.find(text)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        }
        val pack = packRegex.findAll(text).mapNotNull { m ->
            val count = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val unit = m.groupValues[2]
            count to unit
        }.filter { (count, _) -> count in 2..1000 }.maxByOrNull { it.first }
        val site = when {
            "sol göz" in lower || "left eye" in lower -> if (I18n.language() == "tr") "sol göz" else "left eye"
            "sağ göz" in lower || "sag goz" in lower || "right eye" in lower -> if (I18n.language() == "tr") "sağ göz" else "right eye"
            "iki göz" in lower || "both eyes" in lower -> if (I18n.language() == "tr") "iki göz" else "both eyes"
            else -> ""
        }
        val suggestedName = ocrText.lines().map { it.trim() }
            .firstOrNull { it.length in 3..40 && it.any(Char::isLetter) && !it.matches(Regex(".*\\d{3,}.*")) }
            .orEmpty()
        return MedicationSuggestion(form, quantity, site, pack?.first, pack?.second.orEmpty(), suggestedName, if (ocrText.isNotBlank()) 0.82f else 0.65f)
    }
}
