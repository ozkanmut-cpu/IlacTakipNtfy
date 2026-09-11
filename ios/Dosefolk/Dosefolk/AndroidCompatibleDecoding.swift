import Foundation

private enum MedicationKeys: String, CodingKey {
    case id, name, dose, times
}

private enum MedicationMetaKeys: String, CodingKey {
    case medicationId, form, quantity, administrationSite, packageCount, packageUnit, source, doseUnitOverride
}

private enum CirclePeerKeys: String, CodingKey {
    case id, name, topic, canEdit
}

private enum DoseEventKeys: String, CodingKey {
    case v, eventId, type, time, scheduledDate, snoozeUntil, ownerId, targetTopic
    case actor, actorTopic, timestamp, revision, syncState, medications, medicationMeta
}

private enum SyncEnvelopeKeys: String, CodingKey {
    case id, time, event, topic, message
}

extension Medication {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: MedicationKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        dose = try c.decodeIfPresent(String.self, forKey: .dose) ?? ""
        times = try c.decodeIfPresent([String].self, forKey: .times) ?? []
    }
}

extension MedicationMeta {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: MedicationMetaKeys.self)
        medicationId = try c.decodeIfPresent(String.self, forKey: .medicationId) ?? ""
        form = try c.decodeIfPresent(MedicationForm.self, forKey: .form) ?? .OTHER
        quantity = try c.decodeIfPresent(Double.self, forKey: .quantity)
        administrationSite = try c.decodeIfPresent(String.self, forKey: .administrationSite) ?? ""
        packageCount = try c.decodeIfPresent(Int.self, forKey: .packageCount)
        packageUnit = try c.decodeIfPresent(String.self, forKey: .packageUnit) ?? ""
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? "manual"
        doseUnitOverride = try c.decodeIfPresent(String.self, forKey: .doseUnitOverride) ?? ""
    }
}

extension CirclePeer {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CirclePeerKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        topic = try c.decodeIfPresent(String.self, forKey: .topic) ?? ""
        canEdit = try c.decodeIfPresent(Bool.self, forKey: .canEdit) ?? false
    }
}

extension DoseEvent {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: DoseEventKeys.self)
        v = try c.decodeIfPresent(Int.self, forKey: .v) ?? Self.protocolVersion
        eventId = try c.decodeIfPresent(String.self, forKey: .eventId) ?? ""
        type = try c.decodeIfPresent(String.self, forKey: .type) ?? ""
        time = try c.decodeIfPresent(String.self, forKey: .time) ?? ""
        scheduledDate = try c.decodeIfPresent(String.self, forKey: .scheduledDate) ?? ""
        snoozeUntil = try c.decodeIfPresent(Int64.self, forKey: .snoozeUntil) ?? 0
        ownerId = try c.decodeIfPresent(String.self, forKey: .ownerId) ?? ""
        targetTopic = try c.decodeIfPresent(String.self, forKey: .targetTopic) ?? ""
        actor = try c.decodeIfPresent(String.self, forKey: .actor) ?? ""
        actorTopic = try c.decodeIfPresent(String.self, forKey: .actorTopic) ?? ""
        timestamp = try c.decodeIfPresent(Int64.self, forKey: .timestamp) ?? 0
        revision = try c.decodeIfPresent(Int64.self, forKey: .revision) ?? 0
        syncState = try c.decodeIfPresent(String.self, forKey: .syncState) ?? "pending"
        medications = try c.decodeIfPresent([Medication].self, forKey: .medications) ?? []
        medicationMeta = try c.decodeIfPresent([MedicationMeta].self, forKey: .medicationMeta) ?? []
    }
}

extension SyncEnvelope {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: SyncEnvelopeKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id)
        time = try c.decodeIfPresent(Int64.self, forKey: .time)
        event = try c.decodeIfPresent(String.self, forKey: .event) ?? ""
        topic = try c.decodeIfPresent(String.self, forKey: .topic) ?? ""
        message = try c.decodeIfPresent(String.self, forKey: .message)
    }
}
