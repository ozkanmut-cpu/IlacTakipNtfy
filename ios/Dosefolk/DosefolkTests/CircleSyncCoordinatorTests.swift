import XCTest
@testable import Dosefolk

final class CircleSyncCoordinatorTests: XCTestCase {
    func testCoordinatorPersistsAcceptedEventAndRunsRemoteHandlerOnce() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try LocalStore(directory: directory)
        var applied: [String] = []
        let coordinator = CircleSyncCoordinator(
            localTopic: "local-topic",
            store: store,
            topics: { ["local-topic", "peer-topic"] },
            remoteStateHandler: { applied.append($0.eventId) }
        )
        let envelope = SyncEnvelope(
            event: "message",
            topic: "peer-topic",
            message: #"{"v":9,"eventId":"evt-coordinator","type":"taken","time":"08:00","actorTopic":"peer-topic","targetTopic":"local-topic"}"#
        )

        guard case .accepted = try coordinator.process(envelope) else {
            return XCTFail("Expected accepted event")
        }
        XCTAssertEqual(applied, ["evt-coordinator"])
        XCTAssertEqual(try DoseEventStore(store: store).all().map(\.eventId), ["evt-coordinator"])

        XCTAssertEqual(try coordinator.process(envelope), .rejected(.duplicateEvent))
        XCTAssertEqual(applied, ["evt-coordinator"])
    }

    func testOnlyRevokeRequiresImmediateSubscriptionRefresh() {
        let revoke = DoseEvent(eventId: "r", type: "circle_revoked", time: "circle")
        let taken = DoseEvent(eventId: "t", type: "taken", time: "08:00")
        XCTAssertTrue(CircleSyncCoordinator.requiresSubscriptionRefresh(revoke))
        XCTAssertFalse(CircleSyncCoordinator.requiresSubscriptionRefresh(taken))
    }
}
