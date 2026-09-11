import Foundation

enum DoseUserAction: String, CaseIterable {
    case taken
    case snooze
    case missed
}

enum DoseActionResult: Equatable {
    case applied(DoseEvent)
    case ignored
}

actor DoseActionService {
    private let store: LocalStore
    private let publisher: ProtocolEventPublisher
    private let stockEngine: LocalStockEngine
    private let notifications: DoseNotificationManaging
    private let nowMillis: () -> Int64

    init(
        store: LocalStore,
        publisher: ProtocolEventPublisher,
        stockEngine: LocalStockEngine? = nil,
        notifications: DoseNotificationManaging = DoseNotificationLifecycle(),
        nowMillis: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.store = store
        self.publisher = publisher
        self.stockEngine = stockEngine ?? LocalStockEngine(store: store)
        self.notifications = notifications
        self.nowMillis = nowMillis
    }

    func apply(_ action: DoseUserAction, time: String, scheduledDate: String, medications: [Medication]) async throws -> DoseActionResult {
        let state = try DoseStateEngine(store: store).stateForTime(time, scheduledDate: scheduledDate)
        guard Self.shouldApply(action, to: state.status) else { return .ignored }

        let type: String
        let snoozeUntil: Int64
        switch action {
        case .taken:
            type = "taken"
            snoozeUntil = 0
        case .missed:
            type = "missed"
            snoozeUntil = 0
        case .snooze:
            type = "snoozed"
            snoozeUntil = nowMillis() + 30 * 60_000
        }

        let event = try await publisher.publish(
            type: type,
            time: time,
            medications: medications,
            scheduledDate: scheduledDate,
            snoozeUntil: snoozeUntil
        )
        _ = try await stockEngine.apply(event)
        if action == .snooze {
            notifications.snooze(time: time, scheduledDate: scheduledDate, untilMillis: snoozeUntil)
        } else {
            notifications.resolve(time: time, scheduledDate: scheduledDate)
        }
        return .applied(event)
    }

    static func shouldApply(_ action: DoseUserAction, to status: DoseSessionStatus) -> Bool {
        switch action {
        case .snooze:
            return status == .unknown || status == .pending
        case .taken, .missed:
            return status == .unknown || status == .pending || status == .snoozed
        }
    }
}
