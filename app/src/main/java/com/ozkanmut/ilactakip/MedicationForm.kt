package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class MedicationForm(val unitTr: String, val unitEn: String) {
    TABLET("adet", "count"), INSULIN("U", "U"), INJECTION("doz", "dose"), NEBULE("nebül", "nebule"),
    INHALER("puf", "puff"), DROP("damla", "drop"), LIQUID("mL", "mL"), CREAM("uygulama", "application"),
    PATCH("adet", "patch"), OTHER("doz", "dose")
}

data class MedicationMeta(
    val medicationId: String, val form: MedicationForm = MedicationForm.OTHER, val quantity: Double? = null,
    val administrationSite: String = "", val packageCount: Int? = null, val packageUnit: String = "",
    val source: String = "manual", val doseUnitOverride: String = ""
) {
    fun unit(): String = doseUnitOverride.ifBlank { if (I18n.language() == "tr") form.unitTr else form.unitEn }
    fun doseLabel(): String = quantity?.let {
        val n = if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
        "$n ${unit()}${if (administrationSite.isBlank()) "" else " • $administrationSite"}"
    }.orEmpty()
}

data class MedicationSuggestion(
    val form: MedicationForm, val quantity: Double? = null, val administrationSite: String = "",
    val packageCount: Int? = null, val packageUnit: String = "", val suggestedName: String = "",
    val confidence: Float = 0f, val doseUnit: String = ""
)

