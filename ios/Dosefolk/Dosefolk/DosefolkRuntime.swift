import Foundation

final class DosefolkRuntime {
    let settings: AppSettings
    let store: LocalStore
    let transport: CircleTransport
    let coordinator: CircleSyncCoordinator
    let pairingService: CirclePairingService

    init(settings: AppSettings = AppSettings(), store: LocalStore? = nil) throws {
        self.settings = settings
        let localTopic = settings.ensureLocalTopic()
        let resolvedStore = try store ?? LocalStore()
        self.store = resolvedStore

        let transport = CircleTransport(settings: settings, store: resolvedStore)
        self.transport = transport
        self.coordinator = CircleSyncCoordinator(
            localTopic: localTopic,
            store: resolvedStore,
            topics: { (try? transport.subscriptionTopics()) ?? [localTopic] }
        )

        let publisher = ProtocolEventPublisher(settings: settings, store: resolvedStore)
        let lifecycle = CirclePairingLifecycle(localTopic: localTopic, store: resolvedStore)
        let initialSync = CircleInitialSyncPublisher(store: resolvedStore, publisher: publisher, localTopic: localTopic)
        self.pairingService = CirclePairingService(
            settings: settings,
            store: resolvedStore,
            lifecycle: lifecycle,
            initialSyncPublisher: initialSync
        )
    }

    var ownPairingPayload: String {
        CirclePairingPayload.encode(topic: settings.localTopic, name: settings.displayName) ?? settings.localTopic
    }

    func start() {
        coordinator.start()
    }

    func stop() {
        coordinator.stop()
    }

    @discardableResult
    func addPair(rawPayload: String, fallbackName: String) async throws -> CirclePeer {
        let peer = try await pairingService.add(rawPayload: rawPayload, fallbackName: fallbackName)
        coordinator.stop()
        coordinator.start()
        return peer
    }
}
