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
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);setContent{MaterialTheme(colorScheme=lightColorScheme()){MedicationApp(this)}}}
}
data class Medication(val id:String,val name:String,val dose:String="",val times:List<String>)
data class Person(val id:String,val name:String,val topic:String,val canEdit:Boolean=false)
object Store{
 private const val PREFS="ilac_takip";private fun p(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
 fun myName(c:Context)=p(c).getString("my_name",null)?:I18n.t("name");fun setMyName(c:Context,v:String)=p(c).edit().putString("my_name",v.trim()).apply()
 fun topic(c:Context):String{var v=p(c).getString("topic",null);if(v==null){v="dosefolk-${UUID.randomUUID().toString().replace("-","").take(24)}";p(c).edit().putString("topic",v).apply()};return v};fun setTopic(c:Context,v:String)=p(c).edit().putString("topic",v.trim()).apply()
 fun load(c:Context):List<Medication>{val a=JSONArray(p(c).getString("meds","[]")?:"[]");return(0 until a.length()).map{i->val o=a.getJSONObject(i);val t=o.getJSONArray("times");Medication(o.getString("id"),o.getString("name"),o.optString("dose"),(0 until t.length()).map{t.getString(it)})}}
 fun save(c:Context,m:List<Medication>){val a=JSONArray();m.forEach{x->a.put(JSONObject().put("id",x.id).put("name",x.name).put("dose",x.dose).put("times",JSONArray(x.times)))};p(c).edit().putString("meds",a.toString()).apply();AlarmScheduler.scheduleAll(c,m)}
 fun people(c:Context):List<Person>{val a=JSONArray(p(c).getString("people","[]")?:"[]");return(0 until a.length()).map{i->val o=a.getJSONObject(i);Person(o.getString("id"),o.getString("name"),o.getString("topic"),o.optBoolean("canEdit"))}}
 fun savePeople(c:Context,x:List<Person>){val a=JSONArray();x.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("topic",it.topic).put("canEdit",it.canEdit))};p(c).edit().putString("people",a.toString()).apply()}
}
@OptIn(ExperimentalMaterial3Api::class) @Composable fun MedicationApp(context:Context){var tab by remember{mutableIntStateOf(0)};var meds by remember{mutableStateOf(Store.load(context))};var people by remember{mutableStateOf(Store.people(context))};var add by remember{mutableStateOf(false)};val labels=listOf(I18n.t("today"),I18n.t("meds"),I18n.t("follow"),I18n.t("history"),I18n.t("assistant"));val icons=listOf("⌂","✚","♥","↺","🎙");Scaffold(topBar={TopAppBar(title={Column{Text(I18n.t("app"),fontWeight=FontWeight.Bold);Text(if(tab==4)I18n.t("manage_voice") else I18n.t("today_plan"),style=MaterialTheme.typography.bodySmall)}})},bottomBar={NavigationBar{labels.forEachIndexed{i,s->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(icons[i])},label={Text(s)})}}},floatingActionButton={if(tab==1)FloatingActionButton(onClick={add=true}){Text("+")}}){pad->Box(Modifier.padding(pad).fillMaxSize()){when(tab){0->TodayScreen(context,meds);1->MedicationScreen(meds,{meds=it;Store.save(context,it)},{add=true});2->PeopleScreen(context,people){people=it;Store.savePeople(context,it)};3->HistoryScreen();else->VoiceAssistantScreen(context)}}};if(add)AddMedicationDialog(context,{add=false}){m->meds=meds+m;Store.save(context,meds);add=false}}
@Composable fun TodayScreen(c:Context,meds:List<Medication>){val groups=meds.flatMap{m->m.times.map{it to m}}.groupBy({it.first},{it.second}).toSortedMap();LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Card(shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp)){Text(I18n.t("today"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(I18n.t("med_times",groups.size,meds.size))}}};items(groups.entries.toList()){(time,list)->TimeCard(c,time,list)}}}
@Composable fun TimeCard(c:Context,time:String,meds:List<Medication>){Card(shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(time,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);AssistChip(onClick={},label={Text(I18n.t("med_count",meds.size))})};meds.forEach{Text("• ${it.name}${if(it.dose.isBlank())"" else "  ${it.dose}"}")};Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){Button(onClick={Ntfy.sendEvent(c,"taken",time,meds)}){Text(I18n.t("taken"))};OutlinedButton(onClick={AlarmScheduler.snoozeGroup(c,time,meds,30);Ntfy.sendEvent(c,"snoozed",time,meds)}){Text(I18n.t("snooze"))};TextButton(onClick={Ntfy.sendEvent(c,"missed",time,meds)}){Text(I18n.t("missed"))}}}}}
@Composable fun MedicationScreen(meds:List<Medication>,save:(List<Medication>)->Unit,add:()->Unit){LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Button(onClick=add,modifier=Modifier.fillMaxWidth()){Text(I18n.t("add_med"))}};items(meds,key={it.id}){m->Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)){Row(Modifier.padding(16.dp).fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text(m.name,fontWeight=FontWeight.Bold);Text(m.times.joinToString(" • "));if(m.dose.isNotBlank())Text(m.dose)};TextButton(onClick={save(meds.filterNot{it.id==m.id})}){Text(I18n.t("delete"))}}}}}}
@Composable fun AddMedicationDialog(c:Context,close:()->Unit,add:(Medication)->Unit){var name by remember{mutableStateOf("")};var dose by remember{mutableStateOf("")};var times by remember{mutableStateOf(listOf<String>())};AlertDialog(onDismissRequest=close,title={Text(I18n.t("new_med"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},label={Text(I18n.t("med_name"))});OutlinedTextField(dose,{dose=it},label={Text(I18n.t("dose_note"))});times.forEach{AssistChip(onClick={},label={Text(it)})};OutlinedButton(onClick={val n=LocalTime.now();TimePickerDialog(c,{_,h,m->times=(times+String.format("%02d:%02d",h,m)).distinct().sorted()},n.hour,n.minute,true).show()}){Text(I18n.t("add_time"))}}},confirmButton={Button(enabled=name.isNotBlank()&&times.isNotEmpty(),onClick={add(Medication(UUID.randomUUID().toString(),name.trim(),dose.trim(),times))}){Text(I18n.t("save"))}},dismissButton={TextButton(onClick=close){Text(I18n.t("cancel"))}})}
@Composable fun PeopleScreen(c:Context,people:List<Person>,save:(List<Person>)->Unit){var name by remember{mutableStateOf("")};var topic by remember{mutableStateOf("")};LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text(I18n.t("care_permissions"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(I18n.t("care_help"))};items(people,key={it.id}){p->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(p.name,fontWeight=FontWeight.Bold);Row(verticalAlignment=Alignment.CenterVertically){Text(I18n.t("can_edit"),Modifier.weight(1f));Switch(p.canEdit){v->save(people.map{if(it.id==p.id)it.copy(canEdit=v)else it})}};TextButton(onClick={Ntfy.sendTo(p.topic,I18n.t("reminder_title"),I18n.t("reminder_body"))}){Text(I18n.t("remind"))}}}};item{HorizontalDivider();Text(I18n.t("add_person"),fontWeight=FontWeight.Bold);OutlinedTextField(name,{name=it},label={Text(I18n.t("name"))});OutlinedTextField(topic,{topic=it},label={Text(I18n.t("pair_code"))});Button(enabled=name.isNotBlank()&&topic.isNotBlank(),onClick={save(people+Person(UUID.randomUUID().toString(),name.trim(),topic.trim()));name="";topic=""}){Text(I18n.t("add"))};Text(I18n.t("my_code",Store.topic(c)),style=MaterialTheme.typography.bodySmall)}}}
@Composable fun HistoryScreen(){LazyColumn(Modifier.fillMaxSize().padding(16.dp)){item{Text(I18n.t("history"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));Text(I18n.t("history_empty"))}}}
