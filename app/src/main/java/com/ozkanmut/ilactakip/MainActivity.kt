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
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { MaterialTheme(colorScheme = lightColorScheme()) { MedicationApp(this) } }
    }
}

data class Medication(val id:String,val name:String,val dose:String="",val times:List<String>)
data class Person(val id:String,val name:String,val topic:String,val canEdit:Boolean=false)

object Store {
    private const val PREFS="ilac_takip"
    private fun p(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    fun myName(c:Context)=p(c).getString("my_name","Ben")?:"Ben"
    fun setMyName(c:Context,v:String)=p(c).edit().putString("my_name",v.trim()).apply()
    fun topic(c:Context):String { var v=p(c).getString("topic",null); if(v==null){v="ilac-${UUID.randomUUID().toString().replace("-","").take(24)}";p(c).edit().putString("topic",v).apply()};return v }
    fun setTopic(c:Context,v:String)=p(c).edit().putString("topic",v.trim()).apply()
    fun load(c:Context):List<Medication>{ val a=JSONArray(p(c).getString("meds","[]")?:"[]");return (0 until a.length()).map{i->val o=a.getJSONObject(i);val t=o.getJSONArray("times");Medication(o.getString("id"),o.getString("name"),o.optString("dose"),(0 until t.length()).map{t.getString(it)})} }
    fun save(c:Context,m:List<Medication>){val a=JSONArray();m.forEach{x->a.put(JSONObject().put("id",x.id).put("name",x.name).put("dose",x.dose).put("times",JSONArray(x.times)))};p(c).edit().putString("meds",a.toString()).apply();AlarmScheduler.scheduleAll(c,m)}
    fun people(c:Context):List<Person>{val a=JSONArray(p(c).getString("people","[]")?:"[]");return (0 until a.length()).map{i->val o=a.getJSONObject(i);Person(o.getString("id"),o.getString("name"),o.getString("topic"),o.optBoolean("canEdit"))}}
    fun savePeople(c:Context,x:List<Person>){val a=JSONArray();x.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("topic",it.topic).put("canEdit",it.canEdit))};p(c).edit().putString("people",a.toString()).apply()}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MedicationApp(context:Context){
    var tab by remember{mutableIntStateOf(0)}
    var meds by remember{mutableStateOf(Store.load(context))}
    var people by remember{mutableStateOf(Store.people(context))}
    var add by remember{mutableStateOf(false)}
    val labels=listOf("Bugün","İlaçlarım","Takip","Geçmiş","Asistan")
    val icons=listOf("⌂","✚","♥","↺","🎙")
    Scaffold(
        topBar={TopAppBar(title={Column{Text("İlaç Takip",fontWeight=FontWeight.Bold);Text(if(tab==4)"Konuşarak veya yazarak yönet" else "Bugünün ilaç planı",style=MaterialTheme.typography.bodySmall)}})},
        bottomBar={NavigationBar{labels.forEachIndexed{i,s->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(icons[i])},label={Text(s)})}}},
        floatingActionButton={if(tab==1) FloatingActionButton(onClick={add=true}){Text("+")}}
    ){pad->
        Box(Modifier.padding(pad).fillMaxSize()){
            when(tab){
                0->TodayScreen(context,meds)
                1->MedicationScreen(meds,{meds=it;Store.save(context,it)},{add=true})
                2->PeopleScreen(context,people){people=it;Store.savePeople(context,it)}
                3->HistoryScreen()
                else->VoiceAssistantScreen(context)
            }
        }
    }
    if(add) AddMedicationDialog(context,{add=false}){m->meds=meds+m;Store.save(context,meds);add=false}
}

@Composable fun TodayScreen(c:Context,meds:List<Medication>){
    val groups=meds.flatMap{m->m.times.map{it to m}}.groupBy({it.first},{it.second}).toSortedMap()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Card(shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp)){Text("Bugün",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("${groups.size} ilaç saati • ${meds.size} ilaç")}}}
        items(groups.entries.toList()){(time,list)->TimeCard(c,time,list)}
    }
}

