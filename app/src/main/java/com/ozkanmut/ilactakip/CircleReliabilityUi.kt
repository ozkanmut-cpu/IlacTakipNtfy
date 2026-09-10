package com.ozkanmut.ilactakip

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Compact, problem-oriented Circle controls. These stay quiet unless useful. */
@Composable
fun CircleReliabilityCard(c: Context, people: List<Person>, externalRefresh: Int, onChanged: () -> Unit) {
    var localRefresh by remember { mutableIntStateOf(0) }
    var showDetails by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    val active = remember(externalRefresh, localRefresh) { TemporaryCareStore.active(c) }
    val receipt = remember(externalRefresh, localRefresh) { OfflineTrustReceipt.snapshot(c) }
    val unconfirmed = remember(externalRefresh, localRefresh, people) { people.filterNot { CirclePresence.confirmed(c, it.topic) } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                receipt.pendingEvents > 0 -> AssistChip(
                    onClick = { DosefolkSyncScheduler.kick(c) },
                    label = { Text(if (I18n.language() == "tr") "${receipt.pendingEvents} kayıt gönderilmeyi bekliyor" else "${receipt.pendingEvents} record(s) waiting to send") }
                )
                people.isNotEmpty() && unconfirmed.isNotEmpty() -> Text(
                    if (I18n.language() == "tr") "${unconfirmed.first().name} henüz bu telefonu kendi Circle’ına eklemedi. Karşı telefonda QR ile bu telefonu da ekle." else "${unconfirmed.first().name} has not added this phone to their Circle yet. Add this phone by QR on the other device too.",
                    fontWeight = FontWeight.Bold
                )
                people.isNotEmpty() && receipt.lastSuccessfulSync == 0L -> Text(
                    if (I18n.language() == "tr") "Circle bağlantısı doğrulandı; ilk senkron tamamlanıyor." else "Circle pairing is confirmed; first sync is finishing.",
                    fontWeight = FontWeight.Bold
                )
                people.isNotEmpty() -> Text(
                    if (I18n.language() == "tr") "✓ Circle hazır" else "✓ Circle ready",
                    fontWeight = FontWeight.Bold
                )
            }

            if (active != null) {
                val remainingMinutes = ((active.endsAt - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L)
                Text(
                    if (I18n.language() == "tr") "${active.personName} geçici olarak ilk takipçi • ${formatDuration(remainingMinutes)} kaldı"
                    else "${active.personName} is temporarily first responder • ${formatDuration(remainingMinutes)} left"
                )
                OutlinedButton(onClick = {
                    TemporaryCareStore.clear(c); localRefresh++; onChanged()
                }) { Text(if (I18n.language() == "tr") "Geçici bakımı bitir" else "End temporary care") }
            }

            if (people.isNotEmpty()) {
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (I18n.language() == "tr") if (showDetails) "Ayrıntıları gizle" else "Circle ayrıntıları" else if (showDetails) "Hide details" else "Circle details")
                }
            }

            if (showDetails && people.isNotEmpty()) {
                if (receipt.lastSuccessfulSync > 0L) {
                    Text(
                        if (I18n.language() == "tr") "Son senkron: ${formatTime(receipt.lastSuccessfulSync)}" else "Last sync: ${formatTime(receipt.lastSuccessfulSync)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                QaLogControls(c)

                TextButton(onClick = { showPermissions = !showPermissions }) {
                    Text(if (I18n.language() == "tr") if (showPermissions) "İzinleri gizle" else "İzinler ve program erişimi" else if (showPermissions) "Hide permissions" else "Permissions & program access")
                }
                if (showPermissions) {
                    people.forEach { person ->
                        val canStatus = PermissionPolicy.allowed(c, person.topic, CirclePermission.SET_STATUS) && PermissionPolicy.allowed(c, person.topic, CirclePermission.SNOOZE)
                        val canRemind = PermissionPolicy.allowed(c, person.topic, CirclePermission.REMIND)
                        val canEdit = PermissionPolicy.allowed(c, person.topic, CirclePermission.EDIT_PROGRAM) && PermissionPolicy.allowed(c, person.topic, CirclePermission.EDIT_STOCK)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(person.name, fontWeight = FontWeight.Bold)
                                PermissionRow(if (I18n.language() == "tr") "Doz durumunu girsin / ertelesin" else "Set dose status / snooze", canStatus) { value ->
                                    PermissionPolicy.set(c, person.topic, CirclePermission.SET_STATUS, value)
                                    PermissionPolicy.set(c, person.topic, CirclePermission.SNOOZE, value)
                                    localRefresh++; onChanged()
                                }
                                PermissionRow(if (I18n.language() == "tr") "Hatırlatma gönderebilsin" else "Can send reminders", canRemind) { value ->
                                    PermissionPolicy.set(c, person.topic, CirclePermission.REMIND, value)
                                    localRefresh++; onChanged()
                                }
                                PermissionRow(if (I18n.language() == "tr") "Program ve stoku düzenlesin" else "Edit program and stock", canEdit) { value ->
                                    PermissionPolicy.set(c, person.topic, CirclePermission.EDIT_PROGRAM, value)
                                    PermissionPolicy.set(c, person.topic, CirclePermission.EDIT_STOCK, value)
                                    localRefresh++; onChanged()
                                }
                                RemoteProgramSection(c, person, externalRefresh + localRefresh)
                            }
                        }
                    }
                }

                if (active == null) {
                    Text(if (I18n.language() == "tr") "Geçici bakım" else "Temporary care", fontWeight = FontWeight.Bold)
                    Text(
                        if (I18n.language() == "tr") "Gerekirse bir kişiyi 24 saatliğine ilk uyarılacak kişi yap." else "If needed, make one person the first responder for 24 hours.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    people.take(3).forEach { person ->
                        Button(onClick = { TemporaryCareStore.start(c, person, 24); localRefresh++; onChanged() }) {
                            Text(if (I18n.language() == "tr") "${person.name} • 24 sa" else "${person.name} • 24h")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

data class TrustReceiptSnapshot(
    val lastSuccessfulSync: Long,
    val pendingEvents: Int,
    val latestLocalEventAt: Long?
)

object OfflineTrustReceipt {
    fun snapshot(c: Context): TrustReceiptSnapshot {
        val topic = Store.topic(c)
        val latestLocal = EventStore.load(c)
            .asSequence()
            .filter { it.actorTopic == topic }
            .maxByOrNull { it.timestamp }
            ?.timestamp
        return TrustReceiptSnapshot(
            lastSuccessfulSync = SyncEngine.lastSuccess(c),
            pendingEvents = EventStore.pending(c).size,
            latestLocalEventAt = latestLocal
        )
    }
}

private fun formatTime(ms: Long): String = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(ms))

private fun formatDuration(minutes: Long): String {
    val safe = minutes.coerceAtLeast(0L)
    val hours = safe / 60L
    val mins = safe % 60L
    return when {
        hours > 0L && mins > 0L -> if (I18n.language() == "tr") "${hours} sa ${mins} dk" else "${hours}h ${mins}m"
        hours > 0L -> if (I18n.language() == "tr") "${hours} sa" else "${hours}h"
        else -> if (I18n.language() == "tr") "${mins} dk" else "${mins}m"
    }
}
