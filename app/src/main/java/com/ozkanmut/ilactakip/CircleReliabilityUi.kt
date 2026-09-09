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
import androidx.compose.material3.LinearProgressIndicator
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
    var showDigest by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    val active = remember(externalRefresh, localRefresh) { TemporaryCareStore.active(c) }
    val receipt = remember(externalRefresh, localRefresh) { OfflineTrustReceipt.snapshot(c) }
    val digest = remember(externalRefresh, localRefresh) { CareInsights.handover(c, 12) }
    val drift = remember(externalRefresh, localRefresh) { CareInsights.regimenDrift(c, 7) }
    val confidence = remember(externalRefresh, localRefresh) { DoseConfidenceEngine.recent(c, 7) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (I18n.language() == "tr") "Bakım ve güven" else "Care & trust", fontWeight = FontWeight.Bold)

            if (people.isNotEmpty()) {
                TextButton(onClick = { showPermissions = !showPermissions }) {
                    Text(
                        if (I18n.language() == "tr") {
                            if (showPermissions) "İzinleri gizle" else "Circle izinleri"
                        } else {
                            if (showPermissions) "Hide permissions" else "Circle permissions"
                        }
                    )
                }
                if (showPermissions) {
                    Text(
                        if (I18n.language() == "tr")
                            "İzinler bu telefonda uygulanır; karşı taraf kendi cihazından yetkisini artıramaz."
                        else
                            "Permissions are enforced on this phone; the other person cannot elevate them from their own device.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    people.forEach { person ->
                        val canStatus = PermissionPolicy.allowed(c, person.topic, CirclePermission.SET_STATUS) &&
                            PermissionPolicy.allowed(c, person.topic, CirclePermission.SNOOZE)
                        val canRemind = PermissionPolicy.allowed(c, person.topic, CirclePermission.REMIND)
                        val canEdit = PermissionPolicy.allowed(c, person.topic, CirclePermission.EDIT_PROGRAM) &&
                            PermissionPolicy.allowed(c, person.topic, CirclePermission.EDIT_STOCK)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(person.name, fontWeight = FontWeight.Bold)
                                PermissionRow(
                                    if (I18n.language() == "tr") "Doz durumunu girsin / ertelesin" else "Set dose status / snooze",
                                    canStatus
                                ) { value ->
                                    PermissionPolicy.set(c, person.topic, CirclePermission.SET_STATUS, value)
                                    PermissionPolicy.set(c, person.topic, CirclePermission.SNOOZE, value)
                                    localRefresh++; onChanged()
                                }
                                PermissionRow(
                                    if (I18n.language() == "tr") "Hatırlatma gönderebilsin" else "Can send reminders",
                                    canRemind
                                ) { value ->
                                    PermissionPolicy.set(c, person.topic, CirclePermission.REMIND, value)
                                    localRefresh++; onChanged()
                                }
                                PermissionRow(
                                    if (I18n.language() == "tr") "Program ve stoku düzenlesin" else "Edit program and stock",
                                    canEdit
                                ) { value ->
                                    PermissionPolicy.set(c, person.topic, CirclePermission.EDIT_PROGRAM, value)
                                    PermissionPolicy.set(c, person.topic, CirclePermission.EDIT_STOCK, value)
                                    localRefresh++; onChanged()
                                }
                                RemoteProgramSection(c, person, externalRefresh + localRefresh)
                            }
                        }
                    }
                }
            }

            if (active != null) {
                val remainingMinutes = ((active.endsAt - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L)
                Text(
                    if (I18n.language() == "tr")
                        "${active.personName} geçici olarak ilk takipçi • ${formatDuration(remainingMinutes)} kaldı"
                    else
                        "${active.personName} is temporarily first responder • ${formatDuration(remainingMinutes)} left"
                )
                OutlinedButton(onClick = {
                    TemporaryCareStore.clear(c); localRefresh++; onChanged()
                }) { Text(if (I18n.language() == "tr") "Geçici bakımı bitir" else "End temporary care") }
            } else if (people.isNotEmpty()) {
                Text(if (I18n.language() == "tr") "Geçici bakım" else "Temporary care", fontWeight = FontWeight.Bold)
                Text(
                    if (I18n.language() == "tr")
                        "Bir kişiyi kısa süreliğine ilk uyarılacak kişi yap. İlaç programı değişmez."
                    else
                        "Temporarily make one person the first responder. Medication schedules stay unchanged.",
                    style = MaterialTheme.typography.bodySmall
                )
                people.take(3).forEach { person ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            TemporaryCareStore.start(c, person, 24); localRefresh++; onChanged()
                        }) { Text(if (I18n.language() == "tr") "${person.name} • 24 sa" else "${person.name} • 24h") }
                    }
                }
            }

            Text("Offline Trust Receipt", fontWeight = FontWeight.Bold)
            if (receipt.lastSuccessfulSync == 0L) {
                Text(
                    if (I18n.language() == "tr")
                        "Henüz başarılı Circle senkronu yok. Yerel alarm ve kayıtlar yine telefonda çalışır."
                    else
                        "No successful Circle sync yet. Local alarms and records still work on this phone."
                )
            } else {
                Text(
                    if (I18n.language() == "tr") "Son başarılı senkron: ${formatTime(receipt.lastSuccessfulSync)}"
                    else "Last successful sync: ${formatTime(receipt.lastSuccessfulSync)}"
                )
            }
            when {
                receipt.pendingEvents > 0 -> AssistChip(
                    onClick = { DosefolkSyncScheduler.kick(c) },
                    label = { Text(if (I18n.language() == "tr") "${receipt.pendingEvents} kayıt telefonda, gönderim bekliyor" else "${receipt.pendingEvents} record(s) on phone, waiting to send") }
                )
                receipt.lastSuccessfulSync > 0L -> Text(
                    if (I18n.language() == "tr") "✓ Circle ile senkronize" else "✓ Synced with Circle",
                    fontWeight = FontWeight.Bold
                )
            }
            receipt.latestLocalEventAt?.let { at ->
                Text(
                    if (I18n.language() == "tr") "Son yerel kayıt: ${formatTime(at)} • cihazda kaydedildi"
                    else "Latest local record: ${formatTime(at)} • saved on this device",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(if (I18n.language() == "tr") "Doz güveni" else "Dose confidence", fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = { confidence.average.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (I18n.language() == "tr")
                    "Son 7 gün kayıt güveni: %${confidence.average}${if (confidence.uncertainCount > 0) " • ${confidence.uncertainCount} belirsiz oturum" else ""}"
                else
                    "Last 7 days record confidence: ${confidence.average}%${if (confidence.uncertainCount > 0) " • ${confidence.uncertainCount} uncertain session(s)" else ""}"
            )
            Text(
                if (I18n.language() == "tr")
                    "Bu puan yalnızca kayıt kanıtının netliğini gösterir; tıbbi değerlendirme değildir."
                else
                    "This score only reflects certainty of the recorded evidence; it is not a medical assessment.",
                style = MaterialTheme.typography.bodySmall
            )

            TextButton(onClick = { showDigest = !showDigest }) {
                Text(if (I18n.language() == "tr") "${if (showDigest) "Gizle" else "Son 12 saatin özeti"}" else if (showDigest) "Hide handover" else "Last 12h handover")
            }
            if (showDigest) {
                Text(if (I18n.language() == "tr") "Bakım devri özeti" else "Handover digest", fontWeight = FontWeight.Bold)
                Text(
                    if (I18n.language() == "tr")
                        "İçildi ${digest.taken} • İçilmedi ${digest.missed} • Ertelendi ${digest.snoozed} • Açık ${digest.unresolvedNow}"
                    else
                        "Taken ${digest.taken} • Missed ${digest.missed} • Snoozed ${digest.snoozed} • Open ${digest.unresolvedNow}"
                )
                if (digest.conflicts > 0) Text(if (I18n.language() == "tr") "⚠ ${digest.conflicts} çelişkili kayıt" else "⚠ ${digest.conflicts} conflicting record(s)")
                digest.recentLines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }

            if (drift.isNotEmpty()) {
                Text(if (I18n.language() == "tr") "Program sürtünmesi" else "Regimen friction", fontWeight = FontWeight.Bold)
                drift.take(3).forEach { item ->
                    val detail = if (I18n.language() == "tr") {
                        buildString {
                            append(item.time)
                            if (item.snoozedDays > 0) append(" • son 7 günde ${item.snoozedDays} gün ertelendi")
                            if (item.missedDays > 0) append(" • ${item.missedDays} gün içilmedi")
                        }
                    } else {
                        buildString {
                            append(item.time)
                            if (item.snoozedDays > 0) append(" • snoozed on ${item.snoozedDays} of the last 7 days")
                            if (item.missedDays > 0) append(" • missed on ${item.missedDays} days")
                        }
                    }
                    Text(detail)
                }
                Text(
                    if (I18n.language() == "tr")
                        "Dosefolk yalnızca tekrar eden kullanım desenini gösterir; programı kendiliğinden değiştirmez."
                    else
                        "Dosefolk only surfaces the repeated pattern; it never changes the medication schedule automatically.",
                    style = MaterialTheme.typography.bodySmall
                )
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
