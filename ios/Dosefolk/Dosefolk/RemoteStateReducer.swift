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

struct ScopedMedication: Codable, Equatable {
    var ownerId: String
    var medication: Medication
    var medicationMeta: [MedicationMeta]
}

struct ScopedProgramRule: Codable, Equatable {
    var ownerId: String
    var rule: ProgramRule
}

final class RemoteStateReducer {
    private let store: LocalStore
    private let localOwnerId: String
    private let securityState: CircleSecurityState
    private let capabilityGate: CapabilityEventGate
    private let notifications: DoseNotificationManaging

    init(
        store: LocalStore,
        localOwnerId: String,
        notifications: DoseNotificationManaging = DoseNotificationLifecycle()
    ) {
        self.store = store
        self.localOwnerId = localOwnerId
        self.securityState = CircleSecurityState(store: store)
        self.capabilityGate = CapabilityEventGate(store: store)
        self.notifications = notifications
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
            notifications.snooze(time: event.time, scheduledDate: event.scheduledDate, untilMillis: event.snoozeUntil)
        case "taken", "conflict_resolved_taken":
            status = "taken"
            snoozeUntil = 0
            notifications.resolve(time: event.time, scheduledDate: event.scheduledDate)
        default:
            status = "missed"
            snoozeUntil = 0
            notifications.resolve(time: event.time, scheduledDate: event.scheduledDate)
        }
        state[key] = DoseRuntimeEntry(status: status, snoozeUntil: snoozeUntil, eventId: event.eventId, revision: event.revision)
        try store.save(state, to: .doseRuntime)
    }

    private func applyProgram(_ event: DoseEvent) throws {
        guard let medication = event.medications.first, !medication.id.isEmpty else { return }
        let ownerId = resolvedOwner(event)
        guard !ownerId.isEmpty else { return }

        let orderingKey = "\(ownerId)|program|\(medication.id)"
        var ordering = try store.load([String: ProgramOrderingEntry].self, from: .programOrdering, default: [:])
        if let current = ordering[orderingKey], !wins(event, over: current) { return }

        if ownerId == localOwnerId {
            var medications = try store.load([Medication].self, from: .medications, default: [])
            if event.type == "program_deleted" {
                medications.removeAll { $0.id == medication.id }
            } else if let index = medications.firstIndex(where: { $0.id == medication.id }) {
                medications[index] = medication
            } else {
                medications.append(medication)
            }
            try store.save(medications, to: .medications)
            if !event.medicationMeta.isEmpty {
                var meta = try store.load([MedicationMeta].self, from: .medicationMeta, default: [])
                meta.removeAll { $0.medicationId == medication.id }
                meta.append(contentsOf: event.medicationMeta.filter { $0.medicationId == medication.id })
                try store.save(meta, to: .medicationMeta)
            }
        } else {
            var remote = try store.load([ScopedMedication].self, from: .remoteMedications, default: [])
            remote.removeAll { $0.ownerId == ownerId && $0.medication.id == medication.id }
            if event.type != "program_deleted" {
                remote.append(ScopedMedication(ownerId: ownerId, medication: medication, medicationMeta: event.medicationMeta))
            }
            try store.save(remote, to: .remoteMedications)
        }

        ordering[orderingKey] = orderEntry(event)
        try store.save(ordering, to: .programOrdering)
    }

    private func applyProgramRule(_ event: DoseEvent) throws {
        guard let carrier = event.medications.first, !carrier.id.isEmpty, !carrier.dose.isEmpty else { return }
        let rule = try ProgramRuleCodec.decode(carrier.dose)
        guard rule.medicationId == carrier.id else { return }
        let ownerId = resolvedOwner(event)
        guard !ownerId.isEmpty else { return }

        let orderingKey = "\(ownerId)|rule|\(carrier.id)"
        var ordering = try store.load([String: ProgramOrderingEntry].self, from: .programOrdering, default: [:])
        if let current = ordering[orderingKey], !wins(event, over: current) { return }

        if ownerId == localOwnerId {
            var rules = try store.load([ProgramRule].self, from: .programRules, default: [])
            rules.removeAll { $0.medicationId == carrier.id }
            rules.append(rule)
            try store.save(rules, to: .programRules)
        } else {
            var rules = try store.load([ScopedProgramRule].self, from: .remoteProgramRules, default: [])
            rules.removeAll { $0.ownerId == ownerId && $0.rule.medicationId == carrier.id }
            rules.append(ScopedProgramRule(ownerId: ownerId, rule: rule))
            try store.save(rules, to: .remoteProgramRules)
        }

        ordering[orderingKey] = orderEntry(event)
        try store.save(ordering, to: .programOrdering)
    }

    private func applyPresence(_ event: DoseEvent) throws {
        guard !event.actorTopic.isEmpty else { return }
        var presence = try store.load([String: CirclePresenceEntry].self, from: .circlePresence, default: [:])
        presence[event.actorTopic] = CirclePresenceEntry(
            actorTopic: event.actorTopic,
            lastSeenAt: max(event.timestamp, Int64(Date().timeIntervalSince1970 * 1000)),
            eventId: event.eventId
        )
        try store.save(presence, to: .circlePresence)
    }

    private func applyRemoteRevoke(_ event: DoseEvent) throws {
        guard event.targetTopic == localOwnerId else { return }
        let peerTopic = event.actorTopic
        guard !peerTopic.isEmpty, peerTopic != localOwnerId else { return }

        try securityState.revoke(actorTopic: peerTopic)
        try capabilityGate.clearPeer(peerTopic)

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
        guard try capabilityGate.accept(event) else { return }
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
        if event.timestamp != current.timestamp { return event.timestamp >= current.timestamp }
        if event.actorTopic != current.actorTopic { return event.actorTopic > current.actorTopic }
        return event.eventId > current.eventId
    }

    private func orderEntry(_ event: DoseEvent) -> ProgramOrderingEntry {
        ProgramOrderingEntry(revision: event.revision, actorTopic: event.actorTopic, eventId: event.eventId, timestamp: event.timestamp)
    }

    private func resolvedOwner(_ event: DoseEvent) -> String {
        event.ownerId.isEmpty ? event.actorTopic : event.ownerId
    }

    private func doseKey(_ event: DoseEvent) -> String {
        "\(resolvedOwner(event))|\(event.scheduledDate)|\(event.time)"
    }
}
