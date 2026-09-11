import XCTest
@testable import Dosefolk

final class HistoryPresentationTests: XCTestCase {
    private func event(id: String, type: String, date: String, timestamp: Int64) -> DoseEvent {
        DoseEvent(
            eventId: id,
            type: type,
            time: "08:00",
            scheduledDate: date,
            actor: "Özkan",
            actorTopic: "dosefolk-local",
            timestamp: timestamp,
            medications: [Medication(id: "m1", name: "İlaç", dose: "1", times: ["08:00"])]
        )
    }

    func testTechnicalEventsAreHidden() {
        let visible = event(id: "1", type: "taken", date: "2026-09-11", timestamp: 20)
        let technical = event(id: "2", type: "circle_presence", date: "2026-09-11", timestamp: 30)
        let program = event(id: "3", type: "program_updated", date: "2026-09-11", timestamp: 40)

        let days = HistoryPresentation.days(from: [visible, technical, program])

        XCTAssertEqual(days.count, 1)
        XCTAssertEqual(days[0].entries.map(\.id), ["1"])
    }

    func testHistoryIsGroupedByDateAndNewestFirst() {
        let older = event(id: "old", type: "missed", date: "2026-09-10", timestamp: 10)
        let newer = event(id: "new", type: "taken", date: "2026-09-11", timestamp: 20)

        let days = HistoryPresentation.days(from: [older, newer])

        XCTAssertEqual(days.map(\.date), ["2026-09-11", "2026-09-10"])
        XCTAssertEqual(days[0].entries.first?.title, "İlaç alındı")
        XCTAssertTrue(days[0].entries.first?.detail.contains("İlaç") == true)
    }
}
