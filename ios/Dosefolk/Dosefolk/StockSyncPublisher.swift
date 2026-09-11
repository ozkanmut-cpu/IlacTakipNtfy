import Foundation

actor StockSyncPublisher {
    private let localTopic: String
    private let store: LocalStore
    private let client: NtfyClient

    init(localTopic: String, store: LocalStore, client: NtfyClient = NtfyClient()) {
        self.localTopic = localTopic
        self.store = store
        self.client = client
    }

    func publishAll(to targetTopic: String) async throws {
        let target = targetTopic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !localTopic.isEmpty, !target.isEmpty, target != localTopic else { return }
        let stocks = try store.load([StockState].self, from: .stock, default: [])
        for stock in stocks {
            let payload = StockSyncPayload(
                ownerId: localTopic,
                actor: "",
                actorTopic: localTopic,
                revision: try nextRevision(),
                stock: stock,
                targetTopic: target
            )
            let data = try JSONEncoder().encode(payload)
            guard let body = String(data: data, encoding: .utf8) else { continue }
            try await client.publish(topic: localTopic, body: body)
        }
    }

    private func nextRevision() throws -> Int64 {
        var value = try store.load(Int64.self, from: .localRevision, default: 0)
        value += 1
        try store.save(value, to: .localRevision)
        return value
    }
}
