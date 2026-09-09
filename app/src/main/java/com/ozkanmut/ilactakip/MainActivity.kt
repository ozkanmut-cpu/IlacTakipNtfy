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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        Ntfy.retryPending(this); SyncEngine.pullOnce(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme()) { MedicationApp(this) } }
    }
}

data class Medication(val id: String, val name: String, val dose: String = "", val times: List<String>)
data class Person(val id: String, val name: String, val topic: String, val canEdit: Boolean = false)

object Store {
    private const val PREFS = "ilac_takip"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun myName(c: Context) = p(c).getString("my_name", null) ?: I18n.t("name")
    fun topic(c: Context): String { var v = p(c).getString("topic", null); if (v == null) { v = "dosefolk-${UUID.randomUUID().toString().replace("-", "").take(24)}"; p(c).edit().putString("topic", v).apply() }; return v }
    fun load(c: Context): List<Medication> { val a = JSONArray(p(c).getString("meds", "[]") ?: "[]"); return (0 until a.length()).map { i -> val o=a.getJSONObject(i); val t=o.getJSONArray("times"); Medication(o.getString("id"),o.getString("name"),o.optString("dose"),(0 until t.length()).map{t.getString(it)}) } }
    fun save(c: Context, meds: List<Medication>) { val a=JSONArray(); meds.forEach{m->a.put(JSONObject().put("id",m.id).put("name",m.name).put("dose",m.dose).put("times",JSONArray(m.times)))}; p(c).edit().putString("meds",a.toString()).apply(); AlarmScheduler.scheduleAll(c,meds) }
    fun people(c: Context): List<Person> { val a=JSONArray(p(c).getString("people","[]")?:"[]"); return (0 until a.length()).map{i->val o=a.getJSONObject(i);Person(o.getString("id"),o.getString("name"),o.getString("topic"),o.optBoolean("canEdit"))} }
    fun savePeople(c: Context, people: List<Person>) { val a=JSONArray(); people.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("topic",it.topic).put("canEdit",it.canEdit))};p(c).edit().putString("people",a.toString()).apply() }
}
private fun ui(tr:String,en:String)=if(I18n.language()=="tr")tr else en

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MedicationApp(context:Context){
 var tab by remember{mutableIntStateOf(0)};var meds by remember{mutableStateOf(Store.load(context))};var people by remember{mutableStateOf(Store.people(context))};var manageMeds by remember{mutableStateOf(false)};var addMed by remember{mutableStateOf(false)};var history by remember{mutableStateOf(false)};var stock by remember{mutableStateOf(false)};var prn by remember{mutableStateOf(false)}
 val labels=listOf(I18n.t("today"),I18n.t("follow"),I18n.t("assistant"));val icons=listOf("⌂","♥","🎙")
 Scaffold(topBar={TopAppBar(title={Column{Text(I18n.t("app"),fontWeight=FontWeight.Bold);Text(if(tab==2)I18n.t("manage_voice") else I18n.t("today_plan"),style=MaterialTheme.typography.bodySmall)}},actions={if(tab==0){TextButton(onClick={prn=true}){Text("PRN")};TextButton(onClick={stock=true}){Text(ui("Stok","Stock"))};TextButton(onClick={history=true}){Text(I18n.t("history"))};TextButton(onClick={manageMeds=true}){Text(I18n.t("meds"))}}})},bottomBar={NavigationBar{labels.forEachIndexed{i,s->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(icons[i])},label={Text(s)})}}}){pad->Box(Modifier.padding(pad).fillMaxSize()){when(tab){0->TodayScreen(context,meds);1->CircleScreen(context,people){people=it;Store.savePeople(context,it)};else->VoiceAssistantScreen(context)}}}
 if(manageMeds)ModalBottomSheet(onDismissRequest={manageMeds=false}){MedicationScreen(meds,{meds=it;Store.save(context,it)},{addMed=true});Spacer(Modifier.height(24.dp))}
 if(history)ModalBottomSheet(onDismissRequest={history=false}){HistoryScreen(context);Spacer(Modifier.height(24.dp))}
 if(stock)ModalBottomSheet(onDismissRequest={stock=false}){StockScreen(context,meds);Spacer(Modifier.height(24.dp))}
 if(prn)ModalBottomSheet(onDismissRequest={prn=false}){PrnScreen(context,meds);Spacer(Modifier.height(24.dp))}
 if(addMed)AddMedicationDialog(context,{addMed=false}){m->meds=meds+m;Store.save(context,meds);addMed=false}
}

