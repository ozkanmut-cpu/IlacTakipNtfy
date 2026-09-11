import Foundation

struct CirclePollSync {
    let client: NtfyClient
    let coordinator: CircleSyncCoordinator
    let transport: CircleTransport
    let settings: AppSettings

    init(
        client: NtfyClient = NtfyClient(),
        coordinator: CircleSyncCoordinator,
        transport: CircleTransport,
        settings: AppSettings
    ) {
        self.client = client
        self.coordinator = coordinator
        self.transport = transport
        self.settings = settings
    }

    @discardableResult
    func pullOnce() async throws -> Int {
        let topics = try transport.subscriptionTopics()
        guard !topics.isEmpty else { return 0 }
        let since = settings.lastSyncID.isEmpty ? "24h" : settings.lastSyncID
        let batch = try await client.poll(topics: topics, since: since)

        var accepted = 0
        for envelope in batch.envelopes {
            let result = try coordinator.process(envelope)
            if case .accepted = result { accepted += 1 }
        }

        if let newest = batch.newestMessageID, !newest.isEmpty {
            settings.lastSyncID = newest
        }
        settings.lastSyncAt = Date()
        return accepted
    }
}
