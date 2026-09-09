package com.ozkanmut.ilactakip

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.util.UUID

/** Capabilities granted to this device by another owner. */
object RemoteCapabilityStore {
    private const val PREFS = "dosefolk_remote_capabilities"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(ownerTopic: String, permission: CirclePermission) = "$ownerTopic|${permission.name}"

    fun allowedByOwner(c: Context, ownerTopic: String, permission: CirclePermission): Boolean =
        prefs(c).getBoolean(key(ownerTopic, permission), false)

    fun applyEvent(c: Context, event: DoseEvent) {
        val permission = when {
            event.type.startsWith("capability_edit_program_") -> CirclePermission.EDIT_PROGRAM
            event.type.startsWith("capability_edit_stock_") -> CirclePermission.EDIT_STOCK
            else -> return
        }
        val granted = event.type.endsWith("_granted")
        val owner = event.ownerId.ifBlank { event.actorTopic }
        if (owner.isBlank()) return
        prefs(c).edit().putBoolean(key(owner, permission), granted).commit()
    }
}

/** Sends a permission grant directly to the intended peer topic, not to the whole Circle. */
object CapabilitySync {
    fun publish(c: Context, targetTopic: String, permission: CirclePermission, allowed: Boolean) {
        if (targetTopic.isBlank() || targetTopic == Store.topic(c)) return
        val type = when (permission) {
            CirclePermission.EDIT_PROGRAM -> "capability_edit_program_${if (allowed) "granted" else "revoked"}"
            CirclePermission.EDIT_STOCK -> "capability_edit_stock_${if (allowed) "granted" else "revoked"}"
            else -> return
        }
        val event = DoseEvent(
            eventId = UUID.randomUUID().toString(),
            type = type,
            time = permission.name,
            actor = Store.myName(c),
            actorTopic = Store.topic(c),
            timestamp = System.currentTimeMillis(),
            medications = emptyList(),
            syncState = "synced",
            revision = EventStore.nextRevision(c),
            ownerId = Store.topic(c)
        )
        EventStore.append(c, event)
        AlertOutbox.enqueue(c.applicationContext, targetTopic, "Dosefolk sync", EventStore.payload(event).toString())
    }
}

/** Owner-scoped program writes are delivered only to that owner topic. */
object ScopedNtfy {
    fun sendProgramChange(c: Context, ownerTopic: String, type: String, medication: Medication) {
        if (ownerTopic.isBlank() || type !in setOf("program_added", "program_updated", "program_deleted")) return
        val event = DoseEvent(
            eventId = UUID.randomUUID().toString(),
            type = type,
            time = medication.times.firstOrNull() ?: "program",
            actor = Store.myName(c),
            actorTopic = Store.topic(c),
            timestamp = System.currentTimeMillis(),
            medications = listOf(medication),
            syncState = "synced",
            revision = EventStore.nextRevision(c),
            ownerId = ownerTopic
        )
        EventStore.append(c, event)
        OwnerScopeStore.remember(c, event)
        OwnerScopeStore.applyRemoteProgram(c, ownerTopic, type, medication)
        AlertOutbox.enqueue(c.applicationContext, ownerTopic, "Dosefolk sync", EventStore.payload(event).toString())
    }
}

@Composable
fun RemoteProgramSection(c: Context, person: Person, refreshKey: Int) {
    var expanded by remember(person.id) { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Medication?>(null) }
    var adding by remember { mutableStateOf(false) }
    val meds = remember(refreshKey, expanded, editing, adding, person.topic) {
        OwnerScopeStore.remoteMedications(c, person.topic)
    }
    val canEdit = RemoteCapabilityStore.allowedByOwner(c, person.topic, CirclePermission.EDIT_PROGRAM)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (I18n.language() == "tr") {
                    if (expanded) "İlaç programını gizle" else "İlaç programı (${meds.size})"
                } else {
                    if (expanded) "Hide medication program" else "Medication program (${meds.size})"
                }
            )
        }
        if (expanded) {
            if (meds.isEmpty()) {
                Text(
                    if (I18n.language() == "tr") "Henüz bu kişiden program verisi gelmedi." else "No program data received from this person yet.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            meds.forEach { med ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(med.name, fontWeight = FontWeight.Bold)
                        if (med.dose.isNotBlank()) Text(med.dose)
                        Text(med.times.joinToString(" • ").ifBlank { if (I18n.language() == "tr") "Saat yok" else "No fixed time" })
                        if (canEdit) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { editing = med }) { Text(if (I18n.language() == "tr") "Düzenle" else "Edit") }
                                TextButton(onClick = { ScopedNtfy.sendProgramChange(c, person.topic, "program_deleted", med) }) {
                                    Text(if (I18n.language() == "tr") "Sil" else "Delete")
                                }
                            }
                        }
                    }
                }
            }
            if (canEdit) {
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (I18n.language() == "tr") "Bu kişiye ilaç ekle" else "Add medication for this person")
                }
            } else {
                Text(
                    if (I18n.language() == "tr") "Program düzenleme için bu kişinin sana yetki vermesi gerekir." else "This person must grant you program-edit permission before you can change it.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    editing?.let { med ->
        RemoteMedicationDialog(c, med, onDismiss = { editing = null }) { updated ->
            ScopedNtfy.sendProgramChange(c, person.topic, "program_updated", updated)
            editing = null
        }
    }
    if (adding) {
        RemoteMedicationDialog(c, Medication(UUID.randomUUID().toString(), "", "", emptyList()), onDismiss = { adding = false }) { added ->
            ScopedNtfy.sendProgramChange(c, person.topic, "program_added", added)
            adding = false
        }
    }
}

@Composable
private fun RemoteMedicationDialog(c: Context, initial: Medication, onDismiss: () -> Unit, onSave: (Medication) -> Unit) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var dose by remember(initial.id) { mutableStateOf(initial.dose) }
    var times by remember(initial.id) { mutableStateOf(initial.times) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (I18n.language() == "tr") "İlaç programı" else "Medication program") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(if (I18n.language() == "tr") "İlaç" else "Medication") })
                OutlinedTextField(dose, { dose = it }, label = { Text(if (I18n.language() == "tr") "Doz notu" else "Dose note") })
                times.forEach { time ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(time)
                        TextButton(onClick = { times = times.filterNot { it == time } }) { Text("×") }
                    }
                }
                OutlinedButton(onClick = {
                    val now = LocalTime.now()
                    TimePickerDialog(c, { _, h, m ->
                        times = (times + String.format("%02d:%02d", h, m)).distinct().sorted()
                    }, now.hour, now.minute, true).show()
                }) { Text(if (I18n.language() == "tr") "Saat ekle" else "Add time") }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { onSave(initial.copy(name = name.trim(), dose = dose.trim(), times = times)) }) {
                Text(if (I18n.language() == "tr") "Kaydet" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (I18n.language() == "tr") "İptal" else "Cancel") } }
    )
}
