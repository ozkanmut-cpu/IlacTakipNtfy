package com.ozkanmut.ilactakip

import org.junit.Assert.assertEquals
import org.junit.Test

class OrkoTakipBridgeTest {
    private val groups = listOf(
        OrkoTakipBridge.ScheduleGroup("07:30", false),
        OrkoTakipBridge.ScheduleGroup("08:30", false),
        OrkoTakipBridge.ScheduleGroup("13:00", false),
        OrkoTakipBridge.ScheduleGroup("19:30", false),
        OrkoTakipBridge.ScheduleGroup("22:30", true)
    )

    @Test fun morningFirstGroupMapsToFastingAnchor() {
        assertEquals(
            OrkoTakipBridge.Anchor.MORNING_FIRST_GROUP,
            OrkoTakipBridge.resolve(groups, "07:30")
        )
    }

    @Test fun morningSecondGroupMapsToBreakfastPostMealAnchor() {
        assertEquals(
            OrkoTakipBridge.Anchor.MORNING_SECOND_POST_MEAL_GROUP,
            OrkoTakipBridge.resolve(groups, "08:30")
        )
    }

    @Test fun latestNonBedtimeEveningGroupMapsToDinnerAnchor() {
        assertEquals(
            OrkoTakipBridge.Anchor.EVENING_COMBINED_POST_MEAL_GROUP,
            OrkoTakipBridge.resolve(groups, "19:30")
        )
    }

    @Test fun lateInsulinGroupMapsToBedtimeToujeoAnchorWithoutNameMatching() {
        assertEquals(
            OrkoTakipBridge.Anchor.BEDTIME_TOUJEO,
            OrkoTakipBridge.resolve(groups, "22:30")
        )
    }

    @Test fun unrelatedMiddayGroupRemainsUnmapped() {
        assertEquals(
            OrkoTakipBridge.Anchor.UNKNOWN,
            OrkoTakipBridge.resolve(groups, "13:00")
        )
    }
}
