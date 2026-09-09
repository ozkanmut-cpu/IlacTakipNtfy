package com.ozkanmut.ilactakip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class MedicationStock(val medicationId:String,val medicationName:String,val remainingDoses:Int,val packSize:Int,val lowThreshold:Int=5,val updatedAt:Long=System.currentTimeMillis())

object StockEngine {
    private const val PREFS="dosefolk_stock"; private const val KEY_STOCK="stock"; private const val KEY_PROCESSED="processed_events"; private const val KEY_REMOTE="remote_stock"
    private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    fun all(c:Context):List<MedicationStock> = load(c)
    fun forMedication(c:Context,medicationId:String)=load(c).firstOrNull{it.medicationId==medicationId}
    fun remoteAll(c:Context,ownerId:String):List<MedicationStock> = loadRemote(c).filter{it.first==ownerId}.map{it.second}
    fun remoteForMedication(c:Context,ownerId:String,medicationId:String)=remoteAll(c,ownerId).firstOrNull{it.medicationId==medicationId}

    @Synchronized fun configure(c:Context,medication:Medication,packSize:Int,currentDoses:Int=packSize,lowThreshold:Int=5){if(packSize<=0)return;val s=MedicationStock(medication.id,medication.name,currentDoses.coerceAtLeast(0),packSize,lowThreshold.coerceAtLeast(0));save(c,listOf(s)+load(c).filterNot{it.medicationId==medication.id});LowStockNotifier.evaluate(c,s);StockSync.publishToCircle(c,s)}
    @Synchronized fun openNewBox(c:Context,medicationId:String):MedicationStock?{val x=forMedication(c,medicationId)?:return null;val u=x.copy(remainingDoses=x.remainingDoses+x.packSize,updatedAt=System.currentTimeMillis());save(c,listOf(u)+load(c).filterNot{it.medicationId==medicationId});LowStockNotifier.evaluate(c,u);StockSync.publishToCircle(c,u);return u}
    @Synchronized fun addSupply(c:Context,medication:Medication,units:Int,lowThreshold:Int=5):MedicationStock?{
        if(units<=0)return null
        val current=forMedication(c,medication.id)
        val updated=if(current==null) MedicationStock(medication.id,medication.name,units,units,lowThreshold.coerceAtLeast(0))
        else current.copy(remainingDoses=current.remainingDoses+units,updatedAt=System.currentTimeMillis())
        save(c,listOf(updated)+load(c).filterNot{it.medicationId==medication.id})
        LowStockNotifier.evaluate(c,updated)
        StockSync.publishToCircle(c,updated)
        return updated
    }
    fun lowStock(c:Context)=load(c).filter{it.remainingDoses<=it.lowThreshold}

    fun toJson(s:MedicationStock)=JSONObject().put("medicationId",s.medicationId).put("medicationName",s.medicationName).put("remainingDoses",s.remainingDoses).put("packSize",s.packSize).put("lowThreshold",s.lowThreshold).put("updatedAt",s.updatedAt)
    fun fromJson(o:JSONObject?):MedicationStock?{if(o==null)return null;val id=o.optString("medicationId");if(id.isBlank())return null;return MedicationStock(id,o.optString("medicationName"),o.optInt("remainingDoses").coerceAtLeast(0),o.optInt("packSize").coerceAtLeast(0),o.optInt("lowThreshold",5).coerceAtLeast(0),o.optLong("updatedAt"))}

    @Synchronized fun applyRemoteSnapshot(c:Context,ownerId:String,stock:MedicationStock){if(ownerId.isBlank()||ownerId==OwnerScopeStore.localOwnerId(c))return;saveRemoteSnapshot(c,ownerId,stock)}
    @Synchronized fun saveRemoteSnapshot(c:Context,ownerId:String,stock:MedicationStock){if(ownerId.isBlank())return;val rows=loadRemote(c).toMutableList();val i=rows.indexOfFirst{it.first==ownerId&&it.second.medicationId==stock.medicationId};if(i>=0){if(rows[i].second.updatedAt>stock.updatedAt)return;rows[i]=ownerId to stock}else rows.add(ownerId to stock);saveRemote(c,rows)}
    @Synchronized fun clearRemoteOwner(c:Context,ownerId:String){saveRemote(c,loadRemote(c).filterNot{it.first==ownerId})}

    private fun consumptionUnits(c:Context,id:String):Int{val m=MedicationMetaStore.get(c,id)?:return 1;val countable=m.form in setOf(MedicationForm.TABLET,MedicationForm.INSULIN,MedicationForm.NEBULE,MedicationForm.INHALER,MedicationForm.DROP,MedicationForm.PATCH);return if(countable)(m.quantity?:1.0).roundToInt().coerceAtLeast(1) else 1}
    @Synchronized fun applyEvent(c:Context,event:DoseEvent){
        if(event.type !in setOf("taken","prn_taken","undo_taken")||alreadyProcessed(c,event.eventId))return
        val current=load(c).associateBy{it.medicationId}.toMutableMap();val changed=mutableListOf<MedicationStock>();val restore=event.type=="undo_taken"
        event.medications.distinctBy{it.id}.forEach{med->val s=current[med.id]?:return@forEach;val units=consumptionUnits(c,med.id);val remaining=if(restore)s.remainingDoses+units else (s.remainingDoses-units).coerceAtLeast(0);val u=s.copy(remainingDoses=remaining,updatedAt=event.timestamp);current[med.id]=u;changed+=u}
        if(changed.isNotEmpty()){save(c,current.values.toList());changed.forEach{LowStockNotifier.evaluate(c,it);StockSync.publishToCircle(c,it)}};markProcessed(c,event.eventId)
    }
    private fun alreadyProcessed(c:Context,id:String)=processed(c).contains(id)
    private fun processed(c:Context):Set<String>{val raw=prefs(c).getString(KEY_PROCESSED,"[]")?:"[]";return runCatching{val a=JSONArray(raw);(0 until a.length()).map{a.optString(it)}.filter{it.isNotBlank()}.toSet()}.getOrDefault(emptySet())}
    private fun markProcessed(c:Context,id:String){prefs(c).edit().putString(KEY_PROCESSED,JSONArray((listOf(id)+processed(c)).distinct().take(2000)).toString()).apply()}
    private fun load(c:Context):List<MedicationStock>{val raw=prefs(c).getString(KEY_STOCK,"[]")?:"[]";return runCatching{val a=JSONArray(raw);(0 until a.length()).mapNotNull{fromJson(a.optJSONObject(it))}}.getOrDefault(emptyList())}
    private fun save(c:Context,v:List<MedicationStock>){val a=JSONArray();v.forEach{a.put(toJson(it))};prefs(c).edit().putString(KEY_STOCK,a.toString()).apply()}
    private fun loadRemote(c:Context):List<Pair<String,MedicationStock>>{val raw=prefs(c).getString(KEY_REMOTE,"[]")?:"[]";return runCatching{val a=JSONArray(raw);(0 until a.length()).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null;val owner=o.optString("ownerId");val s=fromJson(o.optJSONObject("stock"));if(owner.isBlank()||s==null)null else owner to s}}.getOrDefault(emptyList())}
    private fun saveRemote(c:Context,v:List<Pair<String,MedicationStock>>){val a=JSONArray();v.forEach{(owner,s)->a.put(JSONObject().put("ownerId",owner).put("stock",toJson(s)))};prefs(c).edit().putString(KEY_REMOTE,a.toString()).commit()}
}
