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
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.*

class MainActivity:ComponentActivity(){private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){};override fun onCreate(b:Bundle?){super.onCreate(b);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.POST_NOTIFICATIONS);setContent{MaterialTheme{MedicationApp(this)}}}}
data class Medication(val id:String,val name:String,val dose:String="",val times:List<String>)
data class Person(val id:String,val name:String,val topic:String,val canEdit:Boolean=false,val notifyMode:String="late")
data class Event(val id:String,val type:String,val time:String,val actor:String,val detail:String,val timestamp:Long)

object Store{private const val P="ilac_takip";private fun p(c:Context)=c.getSharedPreferences(P,Context.MODE_PRIVATE)
 fun myName(c:Context)=p(c).getString("my_name","Ben")?:"Ben";fun topic(c:Context):String{var v=p(c).getString("topic",null);if(v==null){v="ilac-${UUID.randomUUID().toString().replace("-","").take(24)}";p(c).edit().putString("topic",v).apply()};return v}
 fun load(c:Context):List<Medication>{val a=JSONArray(p(c).getString("meds","[]")?:"[]");return(0 until a.length()).map{i->val o=a.getJSONObject(i);val t=o.getJSONArray("times");Medication(o.getString("id"),o.getString("name"),o.optString("dose"),(0 until t.length()).map{t.getString(it)})}}
 fun save(c:Context,m:List<Medication>){val a=JSONArray();m.forEach{x->a.put(JSONObject().put("id",x.id).put("name",x.name).put("dose",x.dose).put("times",JSONArray(x.times)))};p(c).edit().putString("meds",a.toString()).apply();AlarmScheduler.scheduleAll(c,m)}
 fun people(c:Context):List<Person>{val a=JSONArray(p(c).getString("people","[]")?:"[]");return(0 until a.length()).map{i->val o=a.getJSONObject(i);Person(o.getString("id"),o.getString("name"),o.getString("topic"),o.optBoolean("canEdit"),o.optString("notifyMode","late"))}}
 fun savePeople(c:Context,x:List<Person>){val a=JSONArray();x.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("topic",it.topic).put("canEdit",it.canEdit).put("notifyMode",it.notifyMode))};p(c).edit().putString("people",a.toString()).apply()}
 fun addEvent(c:Context,type:String,time:String,detail:String,actor:String=myName(c)){val a=JSONArray(p(c).getString("events","[]")?:"[]");a.put(JSONObject().put("id",UUID.randomUUID().toString()).put("type",type).put("time",time).put("actor",actor).put("detail",detail).put("timestamp",System.currentTimeMillis()));while(a.length()>300)a.remove(0);p(c).edit().putString("events",a.toString()).apply()}
 fun events(c:Context):List<Event>{val a=JSONArray(p(c).getString("events","[]")?:"[]");return(0 until a.length()).map{i->val o=a.getJSONObject(i);Event(o.getString("id"),o.getString("type"),o.optString("time"),o.optString("actor"),o.optString("detail"),o.optLong("timestamp"))}.sortedByDescending{it.timestamp}}
}

