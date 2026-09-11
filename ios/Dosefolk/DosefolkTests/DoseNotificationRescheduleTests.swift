import XCTest
@testable import Dosefolk

final class DoseNotificationRescheduleTests: XCTestCase {
    func testLocalProgramEventsTriggerReconcile() {
        for type in ["program_added", "program_updated", "program_deleted", "program_rule_updated"] {
            let event = DoseEvent(
                eventId: UUID().uuidString,
                type: type,
                time: "program",
                ownerId: "local-topic",
                actorTopic: "peer-topic"
            )
            XCTAssertTrue(DoseNotificationReschedule.shouldReconcile(event: event, localTopic: "local-topic"))
        }
    }

    func testRemoteOwnerProgramDoesNotTriggerLocalReconcile() {
        let event = DoseEvent(
            eventId: "remote-program",
            type: "program_updated",
            time: "program",
            ownerId: "remote-owner",
            actorTopic: "remote-owner"
        )
        XCTAssertFalse(DoseNotificationReschedule.shouldReconcile(event: event, localTopic: "local-topic"))
    }

    func testLegacyBlankOwnerFallsBackToActorTopic() {
        let event = DoseEvent(
            eventId: "legacy-rule",
            type: "program_rule_updated",
            time: "program",
            actorTopic: "local-topic"
        )
        XCTAssertTrue(DoseNotificationReschedule.shouldReconcile(event: event, localTopic: "local-topic"))
    }

    func testDoseStateEventDoesNotTriggerProgramReconcile() {
        let event = DoseEvent(
            eventId: "taken",
            type: "taken",
            time: "08:00",
            ownerId: "local-topic",
            actorTopic: "local-topic"
        )
        XCTAssertFalse(DoseNotificationReschedule.shouldReconcile(event: event, localTopic: "local-topic"))
    }
}
