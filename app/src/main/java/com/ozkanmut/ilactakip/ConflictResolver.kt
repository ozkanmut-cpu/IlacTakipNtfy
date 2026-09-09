package com.ozkanmut.ilactakip

import android.content.Context
import java.time.LocalDate

/** Resolves contradictory dose records without deleting either original event. */
object ConflictResolver {
    fun resolveAsTaken(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) =
        resolve(c, time, scheduledDate, "taken")

    fun resolveAsMissed(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()) =
        resolve(c, time, scheduledDate, "missed")

    private fun resolve(c: Context, time: String, scheduledDate: String, finalType: String) {
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrDefault(LocalDate.now())
        val state = DoseStateEngine.stateForTime(c, time, date)
        if (state.status != DoseSessionStatus.CONFLICT) return
        val meds = state.medications.ifEmpty {
            Store.load(c).filter { time in it.times && ProgramRuleStore.isActiveOn(c, it.id, date) }
        }
        CareBatonStore.resolve(c, time, scheduledDate)
        Ntfy.sendEvent(c, finalType, time, meds, scheduledDate)
        Ntfy.sendEvent(c, "conflict_resolved_$finalType", time, meds, scheduledDate)
    }
}
