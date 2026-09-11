import Foundation

enum DoseCorrectionIntent: Equatable {
    case undo
    case correctToTaken
    case correctToMissed
}

enum DoseCorrectionPlan: Equatable {
    case noChange
    case unavailable
    case publish(type: String, medications: [Medication])
}

enum DoseCorrectionResult: Equatable {
    case noChange
    case unavailable
    case applied(DoseEvent)
}

enum DoseCorrectionPolicy {
    static func plan(intent: DoseCorrectionIntent, state: DoseSessionState) -> DoseCorrectionPlan {
        switch intent {
        case .undo:
            let type: String
            switch state.status {
            case .taken: type = "undo_taken"
            case .missed: type = "undo_missed"
            default: return .unavailable
            }
            guard !state.medications.isEmpty else { return .unavailable }
            return .publish(type: type, medications: state.medications)

        case .correctToTaken:
            if state.status == .taken { return .noChange }
            guard !state.medications.isEmpty else { return .unavailable }
            return .publish(type: "conflict_resolved_taken", medications: state.medications)

        case .correctToMissed:
            if state.status == .missed { return .noChange }
            guard !state.medications.isEmpty else { return .unavailable }
            return .publish(type: "conflict_resolved_missed", medications: state.medications)
        }
    }
}

actor DoseCorrectionService {
    private let store: LocalStore
    private let publisher: ProtocolEventPublisher

    init(store: LocalStore, publisher: ProtocolEventPublisher) {
        self.store = store
        self.publisher = publisher
    }

    func apply(_ intent: DoseCorrectionIntent, time: String, scheduledDate: String) async throws -> DoseCorrectionResult {
        let state = try DoseStateEngine(store: store).stateForTime(time, scheduledDate: scheduledDate)
        switch DoseCorrectionPolicy.plan(intent: intent, state: state) {
        case .noChange:
            return .noChange
        case .unavailable:
            return .unavailable
        case let .publish(type, medications):
            let event = try await publisher.publish(
                type: type,
                time: time,
                medications: medications,
                scheduledDate: scheduledDate
            )
            return .applied(event)
        }
    }
}
