package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class MedicationStock(val medicationId:String,val medicationName:String,val remainingDoses:Int,val packSize:Int,val lowThreshold:Int=5,val updatedAt:Long=System.currentTimeMillis())

object StockEngine {
    private const val PREFS="dosefolk_stock"; private const val KEY_STOCK="stock"; private const val KEY_PROCESSED="processed_events"; private const val KEY_CONSUMED="consumed_sessions"; private const val KEY_RESTORED="restored_sessions"; private const val KEY_REMOTE="remote_stock"
    private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private fun remoteRevisionKey(ownerId:String,medicationId:String)="remote_rev|$ownerId|$medicationId"
    private fun remoteActorKey(ownerId:String,medicationId:String)="remote_actor|$ownerId|$medicationId"
    private fun remoteEventKey(ownerId:String,medicationId:String)="remote_event|$ownerId|$medicationId"
    fun all(c:Context):List<MedicationStock> = load(c)
    fun forMedication(c:Context,medicationId:String)=load(c).firstOrNull{it.medicationId==medicationId}
    fun remoteAll(c:Context,ownerId:String):List<MedicationStock> = loadRemote(c).filter{it.first==ownerId}.map{it.second}
    fun remoteForMedication(c:Context,ownerId:String,medicationId:String)=remoteAll(c,ownerId).firstOrNull{it.medicationId==medicationId}

    @Synchronized fun configure(c:Context,medication:Medication,packSize:Int,currentDoses:Int=packSize,lowThreshold:Int=5){
        if(packSize<=0)return
        val s=MedicationStock(medication.id,medication.name,currentDoses.coerceAtLeast(0),packSize,lowThreshold.coerceAtLeast(0))
        if(!save(c,listOf(s)+load(c).filterNot{it.medicationId==medication.id}))return
        LowStockNotifier.evaluate(c,s);StockSync.publishToCircle(c,s)
    }
    @Synchronized fun openNewBox(c:Context,medicationId:String):MedicationStock?{
        val x=forMedication(c,medicationId)?:return null
        val u=x.copy(remainingDoses=x.remainingDoses+x.packSize,updatedAt=System.currentTimeMillis())
        if(!save(c,listOf(u)+load(c).filterNot{it.medicationId==medicationId}))return null
        LowStockNotifier.evaluate(c,u);StockSync.publishToCircle(c,u);return u
    }
    @Synchronized fun addSupply(c:Context,medication:Medication,units:Int,lowThreshold:Int=5):MedicationStock?{
        if(units<=0)return null
        val current=forMedication(c,medication.id)
        val updated=if(current==null) MedicationStock(medication.id,medication.name,units,units,lowThreshold.coerceAtLeast(0))
        else current.copy(remainingDoses=current.remainingDoses+units,updatedAt=System.currentTimeMillis())
        if(!save(c,listOf(updated)+load(c).filterNot{it.medicationId==medication.id}))return null
        LowStockNotifier.evaluate(c,updated)
        StockSync.publishToCircle(c,updated)
        return updated
    }
    fun lowStock(c:Context)=load(c).filter{it.remainingDoses<=it.lowThreshold}

    fun toJson(s:MedicationStock)=JSONObject().put("medicationId",s.medicationId).put("medicationName",s.medicationName).put("remainingDoses",s.remainingDoses).put("packSize",s.packSize).put("lowThreshold",s.lowThreshold).put("updatedAt",s.updatedAt)
    fun fromJson(o:JSONObject?):MedicationStock?{if(o==null)return null;val id=o.optString("medicationId");if(id.isBlank())return null;return MedicationStock(id,o.optString("medicationName"),o.optInt("remainingDoses").coerceAtLeast(0),o.optInt("packSize").coerceAtLeast(0),o.optInt("lowThreshold",5).coerceAtLeast(0),o.optLong("updatedAt"))}

