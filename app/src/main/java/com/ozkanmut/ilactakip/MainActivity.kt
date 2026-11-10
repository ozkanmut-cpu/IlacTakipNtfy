package com.ozkanmut.ilactakip

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : ComponentActivity() {
 private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
 override fun onCreate(savedInstanceState: Bundle?) {
     super.onCreate(savedInstanceState)
     if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
         notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
     }
     Ntfy.retryPending(this)
     SyncEngine.pullOnce(this)
     FcmRegistrationScheduler.ensure(this)
     setContent {
         val palette = if (isSystemInDarkTheme()) {
             darkColorScheme(primary = Color(0xFFEAC1D4), onPrimary = Color(0xFF351E2B))
         } else {
             lightColorScheme(primary = Color(0xFF5F344D), background = Color(0xFFF6F4EF), surface = Color(0xFFFFFEFA))
         }
         MaterialTheme(colorScheme = palette) { MedicationApp(this) }
     }
 }
}
data class Medication(val id:String,val name:String,val dose:String="",val times:List<String>)
data class Person(val id:String,val name:String,val topic:String,val canEdit:Boolean=false)
object Store{
 private const val PREFS="ilac_takip";private fun p(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
 fun myName(c:Context)=p(c).getString("my_name",null)?:I18n.t("name")
 fun topic(c:Context):String{var v=p(c).getString("topic",null);if(v==null){v="dosefolk-${UUID.randomUUID().toString().replace("-","").take(24)}";p(c).edit().putString("topic",v).apply()};return v}
 fun load(c:Context):List<Medication>{val a=JSONArray(p(c).getString("meds","[]")?:"[]");return(0 until a.length()).map{i->val o=a.getJSONObject(i);val t=o.getJSONArray("times");Medication(o.getString("id"),o.getString("name"),o.optString("dose"),(0 until t.length()).map{t.getString(it)})}}
 fun save(c:Context,meds:List<Medication>){val a=JSONArray();meds.forEach{m->a.put(JSONObject().put("id",m.id).put("name",m.name).put("dose",m.dose).put("times",JSONArray(m.times)))};p(c).edit().putString("meds",a.toString()).apply();AlarmScheduler.scheduleAll(c,meds)}
 fun people(c:Context):List<Person>{val a=JSONArray(p(c).getString("people","[]")?:"[]");return(0 until a.length()).map{i->val o=a.getJSONObject(i);Person(o.getString("id"),o.getString("name"),o.getString("topic"),o.optBoolean("canEdit"))}}
 fun savePeople(c:Context,people:List<Person>){val a=JSONArray();people.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("topic",it.topic).put("canEdit",it.canEdit))};p(c).edit().putString("people",a.toString()).apply()}
 fun savePeopleChecked(c:Context,people:List<Person>):Boolean{val a=JSONArray();people.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("topic",it.topic).put("canEdit",it.canEdit))};return p(c).edit().putString("people",a.toString()).commit()}
}
private fun ui(tr:String,en:String)=if(I18n.language()=="tr")tr else en

