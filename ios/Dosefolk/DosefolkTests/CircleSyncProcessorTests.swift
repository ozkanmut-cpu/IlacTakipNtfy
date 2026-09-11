import XCTest
@testable import Dosefolk

final class CircleSyncProcessorTests: XCTestCase {
    private enum TestError: Error { case applyFailed }

    func testMarksProcessedOnlyAfterApplySucceeds() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let state = CircleSecurityState(store: try LocalStore(directory: directory))
        let processor = CircleSyncProcessor(localTopic: "local-topic", securityState: state)
        let envelope = SyncEnvelope(
            event: "message",
            topic: "peer-topic",
            message: #"{"v":9,"eventId":"evt-apply","type":"taken","time":"08:00","actorTopic":"peer-topic","targetTopic":"local-topic"}"#
        )

        XCTAssertThrowsError(try processor.process(envelope: envelope) { _ in
            throw TestError.applyFailed
        })
        XCTAssertFalse(try state.isProcessed(eventId: "evt-apply"))

        var applied = 0
        let result = try processor.process(envelope: envelope) { _ in applied += 1 }
        guard case .accepted = result else { return XCTFail("Expected accepted event") }
        XCTAssertEqual(applied, 1)
        XCTAssertTrue(try state.isProcessed(eventId: "evt-apply"))

        let duplicate = try processor.process(envelope: envelope) { _ in applied += 1 }
        XCTAssertEqual(duplicate, .rejected(.duplicateEvent))
        XCTAssertEqual(applied, 1)
    }
}
