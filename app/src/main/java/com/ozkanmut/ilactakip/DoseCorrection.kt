package com.ozkanmut.ilactakip

import android.content.Context
import java.time.LocalDate

/**
 * Append-only dose correction. Original events are never deleted.
 * Undo emits a compensating event; correction emits undo + the new final state.
 */
object DoseCorrectionEngine {
    fun undo(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()): Boolean {
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrDefault(LocalDate.now())
        val state = DoseStateEngine.stateForTime(c, time, date)
        val latest = state.latestEvent ?: return false
        val meds = state.medications.ifEmpty { latest.medications }
        val undoType = when (state.status) {
            DoseSessionStatus.TAKEN -> "undo_taken"
            DoseSessionStatus.MISSED -> "undo_missed"
            else -> return false
        }
        Ntfy.sendEvent(c, undoType, time, meds, scheduledDate)
        return true
    }

    fun correctToTaken(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()): Boolean {
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrDefault(LocalDate.now())
        val state = DoseStateEngine.stateForTime(c, time, date)
        if (state.status == DoseSessionStatus.TAKEN) return true
        val meds = state.medications.ifEmpty { Store.load(c).filter { time in it.times } }
        if (state.status == DoseSessionStatus.MISSED) Ntfy.sendEvent(c, "undo_missed", time, meds, scheduledDate)
        if (meds.isEmpty()) return false
        Ntfy.sendEvent(c, "taken", time, meds, scheduledDate)
        return true
    }

    fun correctToMissed(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()): Boolean {
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrDefault(LocalDate.now())
        val state = DoseStateEngine.stateForTime(c, time, date)
        if (state.status == DoseSessionStatus.MISSED) return true
        val meds = state.medications.ifEmpty { Store.load(c).filter { time in it.times } }
        if (state.status == DoseSessionStatus.TAKEN) Ntfy.sendEvent(c, "undo_taken", time, meds, scheduledDate)
        if (meds.isEmpty()) return false
        Ntfy.sendEvent(c, "missed", time, meds, scheduledDate)
        return true
    }
}