enum class DailyViewMode(val tr: String, val en: String) {
    SIMPLE("Sade", "Simple"), STANDARD("Standart", "Standard"), ADVANCED("Gelişmiş", "Advanced");
    fun label() = ui(tr, en)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MedicationApp(context:Context){
 var tab by remember{mutableIntStateOf(0)};var meds by remember{mutableStateOf(Store.load(context))};var people by remember{mutableStateOf(Store.people(context))};var manageMeds by remember{mutableStateOf(false)};var addMed by remember{mutableStateOf(false)};var history by remember{mutableStateOf(false)};var stock by remember{mutableStateOf(false)};var prn by remember{mutableStateOf(false)};var more by remember{mutableStateOf(false)}
 val preferences = remember { context.getSharedPreferences("dosefolk_display", Context.MODE_PRIVATE) }
 var mode by remember { mutableStateOf(DailyViewMode.entries.firstOrNull { it.name == preferences.getString("daily_mode", null) } ?: DailyViewMode.STANDARD) }
 var editing by remember { mutableStateOf<Medication?>(null) }
 val labels=listOf(I18n.t("today"),I18n.t("follow"),I18n.t("assistant"));val icons=listOf("⌂","♥","🎙")
 Scaffold(topBar={TopAppBar(title={Column{Text(I18n.t("app"),fontWeight=FontWeight.Bold);Text(if(tab==2)I18n.t("manage_voice") else I18n.t("today_plan"),style=MaterialTheme.typography.bodySmall)}},actions={if(tab==0){Box{TextButton(onClick={more=true}){Text(ui("Diğer","More"))};DropdownMenu(expanded=more,onDismissRequest={more=false}){DropdownMenuItem(text={Text(I18n.t("meds"))},onClick={more=false;manageMeds=true});DropdownMenuItem(text={Text("PRN")},onClick={more=false;prn=true});DropdownMenuItem(text={Text(ui("Stok","Stock"))},onClick={more=false;stock=true});DropdownMenuItem(text={Text(I18n.t("history"))},onClick={more=false;history=true})}}}})},bottomBar={NavigationBar{labels.forEachIndexed{i,s->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(icons[i])},label={Text(s)})}}}){pad->Box(Modifier.padding(pad).fillMaxSize()){when(tab){0->TodayScreen(context,meds,mode){ selected -> mode=selected; preferences.edit().putString("daily_mode",selected.name).apply() };1->CircleScreen(context,people){people=it;Store.savePeople(context,it)};else->VoiceAssistantScreen(context)}}}
 if(manageMeds)ModalBottomSheet(onDismissRequest={manageMeds=false}){MedicationScreen(meds,{meds=it;Store.save(context,it)},{addMed=true},{editing=it});Spacer(Modifier.height(24.dp))}
 if(history)ModalBottomSheet(onDismissRequest={history=false}){HistoryScreen(context);Spacer(Modifier.height(24.dp))}
 if(stock)ModalBottomSheet(onDismissRequest={stock=false}){StockScreen(context,meds);Spacer(Modifier.height(24.dp))}
 if(prn)ModalBottomSheet(onDismissRequest={prn=false}){PrnScreen(context,meds);Spacer(Modifier.height(24.dp))}
 if (addMed || editing != null) AddMedicationDialogEnhanced(context, { addMed = false; editing = null }, editing, mode) { med ->
     val current = Store.load(context)
     meds = if (editing == null) current + med else current.map { if (it.id == med.id) med else it }
     Store.save(context, meds)
     addMed = false
     editing = null
 }
}
@Composable
fun TodayScreen(c: Context, meds: List<Medication>, mode: DailyViewMode, changeMode: (DailyViewMode) -> Unit) {
    var modeMenu by remember { mutableStateOf(false) }
    var showOther by remember(mode) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            refresh++
        }
    }
    val date = LocalDate.now()
    val states = remember(refresh, meds, date) {
        val schedule = meds.filter { ProgramRuleStore.isActiveOn(c, it.id, date) }
            .flatMap { med -> med.times.map { it to med } }.groupBy({ it.first }, { it.second })
        DoseStateEngine.today(c).mapNotNull { state ->
            when {
                state.status == DoseSessionStatus.UNKNOWN -> schedule[state.time]?.let { state.copy(medications = it) }
                state.medications.isNotEmpty() -> state
                else -> null
            }
        }
    }
    val (recorded, open) = states.partition { it.status in setOf(DoseSessionStatus.TAKEN, DoseSessionStatus.MISSED) }
    val pending = open.sortedBy { it.status != DoseSessionStatus.CONFLICT }
    val issue = remember(refresh) { DosefolkCheck.issues(c).firstOrNull() }
    val low = remember(refresh, meds) { StockEngine.lowStock(c) }
    val refills = remember(refresh, meds) { PrescriptionLifecycle.due(c) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val name = Store.myName(c).takeUnless { it.isBlank() || it == I18n.t("name") }
                    Text(listOfNotNull(name, ui("Kendi planım", "My plan")).joinToString(" · "), style = MaterialTheme.typography.titleLarge)
                    Text(date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)), style = MaterialTheme.typography.bodyMedium)
                }
                Box {
                    TextButton(onClick = { modeMenu = true }) { Text(ui("Görünüm", "View") + " · " + mode.label()) }
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        DailyViewMode.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label()) }, onClick = { modeMenu = false; changeMode(option) })
                        }
                    }
                }
            }
        }
        if (states.isEmpty()) item {
            Text(ui("Bugün için planlı ilaç yok.", "No medication is scheduled for today."))
        }
        val visible = if (mode == DailyViewMode.SIMPLE && !showOther) pending.take(1) else pending
        items(visible, key = { it.time }) { state ->
            TimeCard(c, state, advanced = mode == DailyViewMode.ADVANCED, featured = state.time == pending.firstOrNull()?.time) { refresh++ }
        }
        if (mode == DailyViewMode.SIMPLE && pending.size > 1) item {
            TextButton(onClick = { showOther = !showOther }) {
                Text(if (showOther) ui("Diğer saatleri gizle", "Hide other times") else ui("Diğer saatleri göster (${pending.size - 1})", "Show other times (${pending.size - 1})"))
            }
        }
        if (recorded.isNotEmpty()) {
            item {
                OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) ui("Kaydedilenleri gizle", "Hide recorded doses")
                        else ui("Kaydedilen dozları göster (${recorded.size})", "Show recorded doses (${recorded.size})"))
                }
            }
            if (expanded) items(recorded, key = { it.time }) { state -> TimeCard(c, state) { refresh++ } }
        }
        if (issue != null) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(issue.title, fontWeight = FontWeight.Bold)
                    Text(issue.detail)
                    TextButton(onClick = { issue.fix(c) }) { Text(ui("Düzelt", "Fix")) }
                }
            }
        }
        if (refills.isNotEmpty()) item {
            Text(ui("Yeniden temin", "Refills") + " · " + refills.joinToString { it.medicationName })
        }
        if (low.isNotEmpty()) item {
            Text(ui("${low.size} ilaçta düşük stok", "Low stock on ${low.size} medication(s)"))
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun TimeCard(c: Context, state: DoseSessionState, advanced: Boolean = false, featured: Boolean = false, onChange: () -> Unit) {
    val time = state.time
    val date = state.scheduledDate
    val meds = state.medications
    val status = state.status
    val statusText = when (status) {
        DoseSessionStatus.CONFLICT -> ui("Kontrol gerekli", "Needs review")
        DoseSessionStatus.SNOOZED -> ui("Ertelendi", "Snoozed")
        DoseSessionStatus.TAKEN -> ui("Alındı", "Taken")
        DoseSessionStatus.MISSED -> ui("Alınmadı", "Not taken")
        else -> ui("Bekliyor", "Pending")
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        if (featured) DailyDoseHeader(time, meds.size)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!featured) Text(time, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(statusText, style = MaterialTheme.typography.labelLarge)
            meds.forEach { medication ->
                Column {
                    Text(medication.name, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    if (medication.dose.isNotBlank()) Text(medication.dose)
                    if (advanced && medication.times.isNotEmpty()) {
                        Text(ui("Program saatleri", "Scheduled times") + " · " + medication.times.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            when (status) {
                DoseSessionStatus.CONFLICT -> {
                    Button(onClick = { ConflictResolver.resolveAsTaken(c, time, date); onChange() }, modifier = Modifier.fillMaxWidth()) {
                        Text(ui("Alındı olarak düzelt", "Resolve as taken"))
                    }
                    OutlinedButton(onClick = { ConflictResolver.resolveAsMissed(c, time, date); onChange() }, modifier = Modifier.fillMaxWidth()) {
                        Text(ui("Alınmadı olarak düzelt", "Resolve as not taken"))
                    }
                }
                DoseSessionStatus.TAKEN, DoseSessionStatus.MISSED -> {
                    TextButton(onClick = { if (DoseCorrectionEngine.undo(c, time, date)) onChange() }) { Text(ui("Geri al", "Undo")) }
                    val taken = status == DoseSessionStatus.TAKEN
                    TextButton(onClick = {
                        val changed = if (taken) DoseCorrectionEngine.correctToMissed(c, time, date)
                                      else DoseCorrectionEngine.correctToTaken(c, time, date)
                        if (changed) onChange()
                    }) { Text(if (taken) ui("Almadım olarak düzelt", "Correct to not taken") else ui("Aldım olarak düzelt", "Correct to taken")) }
                }
                else -> {
                    Button(onClick = { Ntfy.sendEvent(c, "taken", time, meds, date); onChange() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (meds.size == 1) I18n.t("taken") else I18n.t("all_taken"))
                    }
                    if (status != DoseSessionStatus.SNOOZED) {
                        OutlinedButton(onClick = {
                            val until = AlarmScheduler.snoozeGroup(c, time, meds, AlarmScheduler.DEFAULT_SNOOZE_MINUTES, date)
                            Ntfy.sendEvent(c, "snoozed", time, meds, date, until)
                            onChange()
                        }, modifier = Modifier.fillMaxWidth()) { Text(I18n.t("snooze_default")) }
                    }
                    TextButton(onClick = { Ntfy.sendEvent(c, "missed", time, meds, date); onChange() }) { Text(I18n.t("missed")) }
                }
            }
        }
    }
}

@Composable
private fun DailyDoseHeader(time: String, count: Int) {
    val hour = time.substringBefore(':').toIntOrNull() ?: 0
    val night = hour !in 5..20
    val title = when (hour) {
        in 5..11 -> ui("Sabah listesi", "Morning list")
        in 12..16 -> ui("Öğle listesi", "Afternoon list")
        in 17..20 -> ui("Akşam listesi", "Evening list")
        else -> ui("Gece listesi", "Night list")
    }
    val plum = Color(0xFF5B354A)
    val cream = Color(0xFFFFF5ED)
    Row(Modifier.fillMaxWidth().background(plum).padding(20.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = cream, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.headlineMedium)
            Text("$time · " + I18n.t("med_count", count), color = cream, style = MaterialTheme.typography.titleSmall)
        }
        Canvas(Modifier.size(48.dp)) {
            val center = Offset(size.width * .53f, size.height * .48f)
            drawCircle(Color(0xFFFFD5A6), radius = size.minDimension * .25f, center = center)
            if (night) {
                drawCircle(plum, radius = size.minDimension * .23f, center = Offset(center.x + size.width * .13f, center.y - size.height * .1f))
            } else {
                drawRect(plum, topLeft = Offset(0f, size.height * .56f))
                drawLine(cream, Offset(size.width * .12f, size.height * .57f), Offset(size.width * .92f, size.height * .57f), 2.dp.toPx())
                drawLine(cream, Offset(size.width * .31f, size.height * .7f), Offset(size.width * .73f, size.height * .7f), 1.dp.toPx())
            }
        }
    }
}

@Composable fun StockScreen(c:Context,meds:List<Medication>){
 var refresh by remember{mutableIntStateOf(0)}
 var showSetup by remember{mutableStateOf(false)}
 val tracked=remember(refresh,meds){meds.mapNotNull{m->StockEngine.forMedication(c,m.id)?.let{m to it}}}
 val untracked=remember(refresh,meds){meds.filter{m->StockEngine.forMedication(c,m.id)==null}}
 LazyColumn(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  item{Text(ui("Stok","Stock"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(ui("Kalan dozları gör; yeni kutu açınca tek dokunuşla güncelle.","See remaining doses and update with one tap when you open a new box."))}
  if(tracked.isEmpty()) item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(ui("Henüz stok takibi yok","No stock tracking yet"),fontWeight=FontWeight.Bold);Text(ui("İstersen kullandığın ilaçlardan biri için stok takibini başlatabilirsin.","You can start stock tracking for one of your medications when useful."),style=MaterialTheme.typography.bodySmall)}}}
  items(tracked,key={it.first.id}){(m,current)->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(m.name,fontWeight=FontWeight.Bold);Text(ui("${current.remainingDoses} doz kaldı","${current.remainingDoses} doses left"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);if(current.remainingDoses<=current.lowThreshold)Text(ui("⚠ Düşük stok","⚠ Low stock"),fontWeight=FontWeight.Bold);Text(ui("Kutu: ${current.packSize} doz","Pack: ${current.packSize} doses"),style=MaterialTheme.typography.bodySmall);Button(onClick={StockEngine.openNewBox(c,m.id);refresh++},modifier=Modifier.fillMaxWidth()){Text(ui("Yeni kutu açtım","Opened a new box"))}}}}
  if(untracked.isNotEmpty()) item{TextButton(onClick={showSetup=!showSetup}){Text(if(showSetup)ui("Stok eklemeyi kapat","Hide stock setup") else ui("Stok takibi ekle","Add stock tracking"))}}
  if(showSetup) items(untracked,key={"stock-${it.id}"}){m->var pack by remember(m.id){mutableStateOf("")};Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(m.name,fontWeight=FontWeight.Bold);OutlinedTextField(pack,{pack=it.filter(Char::isDigit)},label={Text(ui("Kutudaki doz sayısı","Doses per box"))},modifier=Modifier.fillMaxWidth());Button(enabled=(pack.toIntOrNull()?:0)>0,onClick={StockEngine.configure(c,m,pack.toInt());pack="";refresh++},modifier=Modifier.fillMaxWidth()){Text(ui("Takibi başlat","Start tracking"))}}}}
 }
}
@Composable fun CircleScreen(c:Context,people:List<Person>,save:(List<Person>)->Unit){var refresh by remember{mutableIntStateOf(0)};LaunchedEffect(Unit){while(true){withContext(Dispatchers.IO){SyncEngine.pullBlocking(c)};refresh++;delay(20_000L)}};val unresolved=remember(refresh){CircleState.unresolved(c)};LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("Circle",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(ui("Sorunlar önce görünür. Açıkken Circle otomatik güncellenir.","Problems appear first. Circle refreshes automatically while open."))};item{CircleReliabilityCard(c,people,refresh){refresh++}};if(unresolved.isEmpty())item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text("✓ ${I18n.t("all_good")}",fontWeight=FontWeight.Bold)}}};items(unresolved,key={it.eventId}){event->val scheduledDate=event.scheduledDate.ifBlank{LocalDate.now().toString()};val date=runCatching{LocalDate.parse(scheduledDate)}.getOrDefault(LocalDate.now());val claim=CareBatonStore.active(c,event.time,scheduledDate);val state=DoseStateEngine.stateForTime(c,event.time,date);val sessionMeds=state.medications.ifEmpty{event.medications};Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("⚠ ${event.time}",fontWeight=FontWeight.Bold);if(sessionMeds.isNotEmpty())Text(sessionMeds.joinToString(", "){it.name});if(state.status==DoseSessionStatus.CONFLICT){Text(ui("Çelişkili kayıt bulundu","Conflicting records found"),fontWeight=FontWeight.Bold);Button(onClick={ConflictResolver.resolveAsTaken(c,event.time,scheduledDate);refresh++},modifier=Modifier.fillMaxWidth()){Text(ui("İçildi olarak çöz","Resolve as taken"))};OutlinedButton(onClick={ConflictResolver.resolveAsMissed(c,event.time,scheduledDate);refresh++},modifier=Modifier.fillMaxWidth()){Text(ui("İçilmedi olarak çöz","Resolve as missed"))}};if(state.status==DoseSessionStatus.PENDING||state.status==DoseSessionStatus.SNOOZED){Button(onClick={Ntfy.sendEvent(c,"taken",event.time,sessionMeds,scheduledDate);refresh++},modifier=Modifier.fillMaxWidth()){Text(ui("İçildi","Taken"))};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={val until=AlarmScheduler.snoozeGroup(c,event.time,sessionMeds,AlarmScheduler.DEFAULT_SNOOZE_MINUTES,scheduledDate);Ntfy.sendEvent(c,"snoozed",event.time,sessionMeds,scheduledDate,until);refresh++}){Text(I18n.t("snooze_default"))};TextButton(onClick={Ntfy.sendEvent(c,"missed",event.time,sessionMeds,scheduledDate);refresh++}){Text(ui("İçilmedi","Missed"))}}};if(claim==null)Button(onClick={CareBatonStore.claim(c,event.time,scheduledDate=scheduledDate);refresh++}){Text(ui("Ben ilgileniyorum","I'm handling this"))}else{Text(ui("${claim.actor} ilgileniyor","${claim.actor} is handling this"),fontWeight=FontWeight.Bold);if(claim.actorTopic==Store.topic(c))OutlinedButton(onClick={CareBatonStore.release(c,event.time,scheduledDate);refresh++}){Text(ui("Sorumluluğu bırak","Release responsibility"))}}}}};item{HorizontalDivider();Text(ui("Circle üyeleri","Circle members"),fontWeight=FontWeight.Bold)};items(people,key={it.id}){p->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(14.dp).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(p.name,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));TextButton(onClick={Ntfy.sendTo(c,p.topic,I18n.t("reminder_title"),I18n.t("reminder_body"))}){Text(I18n.t("remind"))}}}};item{HorizontalDivider();PairingCard(c,people,save)}}}
@Composable fun MedicationScreen(meds:List<Medication>,save:(List<Medication>)->Unit,add:()->Unit,edit:(Medication)->Unit){
 var pendingDelete by remember{mutableStateOf<Medication?>(null)}
 LazyColumn(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  item{Text(I18n.t("meds"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Button(onClick=add,modifier=Modifier.fillMaxWidth()){Text(I18n.t("add_med"))}}
  items(meds,key={it.id}){m->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(m.name,fontWeight=FontWeight.Bold);Text(if(m.times.isEmpty())ui("Gerektiğinde (PRN)","As needed (PRN)") else m.times.joinToString(" • "));if(m.dose.isNotBlank())Text(m.dose)};Column{TextButton(onClick={edit(m)}){Text(ui("Düzenle","Edit"))};TextButton(onClick={pendingDelete=m}){Text(I18n.t("delete"))}}}}}
 }
 pendingDelete?.let{m->AlertDialog(onDismissRequest={pendingDelete=null},title={Text(ui("İlacı sil?","Delete medication?"))},text={Text(ui("${m.name} ilaç listesinden kaldırılacak. Geçmiş kayıtlar korunur.","${m.name} will be removed from your medication list. History is kept."))},confirmButton={Button(onClick={save(meds.filterNot{it.id==m.id});pendingDelete=null}){Text(ui("Sil","Delete"))}},dismissButton={TextButton(onClick={pendingDelete=null}){Text(I18n.t("cancel"))}})}
}
private fun historyEventLabel(type:String)=when(type){
    "taken"->ui("İçildi","Taken")
    "missed"->ui("İçilmedi","Missed")
    "snoozed"->ui("Ertelendi","Snoozed")
    "conflict_resolved_taken"->ui("Düzeltildi: içildi","Corrected: taken")
    "conflict_resolved_missed"->ui("Düzeltildi: içilmedi","Corrected: missed")
    "dose_undo"->ui("Geri alındı","Undone")
    "stock_update"->ui("Stok güncellendi","Stock updated")
    else->ui("Güncellendi","Updated")
}

