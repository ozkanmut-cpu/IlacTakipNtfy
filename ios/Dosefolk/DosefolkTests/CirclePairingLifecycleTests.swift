import XCTest
@testable import Dosefolk

final class CirclePairingLifecycleTests: XCTestCase {
    private var directory: URL!
    private var store: LocalStore!
    private var security: CircleSecurityState!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        store = try LocalStore(directory: directory)
        security = CircleSecurityState(store: store)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testPrepareRePairDrainsRevokedEventsButKeepsFenceUntilComplete() async throws {
        try security.revoke(actorTopic: "peer-topic")
        let envelope = SyncEnvelope(
            id: "ntfy-1",
            time: nil,
            event: "message",
            topic: "peer-topic",
            message: payload(eventId: "old-event")
        )
        let lifecycle = CirclePairingLifecycle(
            localTopic: "local-topic",
            store: store,
            poll: { topics, since in
                XCTAssertEqual(topics, ["peer-topic"])
                XCTAssertEqual(since, "24h")
                return NtfyPollResult(envelopes: [envelope], newestMessageID: "ntfy-1")
            }
        )

        try await lifecycle.prepareRePair(topic: "peer-topic")

        XCTAssertTrue(try security.isRevoked(actorTopic: "peer-topic"))
        XCTAssertTrue(try security.isProcessed(eventId: "old-event"))

        try lifecycle.completeRePair(topic: "peer-topic")
        XCTAssertFalse(try security.isRevoked(actorTopic: "peer-topic"))
    }

    func testFailedDrainDoesNotClearFence() async throws {
        try security.revoke(actorTopic: "peer-topic")
        let lifecycle = CirclePairingLifecycle(
            localTopic: "local-topic",
            store: store,
            poll: { _, _ in throw NtfyClientError.http(503) }
        )

        do {
            try await lifecycle.prepareRePair(topic: "peer-topic")
            XCTFail("Expected drain failure")
        } catch let error as CirclePairingLifecycleError {
            XCTAssertEqual(error, .drainFailed)
        }
        XCTAssertTrue(try security.isRevoked(actorTopic: "peer-topic"))
    }

    private func payload(eventId: String) -> String {
        """
        {"v":9,"eventId":"\(eventId)","type":"taken","time":"08:00","scheduledDate":"2026-09-11","ownerId":"local-topic","actorTopic":"peer-topic","targetTopic":"local-topic"}
        """
    }
}