object MedicationMetaStore {
    private const val PREFS = "dosefolk_medication_meta"
    private const val KEY = "items"
    private const val KEY_REMOTE = "remote_items"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun get(c: Context, medicationId: String): MedicationMeta? = loadLocal(c).firstOrNull { it.medicationId == medicationId }
    fun remote(c: Context, ownerId: String, medicationId: String): MedicationMeta? = loadRemote(c).firstOrNull { it.first == ownerId && it.second.medicationId == medicationId }?.second
    @Synchronized fun save(c: Context, meta: MedicationMeta) {
        val all = listOf(meta) + loadLocal(c).filterNot { it.medicationId == meta.medicationId }
        prefs(c).edit().putString(KEY, encodeList(all).toString()).commit()
    }
    @Synchronized fun saveRemote(c: Context, ownerId: String, meta: MedicationMeta) {
        if (ownerId.isBlank() || ownerId == OwnerScopeStore.localOwnerId(c) || meta.medicationId.isBlank()) return
        val all = listOf(ownerId to meta) + loadRemote(c).filterNot { it.first == ownerId && it.second.medicationId == meta.medicationId }
        val a = JSONArray(); all.forEach { (owner, item) -> a.put(toJson(item).put("ownerId", owner)) }
        prefs(c).edit().putString(KEY_REMOTE, a.toString()).commit()
    }
    @Synchronized fun clearRemoteOwner(c: Context, ownerId: String) {
        val all = loadRemote(c).filterNot { it.first == ownerId }; val a = JSONArray()
        all.forEach { (owner, item) -> a.put(toJson(item).put("ownerId", owner)) }
        prefs(c).edit().putString(KEY_REMOTE, a.toString()).commit()
    }
    fun toJson(m: MedicationMeta): JSONObject = JSONObject().put("medicationId", m.medicationId).put("form", m.form.name)
        .put("quantity", m.quantity ?: JSONObject.NULL).put("administrationSite", m.administrationSite)
        .put("packageCount", m.packageCount ?: JSONObject.NULL).put("packageUnit", m.packageUnit).put("source", m.source)
        .put("doseUnitOverride", m.doseUnitOverride)
    fun fromJson(o: JSONObject?): MedicationMeta? {
        if (o == null) return null; val id = o.optString("medicationId"); if (id.isBlank()) return null
        return MedicationMeta(id, runCatching { MedicationForm.valueOf(o.optString("form")) }.getOrDefault(MedicationForm.OTHER),
            if (o.isNull("quantity")) null else o.optDouble("quantity"), o.optString("administrationSite"),
            if (o.isNull("packageCount")) null else o.optInt("packageCount"), o.optString("packageUnit"),
            o.optString("source", "manual"), o.optString("doseUnitOverride"))
    }
    private fun encodeList(items: List<MedicationMeta>) = JSONArray().also { a -> items.forEach { a.put(toJson(it)) } }
    private fun loadLocal(c: Context): List<MedicationMeta> = runCatching {
        val a = JSONArray(prefs(c).getString(KEY, "[]") ?: "[]"); (0 until a.length()).mapNotNull { fromJson(a.optJSONObject(it)) }
    }.getOrDefault(emptyList())
    private fun loadRemote(c: Context): List<Pair<String, MedicationMeta>> = runCatching {
        val a = JSONArray(prefs(c).getString(KEY_REMOTE, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i -> val o=a.optJSONObject(i)?:return@mapNotNull null; val owner=o.optString("ownerId"); val meta=fromJson(o)?:return@mapNotNull null; if(owner.isBlank()) null else owner to meta }
    }.getOrDefault(emptyList())
}

object MedicationSuggestionEngine {
    private val packRegex = Regex("(?i)\\b(\\d{1,4})\\s*(tablet|film tablet|kapsül|kapsul|capsule|ampul|ampoule|nebül|nebul|nebule|flakon|vial|doz|dose|puf|puff|adet|piece|patch|yama)\\b")
    private val explicitUseRegexes = listOf(
        MedicationForm.INSULIN to Regex("(?i)(?:kullan(?:ım|im)|uygula(?:ma)?|doz|her seferinde|tek doz|inject|use)\\s*[:=-]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:U|IU|ünite|unite|unit)\\b(?!\\s*/\\s*m[lL])"),
        MedicationForm.INHALER to Regex("(?i)(?:kullan(?:ım|im)|uygula(?:ma)?|her seferinde|tek doz|use)\\s*[:=-]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:puf|puff)\\b"),
        MedicationForm.DROP to Regex("(?i)(?:kullan(?:ım|im)|uygula(?:ma)?|her seferinde|tek doz|use)\\s*[:=-]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:damla|drop)\\b"),
        MedicationForm.NEBULE to Regex("(?i)(?:kullan(?:ım|im)|uygula(?:ma)?|her seferinde|tek doz|use)\\s*[:=-]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:nebül|nebul|nebule|ampul|ampoule|flakon|vial)\\b"),
        MedicationForm.LIQUID to Regex("(?i)(?:kullan(?:ım|im)|uygula(?:ma)?|her seferinde|tek doz|use)\\s*[:=-]?\\s*(\\d+(?:[.,]\\d+)?)\\s*m[lL]\\b"),
        MedicationForm.TABLET to Regex("(?i)(?:kullan(?:ım|im)|al(?:ın|in)?|her seferinde|tek doz|take)\\s*[:=-]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:tablet|tab|kapsül|kapsul|capsule)\\b")
    )
    private fun safeExplicitQuantity(form: MedicationForm, text: String): Double? = explicitUseRegexes.firstOrNull { it.first == form }?.second?.find(text)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()

