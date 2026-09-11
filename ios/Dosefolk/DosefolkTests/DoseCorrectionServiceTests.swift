import XCTest
@testable import Dosefolk

final class DoseCorrectionServiceTests: XCTestCase {
    private let med = Medication(id: "med-1", name: "Test", dose: "1", times: ["08:00"])

    func testUndoTakenPublishesUndoTaken() {
        let state = DoseSessionState(
            time: "08:00",
            status: .taken,
            latestEvent: nil,
            medications: [med],
            scheduledDate: "2026-09-11"
        )
        XCTAssertEqual(
            DoseCorrectionPolicy.plan(intent: .undo, state: state),
            .publish(type: "undo_taken", medications: [med])
        )
    }

    func testUndoMissedPublishesUndoMissed() {
        let state = DoseSessionState(
            time: "08:00",
            status: .missed,
            latestEvent: nil,
            medications: [med],
            scheduledDate: "2026-09-11"
        )
        XCTAssertEqual(
            DoseCorrectionPolicy.plan(intent: .undo, state: state),
            .publish(type: "undo_missed", medications: [med])
        )
    }

    func testCorrectionToSameStateIsNoChange() {
        let state = DoseSessionState(
            time: "08:00",
            status: .taken,
            latestEvent: nil,
            medications: [med],
            scheduledDate: "2026-09-11"
        )
        XCTAssertEqual(DoseCorrectionPolicy.plan(intent: .correctToTaken, state: state), .noChange)
    }

    func testConflictCanResolveToMissed() {
        let state = DoseSessionState(
            time: "08:00",
            status: .conflict,
            latestEvent: nil,
            medications: [med],
            scheduledDate: "2026-09-11"
        )
        XCTAssertEqual(
            DoseCorrectionPolicy.plan(intent: .correctToMissed, state: state),
            .publish(type: "conflict_resolved_missed", medications: [med])
        )
    }

    func testCorrectionWithoutMedicationIsUnavailable() {
        let state = DoseSessionState(
            time: "08:00",
            status: .pending,
            latestEvent: nil,
            medications: [],
            scheduledDate: "2026-09-11"
        )
        XCTAssertEqual(DoseCorrectionPolicy.plan(intent: .correctToTaken, state: state), .unavailable)
    }
}
