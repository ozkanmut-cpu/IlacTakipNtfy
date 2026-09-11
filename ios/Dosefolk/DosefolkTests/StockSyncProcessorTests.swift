import XCTest
@testable import Dosefolk

final class StockSyncProcessorTests: XCTestCase {
    func testAppliesKnownPeerStockSnapshotAndRejectsStaleRevision() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try LocalStore(directory: directory)
        try store.save([CirclePeer(id: "p1", name: "Peer", topic: "peer-topic")], to: .circlePeers)
        let security = CircleSecurityState(store: store)
        let sut = StockSyncProcessor(localTopic: "local-topic", store: store, securityState: security)

        let newer = StockSyncPayload(
            eventId: "stock-2", ownerId: "peer-topic", actor: "Peer", actorTopic: "peer-topic",
            timestamp: 200, revision: 2,
            stock: StockState(medicationId: "m1", medicationName: "Drug", remainingDoses: 8, packSize: 10, updatedAt: 200),
            targetTopic: "local-topic"
        )
        let older = StockSyncPayload(
            eventId: "stock-1", ownerId: "peer-topic", actor: "Peer", actorTopic: "peer-topic",
            timestamp: 100, revision: 1,
            stock: StockState(medicationId: "m1", medicationName: "Drug", remainingDoses: 9, packSize: 10, updatedAt: 100),
            targetTopic: "local-topic"
        )

        XCTAssertEqual(try sut.process(envelope(newer)), .applied(newer))
        XCTAssertEqual(try sut.process(envelope(older)), .ignored)
        let stored = try store.load([ScopedStockState].self, from: .remoteStock, default: [])
        XCTAssertEqual(stored, [ScopedStockState(ownerId: "peer-topic", stock: newer.stock)])
    }

    func testRejectsWrongTargetUnknownPeerAndPublisherMismatch() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try LocalStore(directory: directory)
        let sut = StockSyncProcessor(localTopic: "local-topic", store: store, securityState: CircleSecurityState(store: store))
        let payload = StockSyncPayload(
            eventId: "stock-1", ownerId: "peer-topic", actor: "Peer", actorTopic: "peer-topic", revision: 1,
            stock: StockState(medicationId: "m1", medicationName: "Drug", remainingDoses: 8, packSize: 10, updatedAt: 100),
            targetTopic: "someone-else"
        )
        XCTAssertEqual(try sut.process(envelope(payload)), .ignored)

        var unknown = payload
        unknown.targetTopic = "local-topic"
        XCTAssertEqual(try sut.process(envelope(unknown)), .ignored)

        try store.save([CirclePeer(id: "p1", name: "Peer", topic: "peer-topic")], to: .circlePeers)
        XCTAssertEqual(try sut.process(envelope(unknown, topic: "spoof-topic")), .ignored)
    }

    private func envelope(_ payload: StockSyncPayload, topic: String = "peer-topic") throws -> SyncEnvelope {
        let data = try JSONEncoder().encode(payload)
        return SyncEnvelope(id: "ntfy-id", time: 1, event: "message", topic: topic, message: String(decoding: data, as: UTF8.self))
    }
}
