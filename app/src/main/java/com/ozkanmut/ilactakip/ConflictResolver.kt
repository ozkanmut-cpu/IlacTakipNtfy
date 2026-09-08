package com.ozkanmut.ilactakip

import android.content.Context

/** Resolves contradictory dose records without deleting either original event. */
object ConflictResolver {
    fun resolveAsTaken(c: Context, time: String) = resolve(c, time, "taken")
    fun resolveAsMissed(c: Context, time: String) = resolve(c, time, "missed")

    private fun resolve(c: Context, time: String, finalType: String) {
        val state = DoseStateEngine.stateForTime(c, time)
        if (state.status != DoseSessionStatus.CONFLICT) return
        val meds = state.medications.ifEmpty { Store.load(c).filter { time in it.times } }
        CareBatonStore.resolve(c, time)
        Ntfy.sendEvent(c, finalType, time, meds)
        Ntfy.sendEvent(c, "conflict_resolved_$finalType", time, meds)
    }
}
