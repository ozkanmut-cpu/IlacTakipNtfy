import XCTest
@testable import Dosefolk

final class CircleSecurityStateTests: XCTestCase {
    func testReceiptLedger_isIdempotentAndKeepsNewestEntries() {
        var ledger = RemoteEventReceiptLedger(limit: 3)
        ledger.markProcessed("a")
        ledger.markProcessed("b")
        ledger.markProcessed("a")
        ledger.markProcessed("c")
        ledger.markProcessed("d")

        XCTAssertEqual(ledger.ids, ["a", "c", "d"])
        XCTAssertTrue(ledger.contains("a"))
        XCTAssertFalse(ledger.contains("b"))
    }

    func testSecurityState_persistsProcessedAndRevokedPeers() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let store = try LocalStore(directory: directory)
        let state = CircleSecurityState(store: store)

        XCTAssertFalse(try state.isProcessed(eventId: "event-1"))
        try state.markProcessed(eventId: "event-1")
        XCTAssertTrue(try CircleSecurityState(store: try LocalStore(directory: directory)).isProcessed(eventId: "event-1"))

        XCTAssertFalse(try state.isRevoked(actorTopic: "peer-topic"))
        try state.revoke(actorTopic: "peer-topic")
        XCTAssertTrue(try CircleSecurityState(store: try LocalStore(directory: directory)).isRevoked(actorTopic: "peer-topic"))

        try state.clearRevoke(actorTopic: "peer-topic")
        XCTAssertFalse(try state.isRevoked(actorTopic: "peer-topic"))
    }
}
