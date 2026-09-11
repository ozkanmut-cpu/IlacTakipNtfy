import Foundation

enum DoseSessionStatus: String, Codable, Equatable {
    case unknown, pending, snoozed, taken, missed, conflict
}

struct DoseSessionState: Equatable {
    var time: String
    var status: DoseSessionStatus
    var latestEvent: DoseEvent?
    var medications: [Medication]
    var conflictEvents: [DoseEvent] = []
    var scheduledDate: String
}

struct DoseStateEngine {
    private let store: LocalStore
    private let calendar: Calendar

    init(store: LocalStore, calendar: Calendar = .current) {
        self.store = store
        self.calendar = calendar
    }

    func stateForTime(_ time: String, scheduledDate: String) throws -> DoseSessionState {
        let events = try DoseEventStore(store: store).all().filter {
            $0.time == time && eventDate($0, fallback: scheduledDate) == scheduledDate
        }
        let medications = try store.load([Medication].self, from: .medications, default: [])
            .filter { $0.times.contains(time) }
        return Self.reduce(time: time, events: events, scheduleMedications: medications, scheduledDate: scheduledDate)
    }

    static func reduce(time: String, events: [DoseEvent], scheduleMedications: [Medication], scheduledDate: String) -> DoseSessionState {
        let stateTypes: Set<String> = ["alarm", "snoozed", "taken", "missed", "undo_taken", "undo_missed", "conflict_resolved_taken", "conflict_resolved_missed"]
        guard !events.isEmpty else { return .init(time: time, status: .unknown, latestEvent: nil, medications: scheduleMedications, scheduledDate: scheduledDate) }
        let session = events.filter { stateTypes.contains($0.type) }
        guard !session.isEmpty else {
            let latest = events.max(by: eventLess)
            return .init(time: time, status: .unknown, latestEvent: latest, medications: meds(latest, fallback: scheduleMedications), scheduledDate: scheduledDate)
        }

        let resolutions = session.filter { $0.type == "conflict_resolved_taken" || $0.type == "conflict_resolved_missed" }
        if !resolutions.isEmpty { return reduceResolutions(time: time, session: session, resolutions: resolutions, fallback: scheduleMedications, scheduledDate: scheduledDate) }

        let latestByActor = Dictionary(grouping: session, by: \.actorTopic).values.compactMap { actorEvents in
            actorEvents.filter { ["taken", "missed", "undo_taken", "undo_missed"].contains($0.type) }.max(by: actorLess)
        }
        let effective = latestByActor.filter { $0.type != "undo_taken" && $0.type != "undo_missed" }
        if effective.isEmpty, let undo = latestByActor.filter({ $0.type.hasPrefix("undo_") }).max(by: eventLess) {
            return .init(time: time, status: .pending, latestEvent: undo, medications: meds(undo, fallback: scheduleMedications), scheduledDate: scheduledDate)
        }
        if !effective.isEmpty {
            let taken = effective.filter { $0.type == "taken" }
            let missed = effective.filter { $0.type == "missed" }
            if !taken.isEmpty && !missed.isEmpty {
                let conflicts = [taken.max(by: eventLess)!, missed.max(by: eventLess)!].sorted(by: eventLess)
                let latest = conflicts.last!
                return .init(time: time, status: .conflict, latestEvent: latest, medications: meds(latest, fallback: scheduleMedications), conflictEvents: conflicts, scheduledDate: scheduledDate)
            }
            let terminal = effective.max(by: eventLess)!
            return .init(time: time, status: terminal.type == "taken" ? .taken : .missed, latestEvent: terminal, medications: meds(terminal, fallback: scheduleMedications), scheduledDate: scheduledDate)
        }
        let latest = session.max(by: eventLess)!
        let status: DoseSessionStatus = latest.type == "snoozed" ? .snoozed : (["alarm", "undo_taken", "undo_missed"].contains(latest.type) ? .pending : .unknown)
        return .init(time: time, status: status, latestEvent: latest, medications: meds(latest, fallback: scheduleMedications), scheduledDate: scheduledDate)
    }

    private static func reduceResolutions(time: String, session: [DoseEvent], resolutions: [DoseEvent], fallback: [Medication], scheduledDate: String) -> DoseSessionState {
        let latestPerActor = Dictionary(grouping: resolutions, by: \.actorTopic).values.compactMap { $0.max(by: actorLess) }
        let maxRevision = latestPerActor.map(\.revision).max() ?? 0
        let top = latestPerActor.filter { $0.revision == maxRevision }
        let taken = top.filter { $0.type == "conflict_resolved_taken" }
        let missed = top.filter { $0.type == "conflict_resolved_missed" }
        if !taken.isEmpty && !missed.isEmpty {
            let conflicts = [taken.max { $0.eventId < $1.eventId }!, missed.max { $0.eventId < $1.eventId }!].sorted(by: eventLess)
            let latest = conflicts.last!
            return .init(time: time, status: .conflict, latestEvent: latest, medications: meds(latest, fallback: fallback), conflictEvents: conflicts, scheduledDate: scheduledDate)
        }
        let resolution = top.max { a, b in a.actorTopic == b.actorTopic ? a.eventId < b.eventId : a.actorTopic < b.actorTopic }!
        let laterUndo = session.filter { ($0.type == "undo_taken" || $0.type == "undo_missed") && $0.actorTopic == resolution.actorTopic }.max(by: actorLess)
        if let laterUndo, laterUndo.revision > resolution.revision {
            return .init(time: time, status: .pending, latestEvent: laterUndo, medications: meds(laterUndo, fallback: fallback), scheduledDate: scheduledDate)
        }
        return .init(time: time, status: resolution.type == "conflict_resolved_taken" ? .taken : .missed, latestEvent: resolution, medications: meds(resolution, fallback: fallback), scheduledDate: scheduledDate)
    }

    private func eventDate(_ event: DoseEvent, fallback: String) -> String { event.scheduledDate.isEmpty ? fallback : event.scheduledDate }
    private static func meds(_ event: DoseEvent?, fallback: [Medication]) -> [Medication] { guard let event, !event.medications.isEmpty else { return fallback }; return event.medications }
    private static func actorLess(_ a: DoseEvent, _ b: DoseEvent) -> Bool { a.revision == b.revision ? a.eventId < b.eventId : a.revision < b.revision }
    private static func eventLess(_ a: DoseEvent, _ b: DoseEvent) -> Bool { a.revision == b.revision ? (a.actorTopic == b.actorTopic ? a.eventId < b.eventId : a.actorTopic < b.actorTopic) : a.revision < b.revision }
}
