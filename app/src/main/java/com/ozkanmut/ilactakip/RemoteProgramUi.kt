package com.ozkanmut.ilactakip

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.util.UUID

object RemoteCapabilityStore {
    private const val PREFS = "dosefolk_remote_capabilities"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(ownerTopic: String, permission: CirclePermission) = "$ownerTopic|${permission.name}"
    fun allowedByOwner(c: Context, ownerTopic: String, permission: CirclePermission): Boolean = prefs(c).getBoolean(key(ownerTopic, permission), false)
    fun applyEvent(c: Context, event: DoseEvent) {
        val permission = when { event.type.startsWith("capability_edit_program_") -> CirclePermission.EDIT_PROGRAM; event.type.startsWith("capability_edit_stock_") -> CirclePermission.EDIT_STOCK; else -> return }
        val owner = event.ownerId.ifBlank { event.actorTopic }; if (owner.isBlank()) return
        prefs(c).edit().putBoolean(key(owner, permission), event.type.endsWith("_granted")).commit()
    }
}

object CapabilitySync {
    fun publish(c: Context, targetTopic: String, permission: CirclePermission, allowed: Boolean) {
        if (targetTopic.isBlank() || targetTopic == Store.topic(c)) return
        val type = when(permission) { CirclePermission.EDIT_PROGRAM -> "capability_edit_program_${if(allowed)"granted" else "revoked"}"; CirclePermission.EDIT_STOCK -> "capability_edit_stock_${if(allowed)"granted" else "revoked"}"; else -> return }
        val event = DoseEvent(
            eventId = UUID.randomUUID().toString(),
            type = type,
            time = permission.name,
            actor = Store.myName(c),
            actorTopic = Store.topic(c),
            timestamp = System.currentTimeMillis(),
            medications = emptyList(),
            syncState = "synced",
            revision = EventStore.nextRevision(c),
            ownerId = Store.topic(c),
            targetTopic = targetTopic
        )
        EventStore.append(c,event)
        AlertOutbox.enqueue(c.applicationContext,CircleTransport.publishTopic(c),"Dosefolk sync",EventStore.payload(event).toString())
    }
}

object ScopedNtfy {
    fun sendProgramChange(c: Context, ownerTopic: String, type: String, medication: Medication) {
        if (ownerTopic.isBlank() || type !in setOf("program_added","program_updated","program_deleted")) return
        val meta = MedicationMetaStore.remote(c, ownerTopic, medication.id)
        val event = DoseEvent(
            eventId=UUID.randomUUID().toString(), type=type, time=medication.times.firstOrNull()?:"program",
            actor=Store.myName(c), actorTopic=Store.topic(c), timestamp=System.currentTimeMillis(), medications=listOf(medication),
            syncState="synced", revision=EventStore.nextRevision(c), ownerId=ownerTopic,
            medicationMeta=if(type=="program_deleted") emptyList() else listOfNotNull(meta), targetTopic=ownerTopic
        )
        EventStore.append(c,event); OwnerScopeStore.remember(c,event); OwnerScopeStore.applyRemoteProgram(c,ownerTopic,type,medication)
        AlertOutbox.enqueue(c.applicationContext,CircleTransport.publishTopic(c),"Dosefolk sync",EventStore.payload(event).toString())
    }
    fun sendRuleChange(c: Context, ownerTopic: String, medication: Medication, rule: ProgramRule) {
        if(ownerTopic.isBlank()||medication.id.isBlank()) return
        val normalized=ProgramRuleStore.normalizeForSync(rule.copy(medicationId=medication.id))
        val carrier=Medication(medication.id,medication.name,ProgramRuleStore.encode(normalized).toString(),emptyList())
        val now=System.currentTimeMillis(); val meta=MedicationMetaStore.remote(c,ownerTopic,medication.id)
        val event=DoseEvent(UUID.randomUUID().toString(),"program_rule_updated","program",Store.myName(c),Store.topic(c),now,listOf(carrier),"synced",EventStore.nextRevision(c),ownerId=ownerTopic,medicationMeta=listOfNotNull(meta),targetTopic=ownerTopic)
        EventStore.append(c,event); OwnerScopeStore.remember(c,event); OwnerScopeStore.applyRemoteRule(c,ownerTopic,normalized,now)
        AlertOutbox.enqueue(c.applicationContext,CircleTransport.publishTopic(c),"Dosefolk sync",EventStore.payload(event).toString())
    }
}

