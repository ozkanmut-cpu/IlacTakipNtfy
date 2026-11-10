package com.ozkanmut.ilactakip

import java.math.BigDecimal

enum class TabletChoice(val input: String) {
    ONE("1"), QUARTER("0.25"), HALF("0.5"), CUSTOM("")
}

data class MedicationAmount(val choice: TabletChoice, val text: String) {
    val quantity: Double?
        get() = text.trim().replace(',', '.').takeIf { decimal.matches(it) }
            ?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

    fun select(next: TabletChoice) = if (next == choice) this else MedicationAmount(next, next.input)

    companion object {
        private val decimal = Regex("""(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)""")

        fun newTablet() = MedicationAmount(TabletChoice.ONE, "1")

        fun existing(quantity: Double?): MedicationAmount {
            val text = quantity?.takeIf { it.isFinite() && it > 0 }?.let {
                BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()
            }.orEmpty()
            return MedicationAmount(TabletChoice.entries.firstOrNull { it.input == text } ?: TabletChoice.CUSTOM, text)
        }
    }
}
