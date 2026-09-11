import XCTest
@testable import Dosefolk

final class DoseStateEngineTests: XCTestCase {
    func testAlarmSnoozeTerminalAndUndoMatchAndroidStates() {
        let meds = [Medication(id: "m1", name: "Med", dose: "1", times: ["08:00"])]
        let alarm = event("alarm", actor: "a", revision: 1, meds: meds)
        XCTAssertEqual(reduce([alarm], meds).status, .pending)

        let snooze = event("snoozed", actor: "a", revision: 2, meds: meds)
        XCTAssertEqual(reduce([alarm, snooze], meds).status, .snoozed)

        let taken = event("taken", actor: "a", revision: 3, meds: meds)
        XCTAssertEqual(reduce([alarm, snooze, taken], meds).status, .taken)

        let undo = event("undo_taken", actor: "a", revision: 4, meds: meds)
        XCTAssertEqual(reduce([alarm, snooze, taken, undo], meds).status, .pending)
    }

    func testOpposingTerminalFactsRemainConflictRegardlessOfLamportOrder() {
        let taken = event("taken", actor: "a", revision: 2)
        let missed = event("missed", actor: "b", revision: 99)
        let state = reduce([taken, missed], [])
        XCTAssertEqual(state.status, .conflict)
        XCTAssertEqual(Set(state.conflictEvents.map(\.type)), Set(["taken", "missed"]))
    }

    func testExplicitResolutionSettlesConflictAndLaterUndoReturnsPending() {
        let taken = event("taken", actor: "a", revision: 2)
        let missed = event("missed", actor: "b", revision: 3)
        let resolved = event("conflict_resolved_taken", actor: "a", revision: 4)
        XCTAssertEqual(reduce([taken, missed, resolved], []).status, .taken)

        let undo = event("undo_taken", actor: "a", revision: 5)
        XCTAssertEqual(reduce([taken, missed, resolved, undo], []).status, .pending)
    }

    private func reduce(_ events: [DoseEvent], _ meds: [Medication]) -> DoseSessionState {
        DoseStateEngine.reduce(time: "08:00", events: events, scheduleMedications: meds, scheduledDate: "2026-09-11")
    }

    private func event(_ type: String, actor: String, revision: Int64, meds: [Medication] = []) -> DoseEvent {
        DoseEvent(
            eventId: "\(type)-\(actor)-\(revision)",
            type: type,
            time: "08:00",
            scheduledDate: "2026-09-11",
            actorTopic: actor,
            revision: revision,
            medications: meds
        )
    }
}
