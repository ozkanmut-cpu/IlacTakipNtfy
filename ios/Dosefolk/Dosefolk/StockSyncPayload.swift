import Foundation

struct StockSyncPayload: Codable, Equatable {
    static let maximumProtocolVersion = 2

    var protocolVersion: Int
    var eventId: String
    var type: String
    var ownerId: String
    var actor: String
    var actorTopic: String
    var timestamp: Int64
    var revision: Int64
    var stock: StockState
    var targetTopic: String

    enum CodingKeys: String, CodingKey {
        case protocolVersion, eventId, type, ownerId, actor, actorTopic
        case timestamp, revision, stock, targetTopic
    }

    init(eventId: String = UUID().uuidString, ownerId: String, actor: String, actorTopic: String,
         timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000), revision: Int64,
         stock: StockState, targetTopic: String = "") {
        self.protocolVersion = Self.maximumProtocolVersion
        self.eventId = eventId
        self.type = "stock_updated"
        self.ownerId = ownerId
        self.actor = actor
        self.actorTopic = actorTopic
        self.timestamp = timestamp
        self.revision = revision
        self.stock = stock
        self.targetTopic = targetTopic
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        protocolVersion = try c.decodeIfPresent(Int.self, forKey: .protocolVersion) ?? 1
        eventId = try c.decodeIfPresent(String.self, forKey: .eventId) ?? ""
        type = try c.decodeIfPresent(String.self, forKey: .type) ?? ""
        ownerId = try c.decodeIfPresent(String.self, forKey: .ownerId) ?? ""
        actor = try c.decodeIfPresent(String.self, forKey: .actor) ?? ""
        actorTopic = try c.decodeIfPresent(String.self, forKey: .actorTopic) ?? ""
        timestamp = try c.decodeIfPresent(Int64.self, forKey: .timestamp) ?? 0
        revision = try c.decodeIfPresent(Int64.self, forKey: .revision) ?? 0
        stock = try c.decode(StockState.self, forKey: .stock)
        targetTopic = try c.decodeIfPresent(String.self, forKey: .targetTopic) ?? ""
    }
}

struct ScopedStockState: Codable, Equatable {
    var ownerId: String
    var stock: StockState
}

struct StockOrderingEntry: Codable, Equatable {
    var revision: Int64
    var actorTopic: String
    var eventId: String
    var updatedAt: Int64
}