@Composable fun TodayScreen(c:Context,meds:List<Medication>){
 var refresh by remember{mutableIntStateOf(0)};val action=remember(refresh,meds){NextBestActionEngine.calculate(c,meds)};val groups=meds.flatMap{m->m.times.map{it to m}}.groupBy({it.first},{it.second}).toSortedMap();val low=StockEngine.lowStock(c)
 LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  if(low.isNotEmpty())item{AssistChip(onClick={},label={Text(ui("${low.size} ilaçta düşük stok","Low stock on ${low.size} medication(s)"))})}
  item{Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(26.dp)){Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(action.title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(action.detail)
   when(action.kind){
    NextActionKind.REPAIR->{val issue=DosefolkCheck.issues(c).firstOrNull();if(issue!=null)Button(onClick={issue.fix(c)}){Text(ui("Düzelt","Fix"))}}
    NextActionKind.HANDLE_DOSE->{val t=action.time;if(t!=null){Button(onClick={Ntfy.sendEvent(c,"taken",t,action.medications);refresh++},modifier=Modifier.fillMaxWidth()){Text(I18n.t("all_taken"))};OutlinedButton(onClick={val until=AlarmScheduler.snoozeGroup(c,t,action.medications,30);Ntfy.sendEvent(c,"snoozed",t,action.medications,snoozeUntil=until);refresh++}){Text(I18n.t("snooze30"))}}}
    NextActionKind.RESOLVE_CONFLICT->{val t=action.time;if(t!=null){Text(ui("İki farklı kayıt var. Doğru durumu seç.","Two different records exist. Choose the correct state."),fontWeight=FontWeight.Bold);Button(onClick={ConflictResolver.resolveAsTaken(c,t);refresh++},modifier=Modifier.fillMaxWidth()){Text(ui("İçildi olarak çöz","Resolve as taken"))};OutlinedButton(onClick={ConflictResolver.resolveAsMissed(c,t);refresh++},modifier=Modifier.fillMaxWidth()){Text(ui("İçilmedi olarak çöz","Resolve as missed"))}}}
    else->Unit
   }
  }}}
  if(action.kind!=NextActionKind.ALL_GOOD)item{Text(ui("Bugünün planı","Today's plan"),fontWeight=FontWeight.Bold)}
  if(action.kind!=NextActionKind.ALL_GOOD)items(groups.entries.toList()){(time,list)->TimeCard(c,time,list)}
 }
}

@Composable fun TimeCard(c:Context,time:String,meds:List<Medication>){Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(time,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);AssistChip(onClick={},label={Text(I18n.t("med_count",meds.size))})};meds.forEach{Text("• ${it.name}${if(it.dose.isBlank())"" else "  ${it.dose}"}")};Button(onClick={Ntfy.sendEvent(c,"taken",time,meds)},modifier=Modifier.fillMaxWidth()){Text(I18n.t("all_taken"))};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={val until=AlarmScheduler.snoozeGroup(c,time,meds,30);Ntfy.sendEvent(c,"snoozed",time,meds,snoozeUntil=until)}){Text(I18n.t("snooze"))};TextButton(onClick={Ntfy.sendEvent(c,"missed",time,meds)}){Text(I18n.t("missed"))}}}}}

@Composable fun StockScreen(c:Context,meds:List<Medication>){var refresh by remember{mutableIntStateOf(0)};LazyColumn(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text(ui("Stok","Stock"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(ui("Paket boyutunu bir kez gir. Sonra Yeni kutu tek dokunuş.","Set pack size once. New Box is then one tap."))};items(meds,key={it.id}){m->val current=remember(refresh,m.id){StockEngine.forMedication(c,m.id)};var pack by remember(m.id){mutableStateOf("")};Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(m.name,fontWeight=FontWeight.Bold);if(current==null){OutlinedTextField(pack,{pack=it.filter(Char::isDigit)},label={Text(ui("Paket dozu","Pack doses"))});Button(enabled=(pack.toIntOrNull()?:0)>0,onClick={StockEngine.configure(c,m,pack.toInt());pack="";refresh++}){Text(ui("Stok takibini başlat","Start stock tracking"))}}else{Text(ui("Kalan: ${current.remainingDoses} doz","Remaining: ${current.remainingDoses} doses"));Text(ui("Kutu: ${current.packSize} doz","Pack: ${current.packSize} doses"),style=MaterialTheme.typography.bodySmall);if(current.remainingDoses<=current.lowThreshold)Text(ui("⚠ Düşük stok","⚠ Low stock"),fontWeight=FontWeight.Bold);Button(onClick={StockEngine.openNewBox(c,m.id);refresh++},modifier=Modifier.fillMaxWidth()){Text(ui("Yeni kutu","New box"))}}}}}}}

