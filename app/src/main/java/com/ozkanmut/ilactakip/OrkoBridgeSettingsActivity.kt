package com.ozkanmut.ilactakip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class OrkoBridgeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OrkoBridgeSettingsScreen(this)
            }
        }
    }
}

private data class BridgeGroup(
    val time: String,
    val medicines: List<Medication>,
    val inferred: OrkoTakipBridge.Anchor,
    val saved: OrkoTakipBridge.Anchor?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrkoBridgeSettingsScreen(context: android.content.Context) {
    var revision by remember { mutableIntStateOf(0) }
    val groups = remember(revision) {
        Store.load(context)
            .flatMap { med -> med.times.map { time -> time to med } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (time, medicines) ->
                BridgeGroup(
                    time = time,
                    medicines = medicines,
                    inferred = OrkoTakipBridge.inferredAnchor(context, time),
                    saved = OrkoBridgeMappingStore.get(context, time)
                )
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Orko Takip bağlantısı", fontWeight = FontWeight.Bold)
                        Text("İlaç gruplarının şeker ölçümündeki anlamı", style = MaterialTheme.typography.bodySmall)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Dosefolk ilaç verisi Orko Takip'e kopyalanmaz.", fontWeight = FontWeight.Bold)
                        Text("Yalnızca seçilen grubun anlamı ve gerçek 'içildi' zamanı gönderilir. Otomatik eşleme yanlışsa aşağıdan düzeltebilirsin.")
                    }
                }
            }

            if (groups.isEmpty()) {
                item { Text("Henüz saatli ilaç grubu yok.") }
            }

            items(groups, key = { it.time }) { group ->
                BridgeGroupCard(context, group) { revision++ }
            }
        }
    }
}

@Composable
private fun BridgeGroupCard(
    context: android.content.Context,
    group: BridgeGroup,
    onChanged: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val effective = group.saved ?: group.inferred
    val effectiveLabel = anchorLabel(effective)
    val source = if (group.saved == null) "Otomatik" else "Manuel"

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(group.time, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                AssistChip(onClick = {}, label = { Text(source) })
            }
            Text(group.medicines.joinToString(" • ") { it.name }, style = MaterialTheme.typography.bodySmall)
            Text(effectiveLabel, fontWeight = FontWeight.Bold)

            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Eşlemeyi değiştir")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Otomatik: ${anchorLabel(group.inferred)}") },
                        onClick = {
                            OrkoBridgeMappingStore.set(context, group.time, null)
                            expanded = false
                            onChanged()
                        }
                    )
                    selectableAnchors().forEach { anchor ->
                        DropdownMenuItem(
                            text = { Text(anchorLabel(anchor)) },
                            onClick = {
                                OrkoBridgeMappingStore.set(context, group.time, anchor)
                                expanded = false
                                onChanged()
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun selectableAnchors() = listOf(
    OrkoTakipBridge.Anchor.MORNING_FIRST_GROUP,
    OrkoTakipBridge.Anchor.MORNING_SECOND_POST_MEAL_GROUP,
    OrkoTakipBridge.Anchor.EVENING_COMBINED_POST_MEAL_GROUP,
    OrkoTakipBridge.Anchor.BEDTIME_TOUJEO,
    OrkoTakipBridge.Anchor.UNKNOWN
)

private fun anchorLabel(anchor: OrkoTakipBridge.Anchor): String = when (anchor) {
    OrkoTakipBridge.Anchor.MORNING_FIRST_GROUP -> "Sabah ilk grup → açlık şekeri"
    OrkoTakipBridge.Anchor.MORNING_SECOND_POST_MEAL_GROUP -> "Sabah tok grup → kahvaltı +2 saat / öğlen"
    OrkoTakipBridge.Anchor.EVENING_COMBINED_POST_MEAL_GROUP -> "Akşam tok grup → akşam +2 saat"
    OrkoTakipBridge.Anchor.BEDTIME_TOUJEO -> "Yatmadan önce / Toujeo"
    OrkoTakipBridge.Anchor.UNKNOWN -> "Şeker planında kullanılmasın"
}
