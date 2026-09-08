package com.ozkanmut.ilactakip

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

private data class AssistantMessage(val fromUser:Boolean,val text:String)

@Composable
fun VoiceAssistantScreen(context: Context) {
    var input by remember { mutableStateOf("") }
    var messages by remember {
        mutableStateOf(listOf(AssistantMessage(false, "Hazırım. İlaç programını konuşarak yönetebilirsin.")))
    }

    fun submit(text:String){
        val q=text.trim()
        if(q.isBlank()) return
        messages = messages + AssistantMessage(true,q)
        val reply = AssistantLocalRouter.execute(context,q)
        messages = messages + AssistantMessage(false,reply)
        input=""
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if(!heard.isNullOrBlank()) submit(heard)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI Asistan", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
        Text("Konuş veya yaz. Basit komutlar cihazda işlenir; gelişmiş doğal dil komutları ChatGPT kontrol katmanına yönlendirilecek.", style=MaterialTheme.typography.bodyMedium)

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(messages) { m ->
                Card(
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(18.dp),
                    colors=CardDefaults.cardColors(containerColor=if(m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                ) { Text(m.text, Modifier.padding(14.dp)) }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.weight(1f),placeholder={Text("Örn. 20:00 ilaçlarını 30 dk ertele")},singleLine=false)
            Button(onClick={submit(input)},enabled=input.isNotBlank()) { Text("Gönder") }
        }
        Button(
            onClick={
                val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE,"tr-TR")
                    putExtra(RecognizerIntent.EXTRA_PROMPT,"İlaç Takip'e söyle")
                }
                speechLauncher.launch(i)
            },
            modifier=Modifier.fillMaxWidth()
        ) { Text("🎙 Konuş") }
    }
}

object AssistantLocalRouter {
    fun execute(c: Context, raw:String):String {
        val q=raw.lowercase(Locale("tr","TR"))
        val meds=Store.load(c)
        val groups=meds.flatMap { m -> m.times.map { it to m } }.groupBy({it.first},{it.second}).toSortedMap()

        if(q.contains("bugün") && (q.contains("ne") || q.contains("göster") || q.contains("ilaç"))){
            if(groups.isEmpty()) return "Bugün için kayıtlı ilaç saati yok."
            return groups.entries.joinToString("\n") { (t,list) -> "$t: ${list.joinToString { it.name }}" }
        }
        if(q.contains("sıradaki") || q.contains("sonraki")){
            val now=java.time.LocalTime.now()
            val next=groups.entries.firstOrNull { runCatching{java.time.LocalTime.parse(it.key)}.getOrNull()?.isAfter(now)==true } ?: groups.entries.firstOrNull()
            return next?.let{"Sıradaki grup ${it.key}: ${it.value.joinToString { m->m.name }}"} ?: "Planlanmış ilaç yok."
        }

        val timeRegex=Regex("(?:[01]?\\d|2[0-3])[:.]?[0-5]\\d")
        val mentioned=timeRegex.find(q)?.value?.replace('.',':')?.let{v->if(v.contains(':')) v.padStart(5,'0') else v.padStart(4,'0').let{"${it.substring(0,2)}:${it.substring(2)}"}}
        val target = if(mentioned!=null) groups[mentioned]?.let{mentioned to it} else groups.entries.firstOrNull()?.let{it.key to it.value}

        if(q.contains("ertele")){
            if(target==null) return "Ertelenecek ilaç grubu bulamadım."
            val minutes=Regex("(\\d+)\\s*(dk|dakika)").find(q)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 30
            AlarmScheduler.snoozeGroup(c,target.first,target.second,minutes)
            Ntfy.sendEvent(c,"snoozed",target.first,target.second)
            return "${target.first} grubu $minutes dakika ertelendi."
        }
        if(q.contains("içtim") || q.contains("içildi") || q.contains("aldım")){
            if(target==null) return "İçildi olarak işaretlenecek ilaç grubu bulamadım."
            Ntfy.sendEvent(c,"taken",target.first,target.second)
            return "${target.first} grubu içildi olarak işaretlendi."
        }
        if(q.contains("içilmedi") || q.contains("almadım") || q.contains("kaçırdım")){
            if(target==null) return "İçilmedi olarak işaretlenecek ilaç grubu bulamadım."
            Ntfy.sendEvent(c,"missed",target.first,target.second)
            return "${target.first} grubu içilmedi olarak işaretlendi."
        }
        if(q.contains("hatırlat")){
            val person=Store.people(c).firstOrNull { q.contains(it.name.lowercase(Locale("tr","TR"))) }
            if(person!=null){Ntfy.sendTo(person.topic,"HATIRLATMA","${Store.myName(c)} ilaçlarını hatırlatıyor");return "${person.name} kişisine hatırlatma gönderildi."}
            return "Kime hatırlatma göndereceğini anlayamadım."
        }

        return "Bu komutu doğal dil AI katmanına aktarmak için hazır. ChatGPT bağlantısı devreye girdiğinde ilaç ekleme/düzenleme, stok, takipçi, içe aktarma, rapor ve diğer tüm işlemler buradan yapılacak."
    }
}
