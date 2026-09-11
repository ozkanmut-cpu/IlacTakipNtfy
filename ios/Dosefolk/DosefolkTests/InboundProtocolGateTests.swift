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

    private func payload(actorTopic: String, targetTopic: String, version: Int) -> String {
        """
        {"v":\(version),"eventId":"evt-1","type":"taken","time":"08:00","actorTopic":"\(actorTopic)","targetTopic":"\(targetTopic)"}
        """
    }
}
