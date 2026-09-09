package com.ozkanmut.ilactakip

import java.text.Normalizer
import java.util.Locale

/**
 * Stable non-clinical identity used only for matching the same product across SGK/OCR/local labels.
 * It deliberately removes strength, pack size and dosage-form noise, but never changes medication data.
 */
object MedicationIdentity {
    fun canonical(value: String): String {
        var s = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\([^)]*\\)"), " ")

        s = s.replace(
            Regex("\\b\\d+(?:[.,/]\\d+)?\\s*(?:mg|mcg|ug|g|gr|ml|u/ml|iu/ml|%)\\b", RegexOption.IGNORE_CASE),
            " "
        )
        s = s.replace(
            Regex("\\b\\d{1,4}\\s*(?:film\\s+tablet|tablet|tb|kapsul|capsule|flakon|flk|ampul|ampoule|neb|nebul|nebule|doz|dose|puf|puff|adet|piece|patch|yama)\\b", RegexOption.IGNORE_CASE),
            " "
        )
        s = s.replace(
            Regex("\\b(?:film\\s+tablet|tablet|tb|kapsul|capsule|flakon|flk|ampul|ampoule|neb|nebul|nebule|solusyon|solution|damla|drop|inhaler|krem|cream|jel|gel|pomad|patch|yama)\\b", RegexOption.IGNORE_CASE),
            " "
        )
        return s.replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    fun same(a: String, b: String): Boolean {
        val x = canonical(a)
        val y = canonical(b)
        return x.isNotBlank() && x == y
    }
}