    @Synchronized fun applyRemoteSnapshot(c:Context,ownerId:String,stock:MedicationStock,revision:Long=0L,actorTopic:String=ownerId,eventId:String=""){
        if(ownerId.isBlank()||ownerId==OwnerScopeStore.localOwnerId(c))return
        val p=prefs(c)
        val medId=stock.medicationId
        val storedRevision=p.getLong(remoteRevisionKey(ownerId,medId),0L)
        val storedActor=p.getString(remoteActorKey(ownerId,medId),"").orEmpty()
        val storedEvent=p.getString(remoteEventKey(ownerId,medId),"").orEmpty()
        val current=loadRemote(c).firstOrNull{it.first==ownerId&&it.second.medicationId==medId}?.second
        val accept=if(revision>0L||storedRevision>0L){
            when{
                revision!=storedRevision -> revision>storedRevision
                actorTopic!=storedActor -> actorTopic>storedActor
                eventId.isNotBlank()||storedEvent.isNotBlank() -> eventId>storedEvent
                else -> false
            }
        } else current==null || stock.updatedAt>=current.updatedAt
        if(!accept)return
        writeRemoteSnapshot(c,ownerId,stock)
        p.edit().putLong(remoteRevisionKey(ownerId,medId),revision).putString(remoteActorKey(ownerId,medId),actorTopic).putString(remoteEventKey(ownerId,medId),eventId).commit()
    }
    @Synchronized fun saveRemoteSnapshot(c:Context,ownerId:String,stock:MedicationStock){
        if(ownerId.isBlank())return
        val current=loadRemote(c).firstOrNull{it.first==ownerId&&it.second.medicationId==stock.medicationId}?.second
        if(current!=null&&current.updatedAt>stock.updatedAt)return
        writeRemoteSnapshot(c,ownerId,stock)
    }
    private fun writeRemoteSnapshot(c:Context,ownerId:String,stock:MedicationStock){
        val rows=loadRemote(c).toMutableList(); val i=rows.indexOfFirst{it.first==ownerId&&it.second.medicationId==stock.medicationId}
        if(i>=0)rows[i]=ownerId to stock else rows.add(ownerId to stock); saveRemote(c,rows)
    }
    @Synchronized fun clearRemoteOwner(c:Context,ownerId:String){
        saveRemote(c,loadRemote(c).filterNot{it.first==ownerId})
        val p=prefs(c); val edit=p.edit(); p.all.keys.filter{it.startsWith("remote_rev|$ownerId|")||it.startsWith("remote_actor|$ownerId|")||it.startsWith("remote_event|$ownerId|")}.forEach(edit::remove); edit.commit()
    }

    private fun consumptionUnits(c:Context,id:String):Int{val m=MedicationMetaStore.get(c,id)?:return 1;val countable=m.form in setOf(MedicationForm.TABLET,MedicationForm.INSULIN,MedicationForm.NEBULE,MedicationForm.INHALER,MedicationForm.DROP,MedicationForm.PATCH);return if(countable)(m.quantity?:1.0).roundToInt().coerceAtLeast(1) else 1}
    private fun consumptionKey(event:DoseEvent,medicationId:String):String = if(event.type=="prn_taken") "prn|${event.eventId}|$medicationId" else "dose|${event.scheduledDate}|${event.time}|$medicationId"
    private fun compactLedger(values:Set<String>):List<String>{
        // These are semantic facts, not merely replay receipts. Forgetting an old regular
        // dose session would allow a sufficiently old replay to consume or restore stock
        // a second time after bounded receipt/processed-event windows roll over.
        // Keep both regular dose-session and PRN event identity keys durably.
        return values.toList()
    }

