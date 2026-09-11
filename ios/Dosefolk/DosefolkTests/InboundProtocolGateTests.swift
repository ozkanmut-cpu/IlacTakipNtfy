import XCTest
@testable import Dosefolk

final class InboundProtocolGateTests: XCTestCase {
    func testPublisherTopicMismatchRejectsBeforePayloadApplication() throws {
        let envelope = SyncEnvelope(
            event: "message",
            topic: "ntfy-envelope-topic",
            message: payload(actorTopic: "different-publisher", targetTopic: "local-topic", version: 9)
        )
        XCTAssertEqual(
            InboundProtocolGate.inspect(envelope: envelope, localTopic: "local-topic"),
            .rejected(.publisherTopicMismatch)
        )
    }

    func testWrongTargetRejectsAfterPublisherBinding() throws {
        let envelope = SyncEnvelope(
            event: "message",
            topic: "publisher-topic",
            message: payload(actorTopic: "publisher-topic", targetTopic: "other-phone", version: 9)
        )
        XCTAssertEqual(
            InboundProtocolGate.inspect(envelope: envelope, localTopic: "local-topic"),
            .rejected(.wrongTarget)
        )
    }

    func testFutureProtocolIsSafelyRejected() throws {
        let envelope = SyncEnvelope(
            event: "message",
            topic: "publisher-topic",
            message: payload(actorTopic: "publisher-topic", targetTopic: "local-topic", version: 10)
        )
        XCTAssertEqual(
            InboundProtocolGate.inspect(envelope: envelope, localTopic: "local-topic"),
            .rejected(.unsupportedProtocol(10))
        )
    }

    func testMissingVersionMatchesAndroidLegacyVersionOne() throws {
        let message = #"{"eventId":"legacy-1","type":"taken","time":"08:00","actorTopic":"publisher-topic","targetTopic":"local-topic"}"#
        let envelope = SyncEnvelope(event: "message", topic: "publisher-topic", message: message)

        guard case let .accepted(event) = InboundProtocolGate.inspect(envelope: envelope, localTopic: "local-topic") else {
            return XCTFail("Legacy payload without v should be accepted as protocol v1")
        }
        XCTAssertEqual(event.eventId, "legacy-1")
    }

    func testReplayTruncationHeaderMatchesAndroid() {
        XCTAssertTrue(NtfyReplayGuard.isTruncated("1"))
        XCTAssertTrue(NtfyReplayGuard.isTruncated(" 1 "))
        XCTAssertFalse(NtfyReplayGuard.isTruncated("0"))
        XCTAssertFalse(NtfyReplayGuard.isTruncated(nil))
    }

    func testSecureGateRejectsLocalEchoDuplicateAndRevokedPeer() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let state = CircleSecurityState(store: try LocalStore(directory: directory))

        let localEcho = SyncEnvelope(
            event: "message",
            topic: "local-topic",
            message: payload(actorTopic: "local-topic", targetTopic: "local-topic", version: 9, eventId: "echo-1")
        )
        XCTAssertEqual(
            try InboundProtocolGate.inspectSecure(envelope: localEcho, localTopic: "local-topic", securityState: state),
            .rejected(.localEcho)
        )

        try state.markProcessed(eventId: "dup-1")
        let duplicate = SyncEnvelope(
            event: "message",
            topic: "peer-topic",
            message: payload(actorTopic: "peer-topic", targetTopic: "local-topic", version: 9, eventId: "dup-1")
        )
        XCTAssertEqual(
            try InboundProtocolGate.inspectSecure(envelope: duplicate, localTopic: "local-topic", securityState: state),
            .rejected(.duplicateEvent)
        )

        try state.revoke(actorTopic: "peer-topic")
        let revoked = SyncEnvelope(
            event: "message",
            topic: "peer-topic",
            message: payload(actorTopic: "peer-topic", targetTopic: "local-topic", version: 9, eventId: "revoked-1")
        )
        XCTAssertEqual(
            try InboundProtocolGate.inspectSecure(envelope: revoked, localTopic: "local-topic", securityState: state),
            .rejected(.revokedPeer)
        )
        XCTAssertTrue(try state.isProcessed(eventId: "revoked-1"))
    }

    private func payload(
        actorTopic: String,
        targetTopic: String,
        version: Int,
        eventId: String = "evt-1"
    ) -> String {
        """
        {"v":\(version),"eventId":"\(eventId)","type":"taken","time":"08:00","actorTopic":"\(actorTopic)","targetTopic":"\(targetTopic)"}
        """
    }
}