@Composable fun TimeCard(c:Context,time:String,meds:List<Medication>){
    Card(shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(time,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);AssistChip(onClick={},label={Text("${meds.size} ilaç")})}
            meds.forEach{Text("• ${it.name}${if(it.dose.isBlank())"" else "  ${it.dose}"}")}
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Button(onClick={Ntfy.sendEvent(c,"taken",time,meds)}){Text("İçtim")}
                OutlinedButton(onClick={AlarmScheduler.snoozeGroup(c,time,meds,30);Ntfy.sendEvent(c,"snoozed",time,meds)}){Text("+30 dk")}
                TextButton(onClick={Ntfy.sendEvent(c,"missed",time,meds)}){Text("İçilmedi")}
            }
        }
    }
}

@Composable fun MedicationScreen(meds:List<Medication>,save:(List<Medication>)->Unit,add:()->Unit){
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Button(onClick=add,modifier=Modifier.fillMaxWidth()){Text("Yeni ilaç ekle")}}
        items(meds,key={it.id}){m->Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)){Row(Modifier.padding(16.dp).fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text(m.name,fontWeight=FontWeight.Bold);Text(m.times.joinToString(" • "));if(m.dose.isNotBlank())Text(m.dose)};TextButton(onClick={save(meds.filterNot{it.id==m.id})}){Text("Sil")}}}}
    }
}

@Composable fun AddMedicationDialog(c:Context,close:()->Unit,add:(Medication)->Unit){
    var name by remember{mutableStateOf("")};var dose by remember{mutableStateOf("")};var times by remember{mutableStateOf(listOf<String>())}
    AlertDialog(onDismissRequest=close,title={Text("Yeni ilaç")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},label={Text("İlaç adı")});OutlinedTextField(dose,{dose=it},label={Text("Doz / not")});times.forEach{AssistChip(onClick={},label={Text(it)})};OutlinedButton(onClick={val n=LocalTime.now();TimePickerDialog(c,{_,h,m->times=(times+String.format("%02d:%02d",h,m)).distinct().sorted()},n.hour,n.minute,true).show()}){Text("+ Saat ekle")}}},confirmButton={Button(enabled=name.isNotBlank()&&times.isNotEmpty(),onClick={add(Medication(UUID.randomUUID().toString(),name.trim(),dose.trim(),times))}){Text("Kaydet")}},dismissButton={TextButton(onClick=close){Text("Vazgeç")}})
}

@Composable fun PeopleScreen(c:Context,people:List<Person>,save:(List<Person>)->Unit){
    var name by remember{mutableStateOf("")};var topic by remember{mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("Takip ve yetkiler",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Takipçiler durum girebilir; izin verdiklerin ilaç programını da düzenleyebilir.")}
        items(people,key={it.id}){p->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(p.name,fontWeight=FontWeight.Bold);Row(verticalAlignment=Alignment.CenterVertically){Text("Programımı düzenleyebilir",Modifier.weight(1f));Switch(p.canEdit,{v->save(people.map{if(it.id==p.id)it.copy(canEdit=v)else it})})};TextButton(onClick={Ntfy.sendTo(p.topic,"HATIRLATMA","İlaçlarını kontrol eder misin?")}){Text("Hatırlat")}}}}
        item{HorizontalDivider();Text("Kişi ekle",fontWeight=FontWeight.Bold);OutlinedTextField(name,{name=it},label={Text("Ad")});OutlinedTextField(topic,{topic=it},label={Text("ntfy eşleştirme kodu")});Button(enabled=name.isNotBlank()&&topic.isNotBlank(),onClick={save(people+Person(UUID.randomUUID().toString(),name.trim(),topic.trim()));name="";topic=""}){Text("Ekle")};Text("Benim eşleştirme kodum: ${Store.topic(c)}",style=MaterialTheme.typography.bodySmall)}
    }
}

@Composable fun HistoryScreen(){
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)){
        item{Text("Geçmiş",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));Text("İçildi, ertelendi, içilmedi, uzaktan hatırlatma ve program değişiklikleri burada kronolojik gösterilecek.")}
    }
}