@Composable fun CircleScreen(c:Context,people:List<Person>,save:(List<Person>)->Unit){
 var name by remember{mutableStateOf("")};var topic by remember{mutableStateOf("")};var refresh by remember{mutableIntStateOf(0)}
 LaunchedEffect(Unit){while(true){withContext(Dispatchers.IO){SyncEngine.pullBlocking(c)};refresh++;delay(20_000L)}}
 val unresolved=remember(refresh){CircleState.unresolved(c)}
 LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  item{Text("Circle",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(ui("Sorunlar önce görünür. Açıkken Circle otomatik güncellenir.","Problems appear first. Circle refreshes automatically while open."))}
  item{CircleReliabilityCard(c,people,refresh){refresh++}}
  if(unresolved.isEmpty())item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text("✓ ${I18n.t("all_good")}",fontWeight=FontWeight.Bold)}}}
  items(unresolved,key={it.eventId}){event->
   val claim=CareBatonStore.active(c,event.time);val state=DoseStateEngine.stateForTime(c,event.time)
   Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Text("⚠ ${event.time}",fontWeight=FontWeight.Bold);if(event.medications.isNotEmpty())Text(event.medications.joinToString(", "){it.name})
    if(state.status==DoseSessionStatus.CONFLICT){Text(ui("Çelişkili kayıt bulundu","Conflicting records found"),fontWeight=FontWeight.Bold);Button(onClick={ConflictResolver.resolveAsTaken(c,event.time);refresh++},modifier=Modifier.fillMaxWidth()){Text(ui("İçildi olarak çöz","Resolve as taken"))};OutlinedButton(onClick={ConflictResolver.resolveAsMissed(c,event.time);refresh++},modifier=Modifier.fillMaxWidth()){Text(ui("İçilmedi olarak çöz","Resolve as missed"))}}
    if(claim==null)Button(onClick={CareBatonStore.claim(c,event.time);refresh++}){Text(ui("Ben ilgileniyorum","I'm handling this"))}else{Text(ui("${claim.actor} ilgileniyor","${claim.actor} is handling this"),fontWeight=FontWeight.Bold);if(claim.actorTopic==Store.topic(c))OutlinedButton(onClick={CareBatonStore.release(c,event.time);refresh++}){Text(ui("Sorumluluğu bırak","Release responsibility"))}}
   }}
  }
  item{HorizontalDivider();Text(ui("Circle üyeleri","Circle members"),fontWeight=FontWeight.Bold)}
  items(people,key={it.id}){p->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(p.name,fontWeight=FontWeight.Bold);Row(verticalAlignment=Alignment.CenterVertically){Text(I18n.t("can_edit"),Modifier.weight(1f));Switch(checked=p.canEdit,onCheckedChange={v->save(people.map{if(it.id==p.id)it.copy(canEdit=v)else it})})};TextButton(onClick={Ntfy.sendTo(c,p.topic,I18n.t("reminder_title"),I18n.t("reminder_body"))}){Text(I18n.t("remind"))}}}}
  item{HorizontalDivider();Text(I18n.t("add_person"),fontWeight=FontWeight.Bold);OutlinedTextField(name,{name=it},label={Text(I18n.t("name"))});OutlinedTextField(topic,{topic=it},label={Text(I18n.t("pair_code"))});Button(enabled=name.isNotBlank()&&topic.isNotBlank(),onClick={save(people+Person(UUID.randomUUID().toString(),name.trim(),topic.trim()));name="";topic=""}){Text(I18n.t("add"))};Text(I18n.t("my_code",Store.topic(c)),style=MaterialTheme.typography.bodySmall)}
 }
}

@Composable fun MedicationScreen(meds:List<Medication>,save:(List<Medication>)->Unit,add:()->Unit){LazyColumn(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text(I18n.t("meds"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Button(onClick=add,modifier=Modifier.fillMaxWidth()){Text(I18n.t("add_med"))}};items(meds,key={it.id}){m->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp).fillMaxWidth()){Column(Modifier.weight(1f)){Text(m.name,fontWeight=FontWeight.Bold);Text(m.times.joinToString(" • "));if(m.dose.isNotBlank())Text(m.dose)};TextButton(onClick={save(meds.filterNot{it.id==m.id})}){Text(I18n.t("delete"))}}}}}}
@Composable fun HistoryScreen(c:Context){val events=EventStore.load(c);LazyColumn(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text(I18n.t("history"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)};if(events.isEmpty())item{Text(I18n.t("history_empty"))}else items(events,key={it.eventId}){e->val dt=LocalDateTime.ofInstant(Instant.ofEpochMilli(e.timestamp),ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text("${e.time} • ${e.type}",fontWeight=FontWeight.Bold);if(e.medications.isNotEmpty())Text(e.medications.joinToString(", "){it.name});Text("${e.actor} • $dt",style=MaterialTheme.typography.bodySmall)}}}}}
@Composable fun AddMedicationDialog(c:Context,close:()->Unit,add:(Medication)->Unit){var name by remember{mutableStateOf("")};var dose by remember{mutableStateOf("")};var times by remember{mutableStateOf(listOf<String>())};AlertDialog(onDismissRequest=close,title={Text(I18n.t("new_med"))},text={Column{OutlinedTextField(name,{name=it},label={Text(I18n.t("med_name"))});OutlinedTextField(dose,{dose=it},label={Text(I18n.t("dose_note"))});times.forEach{Text(it)};OutlinedButton(onClick={val n=LocalTime.now();TimePickerDialog(c,{_,h,m->times=(times+String.format("%02d:%02d",h,m)).distinct().sorted()},n.hour,n.minute,true).show()}){Text(I18n.t("add_time"))}}},confirmButton={Button(enabled=name.isNotBlank()&&times.isNotEmpty(),onClick={add(Medication(UUID.randomUUID().toString(),name.trim(),dose.trim(),times))}){Text(I18n.t("save"))}},dismissButton={TextButton(onClick=close){Text(I18n.t("cancel"))}})}