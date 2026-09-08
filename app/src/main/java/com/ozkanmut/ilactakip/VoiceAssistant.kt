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
import java.time.LocalTime
import java.util.Locale

private data class AssistantMessage(val fromUser: Boolean, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAssistantScreen(context: Context) {
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf(AssistantMessage(false, I18n.t("ai_ready")))) }
    var showImport by remember { mutableStateOf(false) }
    var showProgram by remember { mutableStateOf(false) }

    fun submit(text: String) {
        val query = text.trim()
        if (query.isBlank()) return
        messages = messages + AssistantMessage(true, query)
        messages = messages + AssistantMessage(false, AssistantLocalRouter.execute(context, query))
        input = ""
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank()) submit(heard)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(I18n.t("ai_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(I18n.t("ai_help"))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showImport = true }, modifier = Modifier.weight(1f)) { Text(if (I18n.language() == "tr") "İçe aktar" else "Import") }
            OutlinedButton(onClick = { showProgram = true }, modifier = Modifier.weight(1f)) { Text(if (I18n.language() == "tr") "Program" else "Schedule") }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { message ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(message.text, Modifier.padding(14.dp))
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text(I18n.t("ai_example")) })
            Button(onClick = { submit(input) }, enabled = input.isNotBlank()) { Text(I18n.t("send")) }
        }

        Button(onClick = {
            speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, I18n.speechLocale())
                putExtra(RecognizerIntent.EXTRA_PROMPT, I18n.t("speech_prompt"))
            })
        }, modifier = Modifier.fillMaxWidth()) { Text(I18n.t("speak")) }
    }

    if (showImport) {
        ModalBottomSheet(onDismissRequest = { showImport = false }) {
            SmartImportScreen(context) { added ->
                showImport = false
                messages = messages + AssistantMessage(false, if (I18n.language() == "tr") "${added.size} ilaç onaylanıp programa eklendi." else "${added.size} medication(s) were confirmed and added.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showProgram) {
        ModalBottomSheet(onDismissRequest = { showProgram = false }) {
            ProgramRulesScreen(context, Store.load(context))
            Spacer(Modifier.height(24.dp))
        }
    }
}

object AssistantLocalRouter {
    fun execute(c: Context, raw: String): String {
        NaturalActionRouter.handle(c, raw)?.let { return it }
        val q = raw.lowercase(Locale.getDefault())
        val meds = Store.load(c)
        val groups = meds.flatMap { med -> med.times.map { it to med } }.groupBy({ it.first }, { it.second }).toSortedMap()

        val todayWords = listOf("bugün", "today", "heute", "aujourd", "hoy", "oggi", "hoje", "сегодня", "сьогодні", "اليوم", "امروز", "היום", "σήμερα", "astăzi", "idag", "i dag", "tänään", "dnes", "ma", "hari ini", "hôm nay", "आज", "今日", "오늘", "今天")
        if (todayWords.any { q.contains(it) }) {
            return if (groups.isEmpty()) I18n.t("all_good") else groups.entries.joinToString("\n") { (time, list) -> "$time: ${list.joinToString { it.name }}" }
        }

        val nextWords = listOf("sıradaki", "sonraki", "next", "nächste", "prochain", "siguiente", "prossim", "próxim", "volgende", "następ", "следующ", "наступн", "التالي", "بعدی", "הבא", "επόμεν", "următor", "nästa", "næste", "neste", "seuraava", "další", "következő", "berikut", "tiếp theo", "अगली", "次", "다음", "下一个")
        if (nextWords.any { q.contains(it) }) {
            val now = LocalTime.now()
            val next = groups.entries.firstOrNull { runCatching { LocalTime.parse(it.key) }.getOrNull()?.isAfter(now) == true } ?: groups.entries.firstOrNull()
            return next?.let { "${it.key}: ${it.value.joinToString { med -> med.name }}" } ?: I18n.t("all_good")
        }

        val timeRegex = Regex("(?:[01]?\\d|2[0-3])[:.]?[0-5]\\d")
        val mentioned = timeRegex.find(q)?.value?.replace('.', ':')?.let { value -> if (value.contains(':')) value.padStart(5, '0') else value.padStart(4, '0').let { "${it.substring(0, 2)}:${it.substring(2)}" } }
        val target = if (mentioned != null) groups[mentioned]?.let { mentioned to it } else groups.entries.firstOrNull()?.let { it.key to it.value }

        val snoozeWords = listOf("ertele", "snooze", "delay", "verschieb", "report", "pospon", "posticip", "adiar", "отлож", "відклад", "تأجيل", "تعویق", "דחה", "αναβολ", "amân", "skjut", "udsæt", "utsett", "siirrä", "odlož", "halaszt", "tunda", "hoãn", "स्थगित", "延期", "미루")
        if (snoozeWords.any { q.contains(it) }) {
            if (target == null) return I18n.t("all_good")
            val minutes = Regex("(\\d+)\\s*(dk|dakika|min|minute|minuto|minuten)").find(q)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 30
            AlarmScheduler.snoozeGroup(c, target.first, target.second, minutes)
            Ntfy.sendEvent(c, "snoozed", target.first, target.second)
            return "${target.first} • $minutes min"
        }

        val takenWords = listOf("içtim", "içildi", "aldım", "taken", "took", "genommen", "pris", "tomado", "assunto", "ingenomen", "przyję", "принял", "прийня", "تم التناول", "مصرف", "נלקח", "ελήφθη", "luat", "tagen", "taget", "tatt", "otettu", "užito", "bevéve", "diminum", "đã uống", "ले लिया", "服用", "복용", "已服")
        if (takenWords.any { q.contains(it) }) {
            if (target == null) return I18n.t("all_good")
            Ntfy.sendEvent(c, "taken", target.first, target.second)
            return "${target.first} • ${I18n.t("taken")}"
        }

        val missedWords = listOf("içilmedi", "almadım", "kaçırdım", "not taken", "missed", "nicht genommen", "non pris", "no tomado", "não tomado", "niet ingenomen", "nie przyję", "не принял", "не прийня", "لم يتم", "مصرف نشد", "לא נלקח", "δεν ελήφθη", "neluat", "inte tagen", "ikke taget", "ikke tatt", "ei otettu", "neužito", "nem vette", "belum diminum", "chưa uống", "नहीं लिया", "未服用", "복용 안")
        if (missedWords.any { q.contains(it) }) {
            if (target == null) return I18n.t("all_good")
            Ntfy.sendEvent(c, "missed", target.first, target.second)
            return "${target.first} • ${I18n.t("missed")}"
        }

        return if (I18n.language() == "tr") "Bu komut henüz yerel olarak desteklenmiyor." else "This command is not supported locally yet."
    }
}
