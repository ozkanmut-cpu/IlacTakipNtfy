import Foundation

struct CapabilityOrderingEntry: Codable, Equatable {
    var revision: Int64
    var timestamp: Int64
    var actorTopic: String
    var eventId: String
}

struct CapabilityEventGate {
    private let store: LocalStore

    init(store: LocalStore) {
        self.store = store
    }

    func accept(_ event: DoseEvent) throws -> Bool {
        guard let permission = permission(for: event) else { return false }
        let owner = event.ownerId.isEmpty ? event.actorTopic : event.ownerId
        guard !owner.isEmpty else { return false }

        let key = "\(owner)|\(permission)"
        var ordering = try store.load([String: CapabilityOrderingEntry].self, from: .capabilityOrdering, default: [:])
        if let current = ordering[key], !wins(event, over: current) {
            return false
        }

        ordering[key] = CapabilityOrderingEntry(
            revision: event.revision,
            timestamp: event.timestamp,
            actorTopic: event.actorTopic,
            eventId: event.eventId
        )
        try store.save(ordering, to: .capabilityOrdering)
        return true
    }

    func clearPeer(_ topic: String) throws {
        guard !topic.isEmpty else { return }
        var ordering = try store.load([String: CapabilityOrderingEntry].self, from: .capabilityOrdering, default: [:])
        ordering = ordering.filter { !$0.key.hasPrefix("\(topic)|") }
        try store.save(ordering, to: .capabilityOrdering)
    }

    private func permission(for event: DoseEvent) -> String? {
        if event.type.hasPrefix("capability_edit_program_") { return "EDIT_PROGRAM" }
        if event.type.hasPrefix("capability_edit_stock_") { return "EDIT_STOCK" }
        return nil
    }

    private func wins(_ event: DoseEvent, over current: CapabilityOrderingEntry) -> Bool {
        if event.revision > 0 || current.revision > 0 {
            if event.revision != current.revision { return event.revision > current.revision }
            if event.actorTopic != current.actorTopic { return event.actorTopic > current.actorTopic }
            return event.eventId > current.eventId
        }
        if event.timestamp != current.timestamp { return event.timestamp >= current.timestamp }
        if event.actorTopic != current.actorTopic { return event.actorTopic > current.actorTopic }
        return event.eventId > current.eventId
    }
}
