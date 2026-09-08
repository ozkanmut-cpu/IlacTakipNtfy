package com.ozkanmut.ilactakip

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PrnScreen(c: Context, meds: List<Medication>) {
    var refresh by remember { mutableIntStateOf(0) }
    val configured = remember(refresh) { PrnEngine.all(c) }
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(if (I18n.language() == "tr") "Gerektiğinde (PRN)" else "As needed (PRN)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (I18n.language() == "tr") "Sınırlar yalnızca sen girersen uygulanır; Dosefolk klinik sınır tahmin etmez." else "Limits are enforced only when you enter them; Dosefolk never invents clinical limits.")
        }
        items(meds.filter { med -> configured.none { it.medicationId == med.id } }, key = { "new-${it.id}" }) { med ->
            var minInterval by remember(med.id) { mutableStateOf("") }
            var maxDay by remember(med.id) { mutableStateOf("") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(med.name, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        minInterval,
                        { minInterval = it.filter(Char::isDigit) },
                        label = { Text(if (I18n.language() == "tr") "Minimum aralık (dakika, opsiyonel)" else "Minimum interval (minutes, optional)") }
                    )
                    OutlinedTextField(
                        maxDay,
                        { maxDay = it.filter(Char::isDigit) },
                        label = { Text(if (I18n.language() == "tr") "Günlük maksimum (opsiyonel)" else "Daily maximum (optional)") }
                    )
                    Button(onClick = {
                        PrnEngine.create(c, med, minInterval.toIntOrNull(), maxDay.toIntOrNull())
                        refresh++
                    }) { Text(if (I18n.language() == "tr") "PRN olarak ekle" else "Add as PRN") }
                }
            }
        }
        items(configured, key = { it.id }) { item ->
            val check = remember(refresh, item.id) { PrnEngine.check(c, item) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.name, fontWeight = FontWeight.Bold)
                    item.minimumIntervalMinutes?.let { Text((if (I18n.language() == "tr") "Minimum aralık: " else "Minimum interval: ") + "$it dk") }
                    item.maximumPerDay?.let { Text((if (I18n.language() == "tr") "Günlük maksimum: " else "Daily maximum: ") + it) }
                    check.lastTakenAt?.let {
                        val t = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
                        Text((if (I18n.language() == "tr") "Son kullanım: " else "Last use: ") + t)
                    }
                    Text((if (I18n.language() == "tr") "Bugün: " else "Today: ") + check.takenToday)
                    if (!check.allowed) {
                        Text(
                            if (check.reason == "minimum_interval") {
                                if (I18n.language() == "tr") "Henüz tanımlı minimum aralık dolmadı." else "The configured minimum interval has not elapsed yet."
                            } else {
                                if (I18n.language() == "tr") "Tanımlı günlük maksimuma ulaşıldı." else "The configured daily maximum has been reached."
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        enabled = check.allowed,
                        onClick = { PrnEngine.recordTaken(c, item); refresh++ },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (I18n.language() == "tr") "Aldım" else "Taken") }
                    TextButton(onClick = { PrnEngine.delete(c, item.id); refresh++ }) {
                        Text(if (I18n.language() == "tr") "PRN'den çıkar" else "Remove PRN")
                    }
                }
            }
        }
    }
}
