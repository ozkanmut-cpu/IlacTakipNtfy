package com.ozkanmut.ilactakip

import android.content.Context
import android.content.Intent
import java.time.LocalTime

/**
 * One-way, data-minimised bridge from Dosefolk to Orko Takip.
 *
 * Dosefolk remains the source of truth for medication status. Only a semantic
 * glucose timing anchor, the actual event timestamp and stable event identity
 * leave Dosefolk; medication names/doses are deliberately not broadcast.
 */
object OrkoTakipBridge {
    const val ACTION_MEDICATION_TAKEN = "com.dosefolk.action.ORKO_MEDICATION_TAKEN"
    const val ORKO_PACKAGE = "com.skhealth.guardian.mobile"

    const val EXTRA_EVENT_ID = "eventId"
    const val EXTRA_ANCHOR = "anchor"
    const val EXTRA_TAKEN_AT_MS = "takenAtMs"
    const val EXTRA_SCHEDULED_DATE = "scheduledDate"
    const val EXTRA_SCHEDULED_TIME = "scheduledTime"

    enum class Anchor {
        MORNING_FIRST_GROUP,
        MORNING_SECOND_POST_MEAL_GROUP,
        EVENING_COMBINED_POST_MEAL_GROUP,
        BEDTIME_TOUJEO,
        UNKNOWN
    }

    internal data class ScheduleGroup(
        val time: String,
        val hasInsulin: Boolean
    )

    fun observePersistedEvent(context: Context, event: DoseEvent) {
        if (event.type != "taken") return
        val localOwner = OwnerScopeStore.localOwnerId(context)
        if (event.ownerId.isNotBlank() && event.ownerId != localOwner) return

        val anchor = resolve(context, event.time)
        if (anchor == Anchor.UNKNOWN) {
            DosefolkQaLog.record(
                context,
                DosefolkQaLog.Category.SYNC,
                "orko_bridge_unmapped_taken",
                mapOf("time" to event.time, "eventId" to event.eventId)
            )
            return
        }

        val intent = Intent(ACTION_MEDICATION_TAKEN)
            .setPackage(ORKO_PACKAGE)
            .putExtra(EXTRA_EVENT_ID, event.eventId)
            .putExtra(EXTRA_ANCHOR, anchor.name)
            .putExtra(EXTRA_TAKEN_AT_MS, event.timestamp)
            .putExtra(EXTRA_SCHEDULED_DATE, event.scheduledDate)
            .putExtra(EXTRA_SCHEDULED_TIME, event.time)

        runCatching { context.sendBroadcast(intent) }
            .onSuccess {
                DosefolkQaLog.record(
                    context,
                    DosefolkQaLog.Category.SYNC,
                    "orko_bridge_sent",
                    mapOf("anchor" to anchor.name, "eventId" to event.eventId)
                )
            }
            .onFailure {
                DosefolkQaLog.record(
                    context,
                    DosefolkQaLog.Category.ERROR,
                    "orko_bridge_send_failed",
                    mapOf("anchor" to anchor.name, "error" to it.javaClass.simpleName)
                )
            }
    }

    fun resolve(context: Context, eventTime: String): Anchor {
        OrkoBridgeMappingStore.get(context, eventTime)?.let { return it }
        return inferredAnchor(context, eventTime)
    }

    fun inferredAnchor(context: Context, eventTime: String): Anchor {
        val meds = Store.load(context)
        val groups = meds
            .flatMap { med -> med.times.map { time -> time to med } }
            .groupBy({ it.first }, { it.second })
            .map { (time, groupMeds) ->
                ScheduleGroup(
                    time = time,
                    hasInsulin = groupMeds.any { med ->
                        MedicationMetaStore.get(context, med.id)?.form == MedicationForm.INSULIN
                    }
                )
            }
        return resolve(groups, eventTime)
    }

    internal fun resolve(groups: List<ScheduleGroup>, eventTime: String): Anchor {
        val parsed = groups.mapNotNull { group ->
            runCatching { LocalTime.parse(group.time) }.getOrNull()?.let { it to group }
        }.distinctBy { it.first }.sortedBy { it.first }
        val target = runCatching { LocalTime.parse(eventTime) }.getOrNull() ?: return Anchor.UNKNOWN

        val morning = parsed.filter { it.first < LocalTime.NOON }
        if (morning.getOrNull(0)?.first == target) return Anchor.MORNING_FIRST_GROUP
        if (morning.getOrNull(1)?.first == target) return Anchor.MORNING_SECOND_POST_MEAL_GROUP

        // Bedtime is inferred semantically from the latest insulin-containing
        // scheduled group at/after 20:00. Manual mappings can override this safely.
        val bedtime = parsed.lastOrNull {
            it.second.hasInsulin && !it.first.isBefore(LocalTime.of(20, 0))
        }
        if (bedtime?.first == target) return Anchor.BEDTIME_TOUJEO

        // Dinner anchor is the latest evening group other than the bedtime insulin group.
        val evening = parsed.filter {
            !it.first.isBefore(LocalTime.of(16, 0)) && it.first != bedtime?.first
        }
        if (evening.lastOrNull()?.first == target) return Anchor.EVENING_COMBINED_POST_MEAL_GROUP

        return Anchor.UNKNOWN
    }
}
