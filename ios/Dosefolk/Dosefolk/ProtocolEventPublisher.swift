import Foundation

actor ProtocolEventPublisher {
    private let settings: AppSettings
    private let store: LocalStore
    private let client: NtfyClient

    init(settings: AppSettings, store: LocalStore, client: NtfyClient = NtfyClient()) {
        self.settings = settings
        self.store = store
        self.client = client
    }

    @discardableResult
    func publish(
        type: String,
        time: String,
        targetTopic: String = "",
        medications: [Medication] = [],
        medicationMeta: [MedicationMeta] = [],
        scheduledDate: String = "",
        snoozeUntil: Int64 = 0
    ) async throws -> DoseEvent {
        let topic = settings.localTopic.trimmingCharacters(in: .whitespacesAndNewlines)
        precondition(!topic.isEmpty, "localTopic must be configured")

        let event = DoseEvent(
            eventId: UUID().uuidString,
            type: type,
            time: time,
            scheduledDate: scheduledDate,
            snoozeUntil: snoozeUntil,
            ownerId: topic,
            targetTopic: targetTopic,
            actor: settings.displayName,
            actorTopic: topic,
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            revision: try nextRevision(),
            syncState: "synced",
            medications: medications,
            medicationMeta: medicationMeta
        )
        let data = try JSONEncoder().encode(event)
        guard let body = String(data: data, encoding: .utf8) else {
            throw EncodingError.invalidValue(event, .init(codingPath: [], debugDescription: "Unable to encode event as UTF-8"))
        }
        _ = try DoseEventStore(store: store).appendIfAbsent(event)
        try await client.publish(topic: topic, body: body)
        return event
    }

    private func nextRevision() throws -> Int64 {
        var value = try store.load(Int64.self, from: .localRevision, default: 0)
        value += 1
        try store.save(value, to: .localRevision)
        return value
    }
}
