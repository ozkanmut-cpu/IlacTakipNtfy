import Foundation

struct StockSyncPayload: Codable, Equatable {
    static let maximumProtocolVersion = 2

    var protocolVersion: Int = maximumProtocolVersion
    var eventId: String
    var type: String = "stock_updated"
    var ownerId: String
    var actor: String
    var actorTopic: String
    var timestamp: Int64
    var revision: Int64
    var stock: StockState
    var targetTopic: String = ""

    enum CodingKeys: String, CodingKey {
        case protocolVersion, eventId, type, ownerId, actor, actorTopic
        case timestamp, revision, stock, targetTopic
    }

    init(
        eventId: String = UUID().uuidString,
        ownerId: String,
        actor: String,
        actorTopic: String,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        revision: Int64,
        stock: StockState,
        targetTopic: String = ""
    ) {
        self.eventId = eventId
        self.ownerId = ownerId
        self.actor = actor
        self.actorTopic = actorTopic
        self.timestamp = timestamp
        self.revision = revision
        self.stock = stock
        self.targetTopic = targetTopic
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
