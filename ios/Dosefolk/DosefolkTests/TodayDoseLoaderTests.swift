import XCTest
@testable import Dosefolk

final class TodayDoseLoaderTests: XCTestCase {
    private func makeStore() throws -> LocalStore {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("dosefolk-today-\(UUID().uuidString)", isDirectory: true)
        return try LocalStore(directory: url)
    }

    func testGroupsMedicationsAtSameTimeAndKeepsPastSlots() throws {
        let store = try makeStore()
        try store.save([
            Medication(id: "a", name: "A", dose: "1", times: ["08:00", "20:00"]),
            Medication(id: "b", name: "B", dose: "1", times: ["08:00"])
        ], to: .medications)

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = calendar.date(from: DateComponents(year: 2026, month: 9, day: 11, hour: 15))!
        let items = try TodayDoseLoader(store: store, calendar: calendar).load(on: date)

        XCTAssertEqual(items.map(\.time), ["08:00", "20:00"])
        XCTAssertEqual(items.first?.medications.map(\.id).sorted(), ["a", "b"])
    }

    func testUsesDoseStateEngineStatus() throws {
        let store = try makeStore()
        let medication = Medication(id: "a", name: "A", dose: "1", times: ["08:00"])
        try store.save([medication], to: .medications)
        try store.save([
            DoseEvent(
                eventId: "taken-1",
                type: "taken",
                time: "08:00",
                scheduledDate: "2026-09-11",
                ownerId: "local",
                actorTopic: "local",
                timestamp: 1,
                revision: 1,
                medications: [medication]
            )
        ], to: .doseEvents)

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = calendar.date(from: DateComponents(year: 2026, month: 9, day: 11, hour: 15))!
        let items = try TodayDoseLoader(store: store, calendar: calendar).load(on: date)

        XCTAssertEqual(items.first?.status, .taken)
    }
}
