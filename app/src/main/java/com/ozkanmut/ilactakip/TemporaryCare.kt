package com.ozkanmut.ilactakip

import android.content.Context

/**
 * Temporarily routes the first escalation to one Circle member.
 * This changes operational attention only; medication schedules are untouched.
 */
data class TemporaryCareWindow(
    val personId: String,
    val personName: String,
    val topic: String,
    val startsAt: Long,
    val endsAt: Long
)

object TemporaryCareStore {
    private const val PREFS = "dosefolk_temporary_care"
    private const val PERSON_ID = "person_id"
    private const val PERSON_NAME = "person_name"
    private const val TOPIC = "topic"
    private const val STARTS = "starts_at"
    private const val ENDS = "ends_at"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun active(c: Context): TemporaryCareWindow? {
        val p = prefs(c)
        val topic = p.getString(TOPIC, null) ?: return null
        val endsAt = p.getLong(ENDS, 0L)
        if (endsAt <= System.currentTimeMillis()) {
            clear(c)
            return null
        }
        return TemporaryCareWindow(
            p.getString(PERSON_ID, "") ?: "",
            p.getString(PERSON_NAME, "") ?: "",
            topic,
            p.getLong(STARTS, 0L),
            endsAt
        )
    }

    fun start(c: Context, person: Person, hours: Int = 24): TemporaryCareWindow {
        val now = System.currentTimeMillis()
        val window = TemporaryCareWindow(
            person.id,
            person.name,
            person.topic,
            now,
            now + hours.coerceIn(1, 168) * 60L * 60L * 1000L
        )
        prefs(c).edit()
            .putString(PERSON_ID, window.personId)
            .putString(PERSON_NAME, window.personName)
            .putString(TOPIC, window.topic)
            .putLong(STARTS, window.startsAt)
            .putLong(ENDS, window.endsAt)
            .apply()
        return window
    }

    fun clear(c: Context) { prefs(c).edit().clear().apply() }

    /** Active temporary caregiver first; remaining Circle order stays unchanged. */
    fun prioritizedPeople(c: Context): List<Person> {
        val people = Store.people(c)
        val window = active(c) ?: return people
        val selected = people.firstOrNull { it.id == window.personId || it.topic == window.topic }
            ?: Person(window.personId, window.personName, window.topic)
        return listOf(selected) + people.filterNot { it.topic == selected.topic }
    }
}
