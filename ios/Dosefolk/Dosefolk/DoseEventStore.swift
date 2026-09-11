import Foundation

struct DoseEventStore {
    private let store: LocalStore

    init(store: LocalStore) {
        self.store = store
    }

    func all() throws -> [DoseEvent] {
        try store.load([DoseEvent].self, from: .doseEvents, default: [])
    }

    @discardableResult
    func appendIfAbsent(_ event: DoseEvent) throws -> DoseEvent {
        var events = try all()
        if let existing = events.first(where: { $0.eventId == event.eventId }) {
            return existing
        }
        var canonical = event
        canonical.syncState = "synced"
        events.append(canonical)
        try store.save(events, to: .doseEvents)
        return canonical
    }
}
