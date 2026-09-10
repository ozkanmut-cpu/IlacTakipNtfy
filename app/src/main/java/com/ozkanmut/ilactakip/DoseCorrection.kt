package com.ozkanmut.ilactakip

import android.content.Context
import java.time.LocalDate

/**
 * Append-only dose correction. Original events are never deleted.
 * Undo emits a compensating event. A correction emits one explicit resolution
 * event so process death cannot strand the session between undo + replacement.
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
        return Ntfy.sendEvent(c, undoType, time, meds, scheduledDate)
    }

    fun correctToTaken(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()): Boolean {
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrDefault(LocalDate.now())
        val state = DoseStateEngine.stateForTime(c, time, date)
        if (state.status == DoseSessionStatus.TAKEN) return true
        val meds = state.medications.ifEmpty { Store.load(c).filter { time in it.times } }
        if (meds.isEmpty()) return false
        return Ntfy.sendEvent(c, "conflict_resolved_taken", time, meds, scheduledDate)
    }

    fun correctToMissed(c: Context, time: String, scheduledDate: String = LocalDate.now().toString()): Boolean {
        val date = runCatching { LocalDate.parse(scheduledDate) }.getOrDefault(LocalDate.now())
        val state = DoseStateEngine.stateForTime(c, time, date)
        if (state.status == DoseSessionStatus.MISSED) return true
        val meds = state.medications.ifEmpty { Store.load(c).filter { time in it.times } }
        if (meds.isEmpty()) return false
        return Ntfy.sendEvent(c, "conflict_resolved_missed", time, meds, scheduledDate)
    }
}