@Composable fun RemoteProgramSection(c: Context, person: Person, refreshKey: Int) {
    var expanded by remember(person.id){mutableStateOf(false)}; var editing by remember{mutableStateOf<Medication?>(null)}; var editingRule by remember{mutableStateOf<Medication?>(null)}; var adding by remember{mutableStateOf(false)}; var localRefresh by remember{mutableIntStateOf(0)}
    val meds=remember(refreshKey,localRefresh,expanded,editing,adding,person.topic){OwnerScopeStore.remoteMedications(c,person.topic)}
    val canEdit=RemoteCapabilityStore.allowedByOwner(c,person.topic,CirclePermission.EDIT_PROGRAM)
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        TextButton(onClick={expanded=!expanded}){Text(if(I18n.language()=="tr"){if(expanded)"İlaç programını gizle" else "İlaç programı (${meds.size})"}else{if(expanded)"Hide medication program" else "Medication program (${meds.size})"})}
        if(expanded){
            if(meds.isEmpty()) Text(if(I18n.language()=="tr")"Henüz bu kişiden program verisi gelmedi." else "No program data received from this person yet.",style=MaterialTheme.typography.bodySmall)
            meds.forEach{med->
                val rule=OwnerScopeStore.remoteRule(c,person.topic,med.id)
                val meta=MedicationMetaStore.remote(c,person.topic,med.id)
                val stock=remember(refreshKey,localRefresh,person.topic,med.id){StockEngine.remoteForMedication(c,person.topic,med.id)}
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text(med.name,fontWeight=FontWeight.Bold)
                        if(med.dose.isNotBlank())Text(med.dose)
                        meta?.doseLabel()?.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                        Text(med.times.joinToString(" • ").ifBlank{if(I18n.language()=="tr")"Saat yok" else "No fixed time"})
                        ProgramRuleStore.describeRule(rule).takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                        stock?.let{s->
                            Text(
                                if(I18n.language()=="tr") "Stok: ${s.remainingDoses} / kutu ${s.packSize} doz"
                                else "Stock: ${s.remainingDoses} / pack ${s.packSize} doses",
                                style=MaterialTheme.typography.bodySmall,
                                fontWeight=if(s.remainingDoses<=s.lowThreshold) FontWeight.Bold else FontWeight.Normal
                            )
                            if(s.remainingDoses<=s.lowThreshold) Text(if(I18n.language()=="tr")"⚠ Düşük stok" else "⚠ Low stock",fontWeight=FontWeight.Bold)
                        }
                        if(canEdit) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            TextButton(onClick={editing=med}){Text(if(I18n.language()=="tr")"Düzenle" else "Edit")}
                            TextButton(onClick={editingRule=med}){Text(if(I18n.language()=="tr")"Kural" else "Rule")}
                            TextButton(onClick={ScopedNtfy.sendProgramChange(c,person.topic,"program_deleted",med);localRefresh++}){Text(if(I18n.language()=="tr")"Sil" else "Delete")}
                        }
                    }
                }
            }
            if(canEdit) OutlinedButton(onClick={adding=true},modifier=Modifier.fillMaxWidth()){Text(if(I18n.language()=="tr")"Bu kişiye ilaç ekle" else "Add medication for this person")} else Text(if(I18n.language()=="tr")"Program düzenleme için bu kişinin sana yetki vermesi gerekir." else "This person must grant you program-edit permission before you can change it.",style=MaterialTheme.typography.bodySmall)
        }
    }
    editing?.let{med->RemoteMedicationDialog(c,med,{editing=null}){updated->ScopedNtfy.sendProgramChange(c,person.topic,"program_updated",updated);editing=null;localRefresh++}}
    editingRule?.let{med->val initial=OwnerScopeStore.remoteRule(c,person.topic,med.id);RemoteRuleDialog(initial,{editingRule=null}){updated->ScopedNtfy.sendRuleChange(c,person.topic,med,updated);editingRule=null;localRefresh++}}
    if(adding) RemoteMedicationDialog(c,Medication(UUID.randomUUID().toString(),"","",emptyList()),{adding=false}){added->ScopedNtfy.sendProgramChange(c,person.topic,"program_added",added);adding=false;localRefresh++}
}

