package com.ozkanmut.ilactakip

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Compact, problem-oriented Circle controls. These stay silent unless useful.
 */
@Composable
fun CircleReliabilityCard(c: Context, people: List<Person>, externalRefresh: Int, onChanged: () -> Unit) {
    var localRefresh by remember { mutableIntStateOf(0) }
    val active = remember(externalRefresh, localRefresh) { TemporaryCareStore.active(c) }
    val receipt = remember(externalRefresh, localRefresh) { OfflineTrustReceipt.snapshot(c) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (I18n.language() == "tr") "Bakım ve güven" else "Care & trust",
                fontWeight = FontWeight.Bold
            )

            if (active != null) {
                val remainingMinutes = ((active.endsAt - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L)
                Text(
                    if (I18n.language() == "tr")
                        "${active.personName} geçici olarak ilk takipçi • ${formatDuration(remainingMinutes)} kaldı"
                    else
                        "${active.personName} is temporarily first responder • ${formatDuration(remainingMinutes)} left"
                )
                OutlinedButton(onClick = {
                    TemporaryCareStore.clear(c)
                    localRefresh++
                    onChanged()
                }) {
                    Text(if (I18n.language() == "tr") "Geçici bakımı bitir" else "End temporary care")
                }
            } else if (people.isNotEmpty()) {
                Text(
                    if (I18n.language() == "tr") "Geçici bakım" else "Temporary care",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
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
                            TemporaryCareStore.start(c, person, 24)
                            localRefresh++
                            onChanged()
                        }) {
                            Text(
                                if (I18n.language() == "tr") "${person.name} • 24 sa" else "${person.name} • 24h"
                            )
                        }
                    }
                }
            }

            Text(
                if (I18n.language() == "tr") "Offline Trust Receipt" else "Offline Trust Receipt",
                fontWeight = FontWeight.Bold
            )
            if (receipt.lastSuccessfulSync == 0L) {
                Text(
                    if (I18n.language() == "tr")
                        "Henüz başarılı Circle senkronu yok. Yerel alarm ve kayıtlar yine telefonda çalışır."
                    else
                        "No successful Circle sync yet. Local alarms and records still work on this phone."
                )
            } else {
                Text(
                    if (I18n.language() == "tr")
                        "Son başarılı senkron: ${formatTime(receipt.lastSuccessfulSync)}"
                    else
                        "Last successful sync: ${formatTime(receipt.lastSuccessfulSync)}"
                )
            }
            when {
                receipt.pendingEvents > 0 -> AssistChip(
                    onClick = { DosefolkSyncScheduler.kick(c) },
                    label = {
                        Text(
                            if (I18n.language() == "tr")
                                "${receipt.pendingEvents} kayıt telefonda güvende, gönderim bekliyor"
                            else
                                "${receipt.pendingEvents} record(s) safe on phone, waiting to send"
                        )
                    }
                )
                receipt.lastSuccessfulSync > 0L -> Text(
                    if (I18n.language() == "tr") "✓ Circle ile senkronize" else "✓ Synced with Circle",
                    fontWeight = FontWeight.Bold
                )
            }
            receipt.latestLocalEventAt?.let { at ->
                Text(
                    if (I18n.language() == "tr")
                        "Son yerel kayıt: ${formatTime(at)} • cihazda kalıcı olarak saklandı"
                    else
                        "Latest local record: ${formatTime(at)} • durably stored on device",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
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
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}s ${m}dk" else "${m}dk"
}