@Composable fun HistoryScreen(c:Context){
    var refresh by remember{mutableIntStateOf(0)}
    val events=remember(refresh){EventStore.load(c)}
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text(I18n.t("history"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)}
        if(events.isEmpty()) item{Text(I18n.t("history_empty"))} else items(events,key={it.eventId}){e->
            val dt=LocalDateTime.ofInstant(Instant.ofEpochMilli(e.timestamp),ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text("${e.time} • ${historyEventLabel(e.type)}",fontWeight=FontWeight.Bold)
                    if(e.medications.isNotEmpty()) Text(e.medications.joinToString(", "){it.name})
                    Text("${e.actor} • $dt",style=MaterialTheme.typography.bodySmall)
                    if(e.type in setOf("taken","missed","conflict_resolved_taken","conflict_resolved_missed")){
                        val date=e.scheduledDate.ifBlank{LocalDateTime.ofInstant(Instant.ofEpochMilli(e.timestamp),ZoneId.systemDefault()).toLocalDate().toString()}
                        val state=DoseStateEngine.stateForTime(c,e.time,runCatching{LocalDate.parse(date)}.getOrDefault(LocalDate.now()))
                        if(state.latestEvent?.eventId==e.eventId||state.status in setOf(DoseSessionStatus.TAKEN,DoseSessionStatus.MISSED)){
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                OutlinedButton(onClick={if(DoseCorrectionEngine.undo(c,e.time,date))refresh++}){Text(ui("Geri al","Undo"))}
                                if(state.status==DoseSessionStatus.TAKEN) TextButton(onClick={if(DoseCorrectionEngine.correctToMissed(c,e.time,date))refresh++}){Text(ui("İçilmedi yap","Mark missed"))}
                                else if(state.status==DoseSessionStatus.MISSED) TextButton(onClick={if(DoseCorrectionEngine.correctToTaken(c,e.time,date))refresh++}){Text(ui("İçildi yap","Mark taken"))}
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable fun AddMedicationDialog(c:Context,close:()->Unit,add:(Medication)->Unit){var name by remember{mutableStateOf("")};var dose by remember{mutableStateOf("")};var times by remember{mutableStateOf(listOf<String>())};var prnOnly by remember{mutableStateOf(false)};var minInterval by remember{mutableStateOf("")};var maxDay by remember{mutableStateOf("")};AlertDialog(onDismissRequest=close,title={Text(I18n.t("new_med"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},label={Text(I18n.t("med_name"))});OutlinedTextField(dose,{dose=it},label={Text(I18n.t("dose_note"))});Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(ui("Sadece gerektiğinde (PRN)","As-needed only (PRN)"),Modifier.weight(1f));Switch(checked=prnOnly,onCheckedChange={prnOnly=it})};if(prnOnly){OutlinedTextField(minInterval,{minInterval=it.filter(Char::isDigit)},label={Text(ui("Minimum aralık, dk (opsiyonel)","Minimum interval, min (optional)"))});OutlinedTextField(maxDay,{maxDay=it.filter(Char::isDigit)},label={Text(ui("Günlük maksimum (opsiyonel)","Daily maximum (optional)"))});Text(ui("Bu sınırlar yalnızca sen girersen uygulanır.","These limits apply only if you enter them."),style=MaterialTheme.typography.bodySmall)}else{times.forEach{Text(it)};OutlinedButton(onClick={val n=LocalTime.now();TimePickerDialog(c,{_,h,m->times=(times+String.format("%02d:%02d",h,m)).distinct().sorted()},n.hour,n.minute,true).show()}){Text(I18n.t("add_time"))}}}},confirmButton={Button(enabled=name.isNotBlank()&&(prnOnly||times.isNotEmpty()),onClick={val med=Medication(UUID.randomUUID().toString(),name.trim(),dose.trim(),if(prnOnly)emptyList() else times);add(med);if(prnOnly)PrnEngine.create(c,med,minInterval.toIntOrNull(),maxDay.toIntOrNull())}){Text(I18n.t("save"))}},dismissButton={TextButton(onClick=close){Text(I18n.t("cancel"))}})}
