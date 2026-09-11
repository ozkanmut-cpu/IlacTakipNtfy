import Foundation

struct RemoteEventReceiptLedger: Codable, Equatable {
    private(set) var ids: [String] = []
    let limit: Int

    init(ids: [String] = [], limit: Int = 5_000) {
        self.limit = max(1, limit)
        self.ids = Self.trim(ids, limit: self.limit)
    }

    func contains(_ eventId: String) -> Bool {
        guard !eventId.isEmpty else { return false }
        return ids.contains(eventId)
    }

    mutating func markProcessed(_ eventId: String) {
        guard !eventId.isEmpty else { return }
        ids.removeAll { $0 == eventId }
        ids.append(eventId)
        ids = Self.trim(ids, limit: limit)
    }

    private static func trim(_ values: [String], limit: Int) -> [String] {
        var ordered: [String] = []
        for value in values where !value.isEmpty {
            ordered.removeAll { $0 == value }
            ordered.append(value)
        }
        return Array(ordered.suffix(limit))
    }
}

struct CircleSecurityState {
    private let store: LocalStore

    init(store: LocalStore) {
        self.store = store
    }

    func isProcessed(eventId: String) throws -> Bool {
        try receiptLedger().contains(eventId)
    }

    func markProcessed(eventId: String) throws {
        var ledger = try receiptLedger()
        ledger.markProcessed(eventId)
        try store.save(ledger, to: .remoteEventReceipts)
    }

    func isRevoked(actorTopic: String) throws -> Bool {
        guard !actorTopic.isEmpty else { return false }
        let topics = try store.load([String].self, from: .revokedPeers, default: [])
        return topics.contains(actorTopic)
    }

    func revoke(actorTopic: String) throws {
        guard !actorTopic.isEmpty else { return }
        var topics = try store.load([String].self, from: .revokedPeers, default: [])
        if !topics.contains(actorTopic) {
            topics.append(actorTopic)
            try store.save(topics, to: .revokedPeers)
        }
    }

    func clearRevoke(actorTopic: String) throws {
        var topics = try store.load([String].self, from: .revokedPeers, default: [])
        let oldCount = topics.count
        topics.removeAll { $0 == actorTopic }
        if topics.count != oldCount {
            try store.save(topics, to: .revokedPeers)
        }
    }

    private func receiptLedger() throws -> RemoteEventReceiptLedger {
        try store.load(RemoteEventReceiptLedger.self, from: .remoteEventReceipts, default: RemoteEventReceiptLedger())
    }
}