@OptIn(ExperimentalMaterial3Api::class) @Composable fun MedicationApp(c:Context){var tab by remember{mutableIntStateOf(0)};var meds by remember{mutableStateOf(Store.load(c))};var people by remember{mutableStateOf(Store.people(c))};var add by remember{mutableStateOf(false)};Scaffold(topBar={TopAppBar(title={Column{Text("İlaç Takip",fontWeight=FontWeight.Bold);Text(if(tab==0)"Bugünün ilaç planı" else listOf("","İlaç programım","Takip ve yetkiler","Kim, ne yaptı?")[tab],style=MaterialTheme.typography.bodySmall)}})},bottomBar={NavigationBar{listOf("Bugün","İlaçlarım","Takip","Geçmiş").forEachIndexed{i,s->NavigationBarItem(tab==i,{tab=i},{Text(listOf("⌂","✚","♥","↺")[i])},label={Text(s)})}}},floatingActionButton={if(tab==1)FloatingActionButton({add=true}){Text("+")}}){p->Box(Modifier.padding(p).fillMaxSize()){when(tab){0->Today(c,meds);1->Meds(meds,{meds=it;Store.save(c,it)},{add=true});2->People(c,people){people=it;Store.savePeople(c,it)};else->History(c)}}};if(add)AddDialog(c,{add=false}){meds=meds+it;Store.save(c,meds);Store.addEvent(c,"program","", "${it.name} eklendi");add=false}}
@Composable fun Today(c:Context,meds:List<Medication>){val g=meds.flatMap{m->m.times.map{it to m}}.groupBy({it.first},{it.second}).toSortedMap();LazyColumn(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(20.dp)){Text("Bugün",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("${g.size} ilaç saati • ${meds.size} ilaç")}}};items(g.entries.toList()){(t,l)->TimeCard(c,t,l)}}}
@Composable fun TimeCard(c:Context,t:String,m:List<Medication>){Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(t,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("${m.size} ilaç")};m.forEach{Text("• ${it.name}${if(it.dose.isBlank())"" else " · ${it.dose}"}")};Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){Button({Store.addEvent(c,"taken",t,"${m.size} ilaç içildi");Ntfy.sendEvent(c,"taken",t,m)}){Text("İçtim")};OutlinedButton({AlarmScheduler.snoozeGroup(c,t,m,30);Store.addEvent(c,"snoozed",t,"30 dk ertelendi");Ntfy.sendEvent(c,"snoozed",t,m)}){Text("+30 dk")};TextButton({Store.addEvent(c,"missed",t,"${m.size} ilaç içilmedi");Ntfy.sendEvent(c,"missed",t,m)}){Text("İçilmedi")}}}}}
@Composable fun Meds(m:List<Medication>,save:(List<Medication>)->Unit,add:()->Unit){LazyColumn(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Button(add,Modifier.fillMaxWidth()){Text("Yeni ilaç ekle")}};items(m,key={it.id}){x->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp).fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text(x.name,fontWeight=FontWeight.Bold);Text(x.times.joinToString(" • "));if(x.dose.isNotBlank())Text(x.dose)};TextButton({save(m.filterNot{it.id==x.id})}){Text("Sil")}}}}}}
@Composable fun AddDialog(c:Context,close:()->Unit,add:(Medication)->Unit){var n by remember{mutableStateOf("")};var d by remember{mutableStateOf("")};var ts by remember{mutableStateOf(listOf<String>())};AlertDialog(close,{Column{OutlinedTextField(n,{n=it},label={Text("İlaç adı")});OutlinedTextField(d,{d=it},label={Text("Doz / not")});Text(ts.joinToString(" • "));OutlinedButton({val z=LocalTime.now();TimePickerDialog(c,{_,h,mi->ts=(ts+String.format("%02d:%02d",h,mi)).distinct().sorted()},z.hour,z.minute,true).show()}){Text("+ Saat ekle")}}},{Button(enabled=n.isNotBlank()&&ts.isNotEmpty(),onClick={add(Medication(UUID.randomUUID().toString(),n.trim(),d.trim(),ts))}){Text("Kaydet")}},dismissButton={TextButton(close){Text("Vazgeç")}},title={Text("Yeni ilaç")})}
@Composable fun People(c:Context,p:List<Person>,save:(List<Person>)->Unit){var n by remember{mutableStateOf("")};var t by remember{mutableStateOf("")};LazyColumn(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("Takip Ettiklerim",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)};items(p,key={it.id}){x->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(x.name,fontWeight=FontWeight.Bold);Row(verticalAlignment=Alignment.CenterVertically){Text("Programımı düzenleyebilir",Modifier.weight(1f));Switch(x.canEdit,{v->save(p.map{if(it.id==x.id)it.copy(canEdit=v)else it})})};Text("Bildirim: ${if(x.notifyMode=="all")"Tümü" else if(x.notifyMode=="missed")"Sadece içilmedi" else "Sadece gecikenler"}");Row{TextButton({Ntfy.sendTo(x.topic,"HATIRLATMA","${Store.myName(c)} ilaçlarını hatırlatıyor");Store.addEvent(c,"reminder","","${x.name} kişisine hatırlatma gönderildi")}){Text("Hatırlat")};TextButton({Ntfy.sendTo(x.topic,"İÇTİN Mİ?","İlaçlarını içtin mi?")}){Text("İçtin mi?")};TextButton({Ntfy.sendTo(x.topic,"BEN İLGİLENİYORUM","${Store.myName(c)} ilgileniyor")}){Text("İlgileniyorum")}}}};item{HorizontalDivider();Text("Kişi ekle",fontWeight=FontWeight.Bold);OutlinedTextField(n,{n=it},label={Text("Ad")});OutlinedTextField(t,{t=it},label={Text("Eşleştirme kodu")});Button(enabled=n.isNotBlank()&&t.isNotBlank(),onClick={save(p+Person(UUID.randomUUID().toString(),n.trim(),t.trim()));n="";t=""}){Text("Ekle")};Text("Benim kodum: ${Store.topic(c)}",style=MaterialTheme.typography.bodySmall)}}}
@Composable fun History(c:Context){val e=Store.events(c);LazyColumn(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{Text("Geçmiş",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)};items(e,key={it.id}){x->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text("${x.time} ${x.detail}",fontWeight=FontWeight.SemiBold);Text("${x.actor} · ${SimpleDateFormat("dd.MM HH:mm",Locale("tr","TR")).format(Date(x.timestamp))}",style=MaterialTheme.typography.bodySmall)}}}}}
