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
@Composable fun VoiceAssistantScreen(context:Context){var input by remember{mutableStateOf("")};var messages by remember{mutableStateOf(listOf(AssistantMessage(false,I18n.t("ai_ready"))))};fun submit(text:String){val q=text.trim();if(q.isBlank())return;messages=messages+AssistantMessage(true,q);messages=messages+AssistantMessage(false,AssistantLocalRouter.execute(context,q));input=""};val speechLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==Activity.RESULT_OK){val heard=r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull();if(!heard.isNullOrBlank())submit(heard)}};Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(I18n.t("ai_title"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(I18n.t("ai_help"));LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){items(messages){m->Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=if(m.fromUser)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)){Text(m.text,Modifier.padding(14.dp))}}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(input,{input=it},Modifier.weight(1f),placeholder={Text(I18n.t("ai_example"))});Button(onClick={submit(input)},enabled=input.isNotBlank()){Text(I18n.t("send"))}};Button(onClick={speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,I18n.speechLocale());putExtra(RecognizerIntent.EXTRA_PROMPT,I18n.t("speech_prompt"))})},modifier=Modifier.fillMaxWidth()){Text(I18n.t("speak"))}}}

object AssistantLocalRouter{
 fun execute(c:Context,raw:String):String{val lang=I18n.language();val q=raw.lowercase(Locale.getDefault());val meds=Store.load(c);val groups=meds.flatMap{m->m.times.map{it to m}}.groupBy({it.first},{it.second}).toSortedMap();
  val todayWords=mapOf("tr" to listOf("bugün"),"en" to listOf("today"),"de" to listOf("heute"),"fr" to listOf("aujourd"),"es" to listOf("hoy"),"it" to listOf("oggi"),"pt" to listOf("hoje"),"ru" to listOf("сегодня"),"ar" to listOf("اليوم"),"ja" to listOf("今日"),"ko" to listOf("오늘"),"zh" to listOf("今天"))[lang]?:listOf("today")
  if(todayWords.any{q.contains(it)})return if(groups.isEmpty())"No medication times scheduled today." else groups.entries.joinToString("\n"){(t,l)->"$t: ${l.joinToString{it.name}}"}
  val nextWords=mapOf("tr" to listOf("sıradaki","sonraki"),"en" to listOf("next"),"de" to listOf("nächste"),"fr" to listOf("prochain"),"es" to listOf("siguiente"),"it" to listOf("prossim"),"pt" to listOf("próxim"))[lang]?:listOf("next");if(nextWords.any{q.contains(it)}){val now=java.time.LocalTime.now();val n=groups.entries.firstOrNull{runCatching{java.time.LocalTime.parse(it.key)}.getOrNull()?.isAfter(now)==true}?:groups.entries.firstOrNull();return n?.let{"${it.key}: ${it.value.joinToString{m->m.name}}"}?:"No medication scheduled."}
  val timeRegex=Regex("(?:[01]?\\d|2[0-3])[:.]?[0-5]\\d");val mentioned=timeRegex.find(q)?.value?.replace('.',':')?.let{v->if(v.contains(':'))v.padStart(5,'0')else v.padStart(4,'0').let{"${it.substring(0,2)}:${it.substring(2)}"}};val target=if(mentioned!=null)groups[mentioned]?.let{mentioned to it}else groups.entries.firstOrNull()?.let{it.key to it.value}
  val snoozeWords=listOf("ertele","snooze","delay","verschieb","report","pospon","posticip","adiar","отлож","تأجيل","延期","미루");if(snoozeWords.any{q.contains(it)}){if(target==null)return"No medication group found.";val minutes=Regex("(\\d+)\\s*(dk|dakika|min|minute|minuto|minuten)").find(q)?.groupValues?.getOrNull(1)?.toIntOrNull()?:30;AlarmScheduler.snoozeGroup(c,target.first,target.second,minutes);Ntfy.sendEvent(c,"snoozed",target.first,target.second);return"${target.first} • $minutes min"}
  val takenWords=listOf("içtim","içildi","aldım","taken","took","genommen","pris","tomado","assunto","принял","服用","복용","已服");if(takenWords.any{q.contains(it)}){if(target==null)return"No medication group found.";Ntfy.sendEvent(c,"taken",target.first,target.second);return"${target.first} • ${I18n.t("taken")}"}
  val missedWords=listOf("içilmedi","almadım","kaçırdım","not taken","missed","nicht genommen","non pris","no tomado","não tomado","не принял","未服用","복용 안");if(missedWords.any{q.contains(it)}){if(target==null)return"No medication group found.";Ntfy.sendEvent(c,"missed",target.first,target.second);return"${target.first} • ${I18n.t("missed")}"}
  return"Dosefolk AI is ready for this command; advanced control will be enabled through the ChatGPT connection."
 }
}