    @Synchronized fun applyEvent(c:Context,event:DoseEvent){
        val stockTypes=setOf("taken","prn_taken","undo_taken","conflict_resolved_taken","conflict_resolved_missed")
        if(event.type !in stockTypes||alreadyProcessed(c,event.eventId))return
        val ownerId=event.ownerId.ifBlank{event.actorTopic}; if(ownerId.isNotBlank()&&ownerId!=OwnerScopeStore.localOwnerId(c))return
        val current=load(c).associateBy{it.medicationId}.toMutableMap(); val changed=mutableListOf<MedicationStock>()
        val consumed=consumed(c).toMutableSet(); val restored=restored(c).toMutableSet()
        val shouldBeConsumed=event.type in setOf("taken","prn_taken","conflict_resolved_taken")
        event.medications.distinctBy{it.id}.forEach{med->
            val s=current[med.id]?:return@forEach; val key=consumptionKey(event,med.id)
            val isConsumed=key in consumed; val isRestored=key in restored
            if(shouldBeConsumed){
                if(isConsumed)return@forEach
                consumed.add(key); restored.remove(key)
            }else{
                if(isRestored)return@forEach
                consumed.remove(key); restored.add(key)
            }
            val units=consumptionUnits(c,med.id)
            val remaining=if(shouldBeConsumed)(s.remainingDoses-units).coerceAtLeast(0) else s.remainingDoses+units
            val u=s.copy(remainingDoses=remaining,updatedAt=event.timestamp); current[med.id]=u; changed+=u
        }
        val processedIds=(listOf(event.eventId)+processed(c)).distinct().take(2000)
        val editor=prefs(c).edit().putString(KEY_PROCESSED,JSONArray(processedIds).toString()).putString(KEY_CONSUMED,JSONArray(compactLedger(consumed)).toString()).putString(KEY_RESTORED,JSONArray(compactLedger(restored)).toString())
        if(changed.isNotEmpty())editor.putString(KEY_STOCK,stockJson(current.values.toList()).toString())
        if(!editor.commit())return
        changed.forEach{LowStockNotifier.evaluate(c,it);StockSync.publishToCircle(c,it)}
    }
    private fun alreadyProcessed(c:Context,id:String)=processed(c).contains(id)
    private fun processed(c:Context):Set<String>{val raw=prefs(c).getString(KEY_PROCESSED,"[]")?:"[]";return runCatching{val a=JSONArray(raw);(0 until a.length()).map{a.optString(it)}.filter{it.isNotBlank()}.toSet()}.getOrDefault(emptySet())}
    private fun consumed(c:Context):Set<String>{val raw=prefs(c).getString(KEY_CONSUMED,"[]")?:"[]";return runCatching{val a=JSONArray(raw);(0 until a.length()).map{a.optString(it)}.filter{it.isNotBlank()}.toSet()}.getOrDefault(emptySet())}
    private fun restored(c:Context):Set<String>{val raw=prefs(c).getString(KEY_RESTORED,"[]")?:"[]";return runCatching{val a=JSONArray(raw);(0 until a.length()).map{a.optString(it)}.filter{it.isNotBlank()}.toSet()}.getOrDefault(emptySet())}
    private fun stockJson(v:List<MedicationStock>):JSONArray{val a=JSONArray();v.forEach{a.put(toJson(it))};return a}
    private fun load(c:Context):List<MedicationStock>{val raw=prefs(c).getString(KEY_STOCK,"[]")?:"[]";return runCatching{val a=JSONArray(raw);(0 until a.length()).mapNotNull{fromJson(a.optJSONObject(it))}}.getOrDefault(emptyList())}
    private fun save(c:Context,v:List<MedicationStock>):Boolean = prefs(c).edit().putString(KEY_STOCK,stockJson(v).toString()).commit()
    private fun loadRemote(c:Context):List<Pair<String,MedicationStock>>{val raw=prefs(c).getString(KEY_REMOTE,"[]")?:"[]";return runCatching{val a=JSONArray(raw);(0 until a.length()).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null;val owner=o.optString("ownerId");val s=fromJson(o.optJSONObject("stock"));if(owner.isBlank()||s==null)null else owner to s}}.getOrDefault(emptyList())}
    private fun saveRemote(c:Context,v:List<Pair<String,MedicationStock>>){val a=JSONArray();v.forEach{(owner,s)->a.put(JSONObject().put("ownerId",owner).put("stock",toJson(s)))};prefs(c).edit().putString(KEY_REMOTE,a.toString()).commit()}
}
