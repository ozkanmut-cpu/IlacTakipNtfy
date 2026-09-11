import XCTest
@testable import Dosefolk

final class DoseSchedulePlannerTests: XCTestCase {
    private var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(secondsFromGMT: 0)!
        return c
    }

    func testGroupsMedicationsAtSameTimeAndSkipsPastToday() throws {
        let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 11, hour: 9)))
        let meds = [
            Medication(id: "a", name: "A", dose: "1", times: ["08:00", "20:00"]),
            Medication(id: "b", name: "B", dose: "1", times: ["20:00"])
        ]
        let result = DoseSchedulePlanner.plan(medications: meds, rules: [], now: now, horizonDays: 1, calendar: calendar)
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result[0].time, "20:00")
        XCTAssertEqual(Set(result[0].medications.map(\.id)), Set(["a", "b"]))
    }

    func testWeekdayAndEveryNDaysMatchAndroidSemantics() throws {
        let friday = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 11)))
        let monday = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 14)))
        let mondayRule = ProgramRule(medicationId: "a", weekdays: [1])
        XCTAssertFalse(DoseSchedulePlanner.isActive(mondayRule, on: friday, today: friday, calendar: calendar))
        XCTAssertTrue(DoseSchedulePlanner.isActive(mondayRule, on: monday, today: friday, calendar: calendar))

        let alternate = ProgramRule(medicationId: "a", startDate: "2026-09-11", everyNDays: 2)
        let saturday = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 12)))
        let sunday = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 13)))
        XCTAssertTrue(DoseSchedulePlanner.isActive(alternate, on: friday, today: friday, calendar: calendar))
        XCTAssertFalse(DoseSchedulePlanner.isActive(alternate, on: saturday, today: friday, calendar: calendar))
        XCTAssertTrue(DoseSchedulePlanner.isActive(alternate, on: sunday, today: friday, calendar: calendar))
    }

    func testPlannerCapsRequestsAndHonorsDateBounds() throws {
        let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 11, hour: 7)))
        let med = Medication(id: "a", name: "A", dose: "1", times: ["08:00", "20:00"])
        let rule = ProgramRule(medicationId: "a", startDate: "2026-09-12", endDate: "2026-09-14")
        let result = DoseSchedulePlanner.plan(medications: [med], rules: [rule], now: now, horizonDays: 10, maxRequests: 3, calendar: calendar)
        XCTAssertEqual(result.count, 3)
        XCTAssertEqual(result.first?.scheduledDate, "2026-09-12")
        XCTAssertEqual(result.last?.scheduledDate, "2026-09-13")
    }
}
