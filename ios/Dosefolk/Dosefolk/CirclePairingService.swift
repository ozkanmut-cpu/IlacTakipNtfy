import Foundation

enum CirclePairingServiceError: Error, Equatable {
    case invalidPayload
    case selfPair
    case duplicatePeer
}

final class CirclePairingService {
    typealias PrepareRePair = (_ topic: String) async throws -> Void
    typealias CompleteRePair = (_ topic: String) throws -> Void
    typealias InitialSync = (_ topic: String) async throws -> Void
    typealias SubscriptionsChanged = () -> Void

    private let settings: AppSettings
    private let store: LocalStore
    private let securityState: CircleSecurityState
    private let prepareRePair: PrepareRePair
    private let completeRePair: CompleteRePair
    private let initialSync: InitialSync
    private let subscriptionsChanged: SubscriptionsChanged
    private let makeID: () -> String

    init(
        settings: AppSettings,
        store: LocalStore,
        lifecycle: CirclePairingLifecycle,
        initialSyncPublisher: CircleInitialSyncPublisher,
        subscriptionsChanged: @escaping SubscriptionsChanged = {},
        makeID: @escaping () -> String = { UUID().uuidString }
    ) {
        self.settings = settings
        self.store = store
        self.securityState = CircleSecurityState(store: store)
        self.prepareRePair = { try await lifecycle.prepareRePair(topic: $0) }
        self.completeRePair = { try lifecycle.completeRePair(topic: $0) }
        self.initialSync = { try await initialSyncPublisher.publish(to: $0) }
        self.subscriptionsChanged = subscriptionsChanged
        self.makeID = makeID
    }

    init(
        settings: AppSettings,
        store: LocalStore,
        prepareRePair: @escaping PrepareRePair,
        completeRePair: @escaping CompleteRePair,
        initialSync: @escaping InitialSync,
        subscriptionsChanged: @escaping SubscriptionsChanged = {},
        makeID: @escaping () -> String = { UUID().uuidString }
    ) {
        self.settings = settings
        self.store = store
        self.securityState = CircleSecurityState(store: store)
        self.prepareRePair = prepareRePair
        self.completeRePair = completeRePair
        self.initialSync = initialSync
        self.subscriptionsChanged = subscriptionsChanged
        self.makeID = makeID
    }

    @discardableResult
    func add(rawPayload: String, fallbackName: String = "") async throws -> CirclePeer {
        guard let payload = CirclePairingPayload.parse(rawPayload) else {
            throw CirclePairingServiceError.invalidPayload
        }
        let localTopic = settings.localTopic.trimmingCharacters(in: .whitespacesAndNewlines)
        let peerTopic = payload.topic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard peerTopic != localTopic else { throw CirclePairingServiceError.selfPair }

        var peers = try store.load([CirclePeer].self, from: .circlePeers, default: [])
        guard !peers.contains(where: { $0.topic == peerTopic }) else {
            throw CirclePairingServiceError.duplicatePeer
        }

        let wasRevoked = try securityState.isRevoked(actorTopic: peerTopic)
        if wasRevoked {
            try await prepareRePair(peerTopic)
        }

        let name = payload.name.isEmpty ? fallbackName.trimmingCharacters(in: .whitespacesAndNewlines) : payload.name
        let peer = CirclePeer(id: makeID(), name: name, topic: peerTopic)
        peers.append(peer)
        try store.save(peers, to: .circlePeers)
        subscriptionsChanged()

        if wasRevoked {
            try completeRePair(peerTopic)
        }
        try await initialSync(peerTopic)
        return peer
    }
}
