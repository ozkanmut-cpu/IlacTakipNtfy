import Foundation

struct DoseRuntimeEntry: Codable, Equatable {
    var status: String
    var snoozeUntil: Int64
    var eventId: String
    var revision: Int64
}

struct ProgramOrderingEntry: Codable, Equatable {
    var revision: Int64
    var actorTopic: String
    var eventId: String
    var timestamp: Int64
}

struct CirclePresenceEntry: Codable, Equatable {
    var actorTopic: String
    var lastSeenAt: Int64
    var eventId: String
}

struct RemoteCapabilityState: Codable, Equatable {
    var editProgram: Set<String> = []
    var editStock: Set<String> = []
}

final class RemoteStateReducer {
    private let store: LocalStore
    private let localOwnerId: String
    private let securityState: CircleSecurityState

    init(store: LocalStore, localOwnerId: String) {
        self.store = store
        self.localOwnerId = localOwnerId
        self.securityState = CircleSecurityState(store: store)
    }

    func apply(_ event: DoseEvent) throws {
        switch event.type {
        case "taken", "missed", "conflict_resolved_taken", "conflict_resolved_missed", "snoozed":
            try applyDoseState(event)
        case "program_added", "program_updated", "program_deleted":
            try applyProgram(event)
        case "program_rule_updated":
            try applyProgramRule(event)
        case "circle_presence":
            try applyPresence(event)
        case "circle_revoked":
            try applyRemoteRevoke(event)
        case "capability_edit_program_granted", "capability_edit_program_revoked",
             "capability_edit_stock_granted", "capability_edit_stock_revoked":
            try applyCapability(event)
        default:
            break
        }
    }

    private func applyDoseState(_ event: DoseEvent) throws {
        let key = doseKey(event)
        var state = try store.load([String: DoseRuntimeEntry].self, from: .doseRuntime, default: [:])
        let status: String
        let snoozeUntil: Int64
        switch event.type {
        case "snoozed":
            status = "snoozed"
            snoozeUntil = event.snoozeUntil
        case "taken", "conflict_resolved_taken":
            status = "taken"
            snoozeUntil = 0
        default:
            status = "missed"
            snoozeUntil = 0
        }
        state[key] = DoseRuntimeEntry(
            status: status,
            snoozeUntil: snoozeUntil,
            eventId: event.eventId,
            revision: event.revision
        )
        try store.save(state, to: .doseRuntime)
    }

    private func applyProgram(_ event: DoseEvent) throws {
        guard let medication = event.medications.first, !medication.id.isEmpty else { return }
        let ownerId = event.ownerId.isEmpty ? event.actorTopic : event.ownerId
        guard !ownerId.isEmpty else { return }

        let orderingKey = "\(ownerId)|\(medication.id)"
        var ordering = try store.load([String: ProgramOrderingEntry].self, from: .programOrdering, default: [:])
        if let current = ordering[orderingKey], !wins(event, over: current) { return }

        if ownerId == localOwnerId {
            var medications = try store.load([Medication].self, from: .medications, default: [])
            switch event.type {
            case "program_deleted":
                medications.removeAll { $0.id == medication.id }
            default:
                if let index = medications.firstIndex(where: { $0.id == medication.id }) {
                    medications[index] = medication
                } else {
                    medications.append(medication)
                }
            }
            try store.save(medications, to: .medications)
        }

        ordering[orderingKey] = ProgramOrderingEntry(
            revision: event.revision,
            actorTopic: event.actorTopic,
            eventId: event.eventId,
            timestamp: event.timestamp
        )
        try store.save(ordering, to: .programOrdering)
    }

    private func applyProgramRule(_ event: DoseEvent) throws {
        guard let medication = event.medications.first, !medication.id.isEmpty else { return }
        var rules = try store.load([ProgramRule].self, from: .programRules, default: [])
        let rule = ProgramRule(medicationId: medication.id, times: medication.times, enabled: true)
        if let index = rules.firstIndex(where: { $0.medicationId == medication.id }) {
            rules[index] = rule
        } else {
            rules.append(rule)
        }
        try store.save(rules, to: .programRules)
    }

    private func applyPresence(_ event: DoseEvent) throws {
        guard !event.actorTopic.isEmpty else { return }
        var presence = try store.load([String: CirclePresenceEntry].self, from: .circlePresence, default: [:])
        presence[event.actorTopic] = CirclePresenceEntry(
            actorTopic: event.actorTopic,
            lastSeenAt: event.timestamp,
            eventId: event.eventId
        )
        try store.save(presence, to: .circlePresence)
    }

    private func applyRemoteRevoke(_ event: DoseEvent) throws {
        guard event.targetTopic == localOwnerId else { return }
        let peerTopic = event.actorTopic
        guard !peerTopic.isEmpty, peerTopic != localOwnerId else { return }

        try securityState.revoke(actorTopic: peerTopic)

        var peers = try store.load([CirclePeer].self, from: .circlePeers, default: [])
        peers.removeAll { $0.topic == peerTopic }
        try store.save(peers, to: .circlePeers)

        var presence = try store.load([String: CirclePresenceEntry].self, from: .circlePresence, default: [:])
        presence.removeValue(forKey: peerTopic)
        try store.save(presence, to: .circlePresence)

        var capabilities = try store.load(RemoteCapabilityState.self, from: .remoteCapabilities, default: RemoteCapabilityState())
        capabilities.editProgram.remove(peerTopic)
        capabilities.editStock.remove(peerTopic)
        try store.save(capabilities, to: .remoteCapabilities)
    }

    private func applyCapability(_ event: DoseEvent) throws {
        guard !event.actorTopic.isEmpty else { return }
        var state = try store.load(RemoteCapabilityState.self, from: .remoteCapabilities, default: RemoteCapabilityState())
        switch event.type {
        case "capability_edit_program_granted": state.editProgram.insert(event.actorTopic)
        case "capability_edit_program_revoked": state.editProgram.remove(event.actorTopic)
        case "capability_edit_stock_granted": state.editStock.insert(event.actorTopic)
        case "capability_edit_stock_revoked": state.editStock.remove(event.actorTopic)
        default: break
        }
        try store.save(state, to: .remoteCapabilities)
    }

    private func wins(_ event: DoseEvent, over current: ProgramOrderingEntry) -> Bool {
        if event.revision > 0 || current.revision > 0 {
            if event.revision != current.revision { return event.revision > current.revision }
            if event.actorTopic != current.actorTopic { return event.actorTopic > current.actorTopic }
            return event.eventId > current.eventId
        }
        return event.timestamp >= current.timestamp
    }

    private func doseKey(_ event: DoseEvent) -> String {
        let ownerId = event.ownerId.isEmpty ? event.actorTopic : event.ownerId
        return "\(ownerId)|\(event.scheduledDate)|\(event.time)"
    }
}
