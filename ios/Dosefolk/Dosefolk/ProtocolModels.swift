import Foundation

struct Medication: Codable, Equatable, Identifiable {
    let id: String
    var name: String
    var dose: String
    var times: [String]
}

enum MedicationForm: String, Codable, CaseIterable {
    case TABLET, INSULIN, INJECTION, NEBULE, INHALER, DROP, LIQUID, CREAM, PATCH, OTHER
}

struct MedicationMeta: Codable, Equatable {
    var medicationId: String
    var form: MedicationForm = .OTHER
    var quantity: Double? = nil
    var administrationSite: String = ""
    var packageCount: Int? = nil
    var packageUnit: String = ""
    var source: String = "manual"
    var doseUnitOverride: String = ""
}

struct CirclePeer: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var topic: String
}

struct DoseEvent: Codable, Equatable, Identifiable {
    static let protocolVersion = 9

    var id: String { eventId }
    var v: Int = protocolVersion
    var eventId: String
    var type: String
    var time: String
    var scheduledDate: String = ""
    var snoozeUntil: Int64 = 0
    var ownerId: String = ""
    var targetTopic: String = ""
    var actor: String = ""
    var actorTopic: String = ""
    var timestamp: Int64 = 0
    var revision: Int64 = 0
    var syncState: String = "pending"
    var medications: [Medication] = []
    var medicationMeta: [MedicationMeta] = []

    enum CodingKeys: String, CodingKey {
        case v, eventId, type, time, scheduledDate, snoozeUntil, ownerId, targetTopic
        case actor, actorTopic, timestamp, revision, syncState, medications, medicationMeta
    }
}

struct StockState: Codable, Equatable {
    var medicationId: String
    var medicationName: String
    var remainingDoses: Int
    var packSize: Int
    var lowThreshold: Int = 5
    var updatedAt: Int64
}

struct ProgramRule: Codable, Equatable {
    var medicationId: String
    var weekdays: Set<Int> = []
    var startDate: String? = nil
    var endDate: String? = nil
    var everyNDays: Int = 1
    var anchorDate: String? = nil
    var routineLabel: String = ""
}

struct SyncEnvelope: Codable, Equatable {
    var id: String?
    var time: Int64?
    var event: String
    var topic: String
    var message: String?
}
