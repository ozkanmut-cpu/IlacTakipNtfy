package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class PrnGuardrailRetentionTest {
    private lateinit var c: Context
    private val med = Medication("prn-med", "PRN Med", "1 tablet", emptyList())

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        listOf("ilac_takip", "dosefolk_events", "dosefolk_prn", "dosefolk_prn_usage", "dosefolk_owner_scope")
            .forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun prnEvent(id:String,timestamp:Long)=DoseEvent(
        eventId=id,
        type="prn_taken",
        time="PRN",
        actor="local",
        actorTopic="",
        timestamp=timestamp,
        medications=listOf(med),
        syncState="synced",
        revision=1L,
        scheduledDate=LocalDate.now().toString(),
        ownerId=""
    )

    @Test
    fun existingEventStorePrnHistory_isBackfilledIntoDurableLedger() {
        val now=System.currentTimeMillis()
        EventStore.append(c,prnEvent("legacy-prn",now-10*60_000L))
        val item=PrnMedication("item",med.id,med.name,minimumIntervalMinutes=60)

        val check=PrnEngine.check(c,item,now)

        assertFalse(check.allowed)
        assertEquals("minimum_interval",check.reason)
        assertEquals(1,PrnUsageLedger.usages(c,med.id).size)
    }

    @Test
    fun dailyMaximumAndMinimumInterval_surviveEventStoreCompaction() {
        val zone=ZoneId.systemDefault()
        val start=LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val first=prnEvent("prn-1",start+8*60*60_000L)
        val second=prnEvent("prn-2",start+9*60*60_000L)
        PrnUsageLedger.observe(c,first)
        PrnUsageLedger.observe(c,second)

        repeat(1100){i->
            EventStore.append(c,DoseEvent(
                eventId="noise-$i", type="alarm", time="08:00", actor="x", actorTopic="x",
                timestamp=start-86_400_000L-i, medications=emptyList(), syncState="synced",
                revision=i.toLong()+1, scheduledDate=LocalDate.now().minusDays(1).toString(), ownerId="x"
            ))
        }
        assertTrue(EventStore.load(c).size<=1000)

        val maxItem=PrnMedication("max",med.id,med.name,maximumPerDay=2)
        val maxCheck=PrnEngine.check(c,maxItem,start+10*60*60_000L)
        assertFalse(maxCheck.allowed)
        assertEquals("daily_maximum",maxCheck.reason)
        assertEquals(2,maxCheck.takenToday)

        val intervalItem=PrnMedication("interval",med.id,med.name,minimumIntervalMinutes=120)
        val intervalCheck=PrnEngine.check(c,intervalItem,start+10*60*60_000L)
        assertFalse(intervalCheck.allowed)
        assertEquals("minimum_interval",intervalCheck.reason)
    }
}
