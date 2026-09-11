import Foundation

final class CircleSyncCoordinator {
    typealias RemoteStateHandler = (DoseEvent) throws -> Void

    private let liveStream: NtfyLiveStream
    private let processor: CircleSyncProcessor
    private let stockProcessor: StockSyncProcessor
    private let eventStore: DoseEventStore
    private let topics: () -> [String]
    private let remoteStateHandler: RemoteStateHandler
    private var task: Task<Void, Never>?

    init(
        localTopic: String,
        store: LocalStore,
        topics: @escaping () -> [String],
        liveStream: NtfyLiveStream = NtfyLiveStream(),
        remoteStateHandler: RemoteStateHandler? = nil
    ) {
        self.liveStream = liveStream
        let securityState = CircleSecurityState(store: store)
        self.processor = CircleSyncProcessor(localTopic: localTopic, securityState: securityState)
        self.stockProcessor = StockSyncProcessor(localTopic: localTopic, store: store, securityState: securityState)
        self.eventStore = DoseEventStore(store: store)
        self.topics = topics
        if let remoteStateHandler {
            self.remoteStateHandler = remoteStateHandler
        } else {
            let reducer = RemoteStateReducer(store: store, localOwnerId: localTopic)
            self.remoteStateHandler = reducer.apply
        }
    }

    func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            guard let self else { return }
            await self.liveStream.runReconnecting(
                topics: { self.topics() },
                onEnvelope: { envelope in
                    _ = try? self.processAny(envelope)
                }
            )
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    @discardableResult
    func processAny(_ envelope: SyncEnvelope) throws -> Bool {
        switch try stockProcessor.process(envelope) {
        case .applied:
            return true
        case .ignored:
            return false
        case .notStock:
            if case .accepted = try process(envelope) { return true }
            return false
        }
    }

    @discardableResult
    func process(_ envelope: SyncEnvelope) throws -> InboundProtocolResult {
        try processor.process(envelope: envelope) { [eventStore, remoteStateHandler] event in
            let canonical = try eventStore.appendIfAbsent(event)
            try remoteStateHandler(canonical)
        }
    }
}
