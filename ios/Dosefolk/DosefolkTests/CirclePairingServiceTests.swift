import XCTest
@testable import Dosefolk

final class CirclePairingServiceTests: XCTestCase {
    func testAddsPeerAndRunsInitialSync() async throws {
        let context = try TestContext()
        var synced: [String] = []
        let service = CirclePairingService(
            settings: context.settings,
            store: context.store,
            prepareRePair: { _ in XCTFail("unexpected re-pair") },
            completeRePair: { _ in XCTFail("unexpected re-pair") },
            initialSync: { synced.append($0) },
            makeID: { "peer-id" }
        )

        let peer = try await service.add(rawPayload: "dosefolk://pair?topic=dosefolk-peer&name=Ayse")
        XCTAssertEqual(peer, CirclePeer(id: "peer-id", name: "Ayse", topic: "dosefolk-peer"))
        XCTAssertEqual(synced, ["dosefolk-peer"])
        XCTAssertEqual(try context.store.load([CirclePeer].self, from: .circlePeers, default: []), [peer])
    }

    func testPairingSignalsSubscriptionChangeAfterPeerIsStored() async throws {
        let context = try TestContext()
        var observedTopics: [String] = []
        let service = CirclePairingService(
            settings: context.settings,
            store: context.store,
            prepareRePair: { _ in },
            completeRePair: { _ in },
            initialSync: { _ in },
            subscriptionsChanged: {
                observedTopics = (try? CircleTransport(settings: context.settings, store: context.store).subscriptionTopics()) ?? []
            },
            makeID: { "peer-id" }
        )

        _ = try await service.add(rawPayload: "dosefolk://pair?topic=dosefolk-peer&name=Ayse")

        XCTAssertEqual(observedTopics, ["dosefolk-local", "dosefolk-peer"])
    }

    func testRevokedPeerDrainsBeforeFenceIsCleared() async throws {
        let context = try TestContext()
        try CircleSecurityState(store: context.store).revoke(actorTopic: "dosefolk-peer")
        var order: [String] = []
        let service = CirclePairingService(
            settings: context.settings,
            store: context.store,
            prepareRePair: { topic in order.append("prepare:\(topic)") },
            completeRePair: { topic in order.append("complete:\(topic)") },
            initialSync: { topic in order.append("sync:\(topic)") }
        )

        _ = try await service.add(rawPayload: "dosefolk://pair?topic=dosefolk-peer&name=Ayse")
        XCTAssertEqual(order, ["prepare:dosefolk-peer", "complete:dosefolk-peer", "sync:dosefolk-peer"])
    }

    func testRejectsSelfAndDuplicatePairing() async throws {
        let context = try TestContext()
        let service = CirclePairingService(
            settings: context.settings,
            store: context.store,
            prepareRePair: { _ in },
            completeRePair: { _ in },
            initialSync: { _ in }
        )

        do {
            _ = try await service.add(rawPayload: "dosefolk://pair?topic=dosefolk-local&name=Me")
            XCTFail("self pairing should fail")
        } catch let error as CirclePairingServiceError {
            XCTAssertEqual(error, .selfPair)
        }

        try context.store.save([CirclePeer(id: "x", name: "A", topic: "dosefolk-peer")], to: .circlePeers)
        do {
            _ = try await service.add(rawPayload: "dosefolk://pair?topic=dosefolk-peer&name=A")
            XCTFail("duplicate pairing should fail")
        } catch let error as CirclePairingServiceError {
            XCTAssertEqual(error, .duplicatePeer)
        }
    }

    private struct TestContext {
        let directory: URL
        let store: LocalStore
        let settings: AppSettings

        init() throws {
            directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            store = try LocalStore(directory: directory)
            let defaults = UserDefaults(suiteName: UUID().uuidString)!
            settings = AppSettings(defaults: defaults)
            settings.localTopic = "dosefolk-local"
        }
    }
}
