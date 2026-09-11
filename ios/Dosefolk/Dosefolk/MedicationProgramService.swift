import Foundation

actor MedicationProgramService {
    private let store: LocalStore
    private let publisher: ProtocolEventPublisher
    private let onProgramChanged: (() async throws -> Void)?

    init(
        store: LocalStore,
        publisher: ProtocolEventPublisher,
        onProgramChanged: (() async throws -> Void)? = nil
    ) {
        self.store = store
        self.publisher = publisher
        self.onProgramChanged = onProgramChanged
    }

    @discardableResult
    func upsert(
        medication: Medication,
        meta: MedicationMeta? = nil,
        rule: ProgramRule? = nil
    ) async throws -> Medication {
        let normalizedMedication = Self.normalize(medication)
        guard !normalizedMedication.name.isEmpty, !normalizedMedication.times.isEmpty else {
            throw CocoaError(.validationMissingMandatoryProperty)
        }

        var medications = try store.load([Medication].self, from: .medications, default: [])
        let existed = medications.contains { $0.id == normalizedMedication.id }
        medications.removeAll { $0.id == normalizedMedication.id }
        medications.append(normalizedMedication)
        medications.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        try store.save(medications, to: .medications)

        var metas = try store.load([MedicationMeta].self, from: .medicationMeta, default: [])
        metas.removeAll { $0.medicationId == normalizedMedication.id }
        if var meta {
            meta.medicationId = normalizedMedication.id
            metas.append(meta)
        }
        try store.save(metas, to: .medicationMeta)

        let normalizedRule = ProgramRuleCodec.normalize(rule ?? ProgramRule(medicationId: normalizedMedication.id))
        var rules = try store.load([ProgramRule].self, from: .programRules, default: [])
        rules.removeAll { $0.medicationId == normalizedMedication.id }
        rules.append(normalizedRule)
        try store.save(rules, to: .programRules)

        _ = try await publisher.publish(
            type: existed ? "program_updated" : "program_added",
            time: normalizedMedication.times.first ?? "program",
            medications: [normalizedMedication],
            medicationMeta: meta.map { [$0] } ?? []
        )

        let encodedRule = try ProgramRuleCodec.encode(normalizedRule)
        let carrier = Medication(
            id: normalizedMedication.id,
            name: normalizedMedication.name,
            dose: encodedRule,
            times: []
        )
        _ = try await publisher.publish(
            type: "program_rule_updated",
            time: "program",
            medications: [carrier]
        )
        try await onProgramChanged?()
        return normalizedMedication
    }

    func delete(medicationID: String) async throws {
        let id = medicationID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }

        var medications = try store.load([Medication].self, from: .medications, default: [])
        guard let removed = medications.first(where: { $0.id == id }) else { return }
        medications.removeAll { $0.id == id }
        try store.save(medications, to: .medications)

        var metas = try store.load([MedicationMeta].self, from: .medicationMeta, default: [])
        metas.removeAll { $0.medicationId == id }
        try store.save(metas, to: .medicationMeta)

        var rules = try store.load([ProgramRule].self, from: .programRules, default: [])
        rules.removeAll { $0.medicationId == id }
        try store.save(rules, to: .programRules)

        _ = try await publisher.publish(
            type: "program_deleted",
            time: "program",
            medications: [removed]
        )
        try await onProgramChanged?()
    }

    static func normalize(_ medication: Medication) -> Medication {
        var value = medication
        value.name = value.name.trimmingCharacters(in: .whitespacesAndNewlines)
        value.dose = value.dose.trimmingCharacters(in: .whitespacesAndNewlines)
        value.times = Array(Set(value.times.compactMap(normalizeTime))).sorted()
        return value
    }

    static func normalizeTime(_ value: String) -> String? {
        let parts = value.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ":")
        guard parts.count == 2,
              let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        return String(format: "%02d:%02d", hour, minute)
    }
}
