package com.ozkanmut.ilactakip

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ReliabilityCoreTest {
    private lateinit var c: Context
    private val med = Medication("med-1", "Test Med", "", listOf("08:00"))
    private val today = LocalDate.now().toString()

    @Before fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("ilac_takip","dosefolk_events","dosefolk_stock","dosefolk_alarm_scheduler","dosefolk_medication_meta","dosefolk_remote_medication_meta","dosefolk_program_rules","dosefolk_owner_scope","dosefolk_low_stock_alerts","dosefolk_prescription_tracker","dosefolk_sgk_stock_import","dosefolk_refill_alerts","dosefolk_travel_guard").forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun event(id:String,type:String,timestamp:Long,meds:List<Medication> = listOf(med))=DoseEvent(id,type,"08:00","Tester",Store.topic(c),timestamp,meds,"synced",timestamp,today,0L,Store.topic(c))

    @Test fun eventStore_deduplicatesSameEventId(){ EventStore.append(c,event("same-id","taken",100L));EventStore.append(c,event("same-id","missed",200L));val events=EventStore.load(c);assertEquals(1,events.size);assertEquals("taken",events.single().type) }
    @Test fun doseState_undoTakenReturnsSessionToPending(){ Store.save(c,listOf(med));EventStore.append(c,event("taken-1","taken",100L));EventStore.append(c,event("undo-1","undo_taken",200L));val state=DoseStateEngine.stateForTime(c,"08:00",LocalDate.now());assertEquals(DoseSessionStatus.PENDING,state.status);assertEquals("undo_taken",state.latestEvent?.type) }
    @Test fun stock_takenIsIdempotentAndUndoRestoresDose(){ StockEngine.configure(c,med,10,10,2);val taken=event("taken-stock","taken",100L);StockEngine.applyEvent(c,taken);StockEngine.applyEvent(c,taken);assertEquals(9,StockEngine.forMedication(c,med.id)?.remainingDoses);StockEngine.applyEvent(c,event("undo-stock","undo_taken",200L));assertEquals(10,StockEngine.forMedication(c,med.id)?.remainingDoses) }
    @Test fun remoteStock_olderSnapshotCannotRollBackNewerState(){ StockEngine.saveRemoteSnapshot(c,"owner-2",MedicationStock(med.id,med.name,6,10,2,200L));StockEngine.saveRemoteSnapshot(c,"owner-2",MedicationStock(med.id,med.name,9,10,2,100L));val stock=StockEngine.remoteForMedication(c,"owner-2",med.id);assertNotNull(stock);assertEquals(6,stock?.remainingDoses);assertEquals(200L,stock?.updatedAt) }
    @Test fun alarmPlan_canBeRebuiltFromPersistedMedicationAfterSchedulerStateLoss(){ Store.save(c,listOf(med));c.getSharedPreferences("dosefolk_alarm_scheduler",Context.MODE_PRIVATE).edit().clear().commit();AlarmScheduler.scheduleAll(c,Store.load(c),observeProgramChanges=false);val scheduled=c.getSharedPreferences("dosefolk_alarm_scheduler",Context.MODE_PRIVATE).getStringSet("scheduled_times",emptySet()).orEmpty();assertTrue("08:00" in scheduled) }
    @Test fun timezoneChange_waitsForUserConfirmationBeforeRebuildingRegularAlarms(){ assertFalse(RecoveryPolicy.shouldRebuildRegularAlarms(Intent.ACTION_TIMEZONE_CHANGED,true));assertTrue(RecoveryPolicy.shouldRebuildRegularAlarms(Intent.ACTION_TIMEZONE_CHANGED,false));assertTrue(RecoveryPolicy.shouldRebuildRegularAlarms(Intent.ACTION_BOOT_COMPLETED,true));assertTrue(RecoveryPolicy.shouldRebuildRegularAlarms(Intent.ACTION_TIME_CHANGED,true)) }
    @Test fun expiredSnooze_recoveryNeverSchedulesInThePast(){ val now=1_000_000L;assertEquals(now+1_000L,SnoozeRecovery.recoveryTrigger(now-60_000L,now));assertEquals(now+90_000L,SnoozeRecovery.recoveryTrigger(now+90_000L,now)) }
    @Test fun prescriptionLifecycle_newestCycleSupersedesOlderFill(){ val old=PrescriptionRecord(id="old",medicationName="Vasoxen 5 mg 28 tablet",fillDate="01.06.2026",doseEndDate="01.09.2026",continuous=true);val newer=PrescriptionRecord(id="new",medicationName="VASOXEN 5 MG 28 FILM TABLET",fillDate="01.08.2026",doseEndDate="01.11.2026",continuous=true);val current=PrescriptionLifecycle.current(listOf(old,newer));assertEquals(1,current.size);assertEquals("new",current.single().id) }
    @Test fun prescriptionLifecycle_dueDoesNotSurfaceSupersededOldCycle(){ val old=PrescriptionRecord(id="old",medicationName="Vasoxen 5 mg 28 tablet",fillDate="01.05.2026",doseEndDate="01.06.2026",continuous=true);val newer=PrescriptionRecord(id="new",medicationName="VASOXEN 5 MG 28 FILM TABLET",fillDate="01.08.2026",doseEndDate="01.12.2026",continuous=true);PrescriptionRecordStore.upsertAll(c,listOf(old,newer));val due=PrescriptionLifecycle.due(c,LocalDate.of(2026,9,9));assertTrue(due.none{it.id=="old"});assertTrue(due.none{MedicationIdentity.canonical(it.medicationName)=="vasoxen"}) }
    @Test fun medicationIdentity_matchesSgkPackagingNoiseToLocalName(){ assertTrue(MedicationIdentity.same("Vasoxen","VASOXEN 5 MG 28 FILM TABLET"));assertTrue(MedicationIdentity.same("Euthyrox","EUTHYROX 50 MCG 100 TABLET")) }
    @Test fun sgkStockImport_addsNewCycleOnlyOnce(){ val sgkMed=Medication("sgk-med","Vasoxen 5 mg 28 tablet","1 tablet",emptyList());Store.save(c,listOf(sgkMed));MedicationMetaStore.save(c,MedicationMeta(medicationId=sgkMed.id,form=MedicationForm.TABLET,quantity=1.0,packageCount=28,packageUnit="tablet",source="sgk_pdf",doseUnitOverride="tablet"));PrescriptionRecordStore.upsertAll(c,listOf(PrescriptionRecord(id="sgk-cycle-1",medicationId=sgkMed.id,medicationName=sgkMed.name,prescriptionNo="RX1",prescriptionDate="21.08.2026",fillDate="21.08.2026",doseEndDate="16.10.2026",boxCount=2,dosePattern="1x1",continuous=true)));SgkStockAutoImporter.reconcile(c);assertEquals(56,StockEngine.forMedication(c,sgkMed.id)?.remainingDoses);SgkStockAutoImporter.reconcile(c);assertEquals(56,StockEngine.forMedication(c,sgkMed.id)?.remainingDoses) }
    @Test fun sgkStockImport_reusesExistingMedicationDespiteStrengthAndPackText(){ val local=Medication("local-vasoxen","Vasoxen","1 tablet",listOf("08:00"));Store.save(c,listOf(local));MedicationMetaStore.save(c,MedicationMeta(medicationId=local.id,form=MedicationForm.TABLET,quantity=1.0,packageCount=28,packageUnit="tablet",source="manual",doseUnitOverride="tablet"));PrescriptionRecordStore.upsertAll(c,listOf(PrescriptionRecord(id="variant-cycle",medicationName="VASOXEN 5 MG 28 FILM TABLET",prescriptionNo="RX2",prescriptionDate="01.09.2026",fillDate="01.09.2026",doseEndDate="28.10.2026",boxCount=2,dosePattern="1x1",continuous=true)));SgkStockAutoImporter.reconcile(c);assertEquals(1,Store.load(c).count{MedicationIdentity.canonical(it.name)=="vasoxen"});assertEquals(56,StockEngine.forMedication(c,local.id)?.remainingDoses);assertEquals(local.id,PrescriptionRecordStore.all(c).first{it.prescriptionNo=="RX2"}.medicationId) }
}
