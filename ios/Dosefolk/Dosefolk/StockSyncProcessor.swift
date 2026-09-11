import Foundation

enum StockSyncResult: Equatable {
    case notStock
    case applied(StockSyncPayload)
    case ignored
}

struct StockSyncProcessor {
    let localTopic: String
    let store: LocalStore
    let securityState: CircleSecurityState

    func process(_ envelope: SyncEnvelope) throws -> StockSyncResult {
        guard envelope.event == "message", let message = envelope.message,
              let data = message.data(using: .utf8) else { return .notStock }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (object["type"] as? String) == "stock_updated" else { return .notStock }
        guard let payload = try? JSONDecoder().decode(StockSyncPayload.self, from: data) else { return .ignored }

        let actor = payload.actorTopic.trimmingCharacters(in: .whitespacesAndNewlines)
        let owner = payload.ownerId.isEmpty ? actor : payload.ownerId
        let envelopeTopic = envelope.topic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !actor.isEmpty, envelopeTopic == actor, owner == actor else { return .ignored }
        guard payload.targetTopic.isEmpty || payload.targetTopic == localTopic else { return .ignored }
        guard (1...StockSyncPayload.maximumProtocolVersion).contains(payload.protocolVersion) else { return .ignored }
        guard actor != localTopic, !payload.eventId.isEmpty else { return .ignored }
        if try securityState.isRevoked(actorTopic: actor) {
            try securityState.markProcessed(eventId: payload.eventId)
            return .ignored
        }
        if try securityState.isProcessed(eventId: payload.eventId) { return .ignored }
        let peers = try store.load([CirclePeer].self, from: .circlePeers, default: [])
        guard peers.contains(where: { $0.topic == owner }) else { return .ignored }

        var ordering = try store.load([String: StockOrderingEntry].self, from: .stockOrdering, default: [:])
        let key = "\(owner)|\(payload.stock.medicationId)"
        if let current = ordering[key], !wins(payload, over: current) {
            try securityState.markProcessed(eventId: payload.eventId)
            return .ignored
        }
        var remote = try store.load([ScopedStockState].self, from: .remoteStock, default: [])
        remote.removeAll { $0.ownerId == owner && $0.stock.medicationId == payload.stock.medicationId }
        remote.append(ScopedStockState(ownerId: owner, stock: payload.stock))
        try store.save(remote, to: .remoteStock)
        ordering[key] = StockOrderingEntry(revision: payload.revision, actorTopic: actor, eventId: payload.eventId, updatedAt: payload.stock.updatedAt)
        try store.save(ordering, to: .stockOrdering)
        try securityState.markProcessed(eventId: payload.eventId)
        return .applied(payload)
    }

    private func wins(_ payload: StockSyncPayload, over current: StockOrderingEntry) -> Bool {
        if payload.revision > 0 || current.revision > 0 {
            if payload.revision != current.revision { return payload.revision > current.revision }
            if payload.actorTopic != current.actorTopic { return payload.actorTopic > current.actorTopic }
            return payload.eventId > current.eventId
        }
        return payload.stock.updatedAt >= current.updatedAt
    }
}