    fun suggest(name: String, ocrText: String = ""): MedicationSuggestion {
        val text = listOf(name, ocrText).filter { it.isNotBlank() }.joinToString("\n"); val lower = text.lowercase()
        val form = when {
            Regex("(?i)insulin|insülin|insülinum|humalog|novorapid|fiasp|lantus|levemir|toujeo|tresiba").containsMatchIn(text) -> MedicationForm.INSULIN
            Regex("(?i)nebül|nebul|nebule|nebülizat|nebuliz|respule").containsMatchIn(text) -> MedicationForm.NEBULE
            Regex("(?i)inhaler|inhalasyon|puf|puff|turbuhaler|diskus|ellipta|aerosol").containsMatchIn(text) -> MedicationForm.INHALER
            Regex("(?i)göz damlası|goz damlasi|eye drops|kulak damlası|burun damlası|damla|drops?").containsMatchIn(text) -> MedicationForm.DROP
            Regex("(?i)şurup|surup|syrup|oral solüsyon|oral solusyon|solution|süspansiyon|suspension").containsMatchIn(text) -> MedicationForm.LIQUID
            Regex("(?i)krem|cream|merhem|ointment|jel|gel|pomad").containsMatchIn(text) -> MedicationForm.CREAM
            Regex("(?i)yama|patch|transdermal").containsMatchIn(text) -> MedicationForm.PATCH
            Regex("(?i)enjeksiyon|injection|enjektabl|ampul|ampoule|flakon|vial|prefilled|kalem|pen").containsMatchIn(text) -> MedicationForm.INJECTION
            Regex("(?i)tablet|kapsül|kapsul|capsule|draje|film tablet").containsMatchIn(text) -> MedicationForm.TABLET
            else -> MedicationForm.OTHER
        }
        // Box OCR is packaging/strength evidence, not a prescription. Never turn 100 U/mL, 30 tablets,
        // 120 doses, 5 mL etc. into an administration quantity. Quantity is accepted only from an
        // explicit usage phrase; SGK's structured dose field is parsed separately by the SGK importer.
        val quantity = safeExplicitQuantity(form, text)
        val pack = packRegex.findAll(text).mapNotNull { m -> val count=m.groupValues[1].toIntOrNull()?:return@mapNotNull null; count to m.groupValues[2] }
            .filter { it.first in 2..1000 }.maxByOrNull { it.first }
        val site = when {
            "sol göz" in lower || "left eye" in lower -> if (I18n.language()=="tr") "sol göz" else "left eye"
            "sağ göz" in lower || "sag goz" in lower || "right eye" in lower -> if (I18n.language()=="tr") "sağ göz" else "right eye"
            "iki göz" in lower || "both eyes" in lower -> if (I18n.language()=="tr") "iki göz" else "both eyes"
            else -> ""
        }
        val doseUnit = when(form) {
            MedicationForm.INSULIN -> "U"
            MedicationForm.TABLET -> if(Regex("(?i)kapsül|kapsul|capsule").containsMatchIn(text)) if(I18n.language()=="tr") "kapsül" else "capsule" else "tablet"
            MedicationForm.NEBULE -> when { Regex("(?i)flakon|flk|vial").containsMatchIn(text)->if(I18n.language()=="tr")"flakon" else "vial"; Regex("(?i)ampul|ampoule").containsMatchIn(text)->if(I18n.language()=="tr")"ampul" else "ampoule"; else->if(I18n.language()=="tr")"nebül" else "nebule" }
            MedicationForm.INHALER -> if(Regex("(?i)kapsül|kapsul|capsule").containsMatchIn(text)) if(I18n.language()=="tr")"kapsül" else "capsule" else if(I18n.language()=="tr")"puf" else "puff"
            MedicationForm.DROP -> if(I18n.language()=="tr")"damla" else "drop"; MedicationForm.LIQUID -> "mL"
            MedicationForm.CREAM -> if(I18n.language()=="tr")"uygulama" else "application"; MedicationForm.PATCH -> if(I18n.language()=="tr")"yama" else "patch"
            MedicationForm.INJECTION -> if(I18n.language()=="tr")"doz" else "dose"; MedicationForm.OTHER -> ""
        }
        val suggestedName = ocrText.lines().map { it.trim() }.firstOrNull { it.length in 3..40 && it.any(Char::isLetter) && !it.matches(Regex(".*\\d{3,}.*")) }.orEmpty()
        return MedicationSuggestion(form, quantity, site, pack?.first, pack?.second.orEmpty(), suggestedName, if(ocrText.isNotBlank())0.82f else 0.65f, doseUnit)
    }
}
