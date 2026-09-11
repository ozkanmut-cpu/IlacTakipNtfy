import Foundation

struct CircleInitialSyncPublisher {
    typealias PublishEvent = (_ type: String, _ time: String, _ targetTopic: String, _ medications: [Medication], _ medicationMeta: [MedicationMeta]) async throws -> Void
    typealias PublishPresence = (_ targetTopic: String) async throws -> Void

    let store: LocalStore
    let publishPresence: PublishPresence
    let publishEvent: PublishEvent

    init(store: LocalStore, publisher: ProtocolEventPublisher, localTopic: String) {
        self.store = store
        let presence = CirclePresencePublisher(publisher: publisher, localTopic: localTopic)
        self.publishPresence = { target in
            try await presence.publish(to: target)
        }
        self.publishEvent = { type, time, target, medications, meta in
            _ = try await publisher.publish(
                type: type,
                time: time,
                targetTopic: target,
                medications: medications,
                medicationMeta: meta
            )
        }
    }

    init(store: LocalStore, publishPresence: @escaping PublishPresence, publishEvent: @escaping PublishEvent) {
        self.store = store
        self.publishPresence = publishPresence
        self.publishEvent = publishEvent
    }

    func publish(to targetTopic: String) async throws {
        let target = targetTopic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty else { return }

        try await publishPresence(target)
        let medications = try store.load([Medication].self, from: .medications, default: [])
        let allMeta = try store.load([MedicationMeta].self, from: .medicationMeta, default: [])
        let rules = try store.load([ProgramRule].self, from: .programRules, default: [])

        for medication in medications {
            let meta = allMeta.filter { $0.medicationId == medication.id }
            try await publishEvent("program_added", medication.times.first ?? "program", target, [medication], meta)

            let rule = rules.first(where: { $0.medicationId == medication.id }) ?? ProgramRule(medicationId: medication.id)
            let encodedRule = try ProgramRuleCodec.encode(rule)
            let carrier = Medication(id: medication.id, name: medication.name, dose: encodedRule, times: [])
            try await publishEvent("program_rule_updated", "program", target, [carrier], meta)
        }
    }
}
