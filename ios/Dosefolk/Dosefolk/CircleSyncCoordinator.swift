import Foundation

final class CircleSyncCoordinator {
    typealias RemoteStateHandler = (DoseEvent) throws -> Void

    private let liveStream: NtfyLiveStream
    private let processor: CircleSyncProcessor
    private let eventStore: DoseEventStore
    private let topics: () -> [String]
    private let remoteStateHandler: RemoteStateHandler
    private var task: Task<Void, Never>?

    init(
        localTopic: String,
        store: LocalStore,
        topics: @escaping () -> [String],
        liveStream: NtfyLiveStream = NtfyLiveStream(),
        remoteStateHandler: @escaping RemoteStateHandler = { _ in }
    ) {
        self.liveStream = liveStream
        self.processor = CircleSyncProcessor(
            localTopic: localTopic,
            securityState: CircleSecurityState(store: store)
        )
        self.eventStore = DoseEventStore(store: store)
        self.topics = topics
        self.remoteStateHandler = remoteStateHandler
    }

    func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            guard let self else { return }
            await self.liveStream.runReconnecting(
                topics: { self.topics() },
                onEnvelope: { envelope in
                    try? self.process(envelope)
                }
            )
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    @discardableResult
    func process(_ envelope: SyncEnvelope) throws -> InboundProtocolResult {
        try processor.process(envelope: envelope) { [eventStore, remoteStateHandler] event in
            let canonical = try eventStore.appendIfAbsent(event)
            try remoteStateHandler(canonical)
        }
    }
}
