package com.ozkanmut.ilactakip

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MaterialTheme { MedicationApp(this) } }
    }
}

data class Medication(val id: String, val name: String, val times: List<String>)

object Store {
    private const val PREFS = "ilac_takip"
    fun topic(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var value = p.getString("topic", null)
        if (value == null) {
            value = "ilac-${UUID.randomUUID().toString().replace("-", "").take(20)}"
            p.edit().putString("topic", value).apply()
        }
        return value
    }
    fun setTopic(context: Context, value: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("topic", value.trim()).apply()
    fun load(context: Context): List<Medication> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("meds", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val t = o.getJSONArray("times")
            Medication(o.getString("id"), o.getString("name"), (0 until t.length()).map { t.getString(it) })
        }
    }
    fun save(context: Context, meds: List<Medication>) {
        val arr = JSONArray()
        meds.forEach { m -> arr.put(JSONObject().put("id", m.id).put("name", m.name).put("times", JSONArray(m.times))) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("meds", arr.toString()).apply()
        AlarmScheduler.scheduleAll(context, meds)
    }
}

@Composable
fun MedicationApp(context: Context) {
    var meds by remember { mutableStateOf(Store.load(context)) }
    var name by remember { mutableStateOf("") }
    var times by remember { mutableStateOf("08:00, 20:00") }
    var topic by remember { mutableStateOf(Store.topic(context)) }

    Scaffold(topBar = { TopAppBar(title = { Text("İlaç Takip") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("ntfy takip konusu", style = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(topic, { topic = it }, label = { Text("Topic") }, modifier = Modifier.fillMaxWidth()) }
            item { Button(onClick = { Store.setTopic(context, topic) }) { Text("Topic'i kaydet") } }
            item { HorizontalDivider() }
            item { OutlinedTextField(name, { name = it }, label = { Text("İlaç adı") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(times, { times = it }, label = { Text("Saatler (08:00, 14:00, 20:00)") }, modifier = Modifier.fillMaxWidth()) }
            item {
                Button(onClick = {
                    val parsed = times.split(",").map { it.trim() }.filter { Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(it) }.distinct()
                    if (name.isNotBlank() && parsed.isNotEmpty()) {
                        meds = meds + Medication(UUID.randomUUID().toString(), name.trim(), parsed)
                        Store.save(context, meds); name = ""
                    }
                }) { Text("İlaç ekle") }
            }
            items(meds, key = { it.id }) { med ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(med.name, style = MaterialTheme.typography.titleMedium)
                        Text(med.times.joinToString(" • "))
                        TextButton(onClick = { meds = meds.filterNot { it.id == med.id }; Store.save(context, meds) }) { Text("Sil") }
                    }
                }
            }
        }
    }
}