@Composable private fun RemoteMedicationDialog(c: Context, initial: Medication, onDismiss:()->Unit, onSave:(Medication)->Unit){
    var name by remember(initial.id){mutableStateOf(initial.name)};var dose by remember(initial.id){mutableStateOf(initial.dose)};var times by remember(initial.id){mutableStateOf(initial.times)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(I18n.language()=="tr")"İlaç programı" else "Medication program")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},label={Text(if(I18n.language()=="tr")"İlaç" else "Medication")});OutlinedTextField(dose,{dose=it},label={Text(if(I18n.language()=="tr")"Doz notu" else "Dose note")});times.forEach{time->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(time);TextButton(onClick={times=times.filterNot{it==time}}){Text("×")}}};OutlinedButton(onClick={val now=LocalTime.now();TimePickerDialog(c,{_,h,m->times=(times+String.format("%02d:%02d",h,m)).distinct().sorted()},now.hour,now.minute,true).show()}){Text(if(I18n.language()=="tr")"Saat ekle" else "Add time")}}},confirmButton={Button(enabled=name.isNotBlank(),onClick={onSave(initial.copy(name=name.trim(),dose=dose.trim(),times=times))}){Text(if(I18n.language()=="tr")"Kaydet" else "Save")}},dismissButton={TextButton(onClick=onDismiss){Text(if(I18n.language()=="tr")"İptal" else "Cancel")}})
}

@Composable private fun RemoteRuleDialog(initial:ProgramRule,onDismiss:()->Unit,onSave:(ProgramRule)->Unit){
    var weekdays by remember(initial.medicationId){mutableStateOf(initial.weekdays)};var everyN by remember(initial.medicationId){mutableStateOf(initial.everyNDays.toString())};var start by remember(initial.medicationId){mutableStateOf(initial.startDate.orEmpty())};var end by remember(initial.medicationId){mutableStateOf(initial.endDate.orEmpty())};var anchor by remember(initial.medicationId){mutableStateOf(initial.anchorDate.orEmpty())};var routine by remember(initial.medicationId){mutableStateOf(initial.routineLabel)};val labels=if(I18n.language()=="tr")listOf("Pzt","Sal","Çar","Per","Cum","Cmt","Paz")else listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(I18n.language()=="tr")"Program kuralı" else "Program rule")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(if(I18n.language()=="tr")"Günler (hiçbiri = her gün)" else "Days (none = every day)");Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){(1..4).forEach{d->FilterChip(d in weekdays,{weekdays=if(d in weekdays)weekdays-d else weekdays+d},{Text(labels[d-1])})}};Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){(5..7).forEach{d->FilterChip(d in weekdays,{weekdays=if(d in weekdays)weekdays-d else weekdays+d},{Text(labels[d-1])})}};OutlinedTextField(everyN,{everyN=it.filter(Char::isDigit)},label={Text(if(I18n.language()=="tr")"Kaç günde bir" else "Every N days")});OutlinedTextField(start,{start=it},label={Text(if(I18n.language()=="tr")"Başlangıç YYYY-AA-GG" else "Start YYYY-MM-DD")});OutlinedTextField(end,{end=it},label={Text(if(I18n.language()=="tr")"Bitiş YYYY-AA-GG" else "End YYYY-MM-DD")});OutlinedTextField(anchor,{anchor=it},label={Text(if(I18n.language()=="tr")"Referans tarih YYYY-AA-GG" else "Anchor date YYYY-MM-DD")});OutlinedTextField(routine,{routine=it},label={Text(if(I18n.language()=="tr")"Rutin etiketi" else "Routine label")})}},confirmButton={Button(onClick={onSave(initial.copy(weekdays=weekdays,everyNDays=(everyN.toIntOrNull()?:1).coerceAtLeast(1),startDate=start.trim().takeIf{it.isNotBlank()},endDate=end.trim().takeIf{it.isNotBlank()},anchorDate=anchor.trim().takeIf{it.isNotBlank()},routineLabel=routine.trim()))}){Text(if(I18n.language()=="tr")"Kaydet" else "Save")}},dismissButton={TextButton(onClick=onDismiss){Text(if(I18n.language()=="tr")"İptal" else "Cancel")}})
}
