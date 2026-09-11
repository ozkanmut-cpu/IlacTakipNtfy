import Foundation

actor PrnDoseService {
    private let publisher: ProtocolEventPublisher
    private let stockEngine: LocalStockEngine
    private let onStockChanged: (() async throws -> Void)?
    private let now: () -> Date

    init(
        publisher: ProtocolEventPublisher,
        stockEngine: LocalStockEngine,
        onStockChanged: (() async throws -> Void)? = nil,
        now: @escaping () -> Date = Date.init
    ) {
        self.publisher = publisher
        self.stockEngine = stockEngine
        self.onStockChanged = onStockChanged
        self.now = now
    }

    @discardableResult
    func record(medication: Medication) async throws -> DoseEvent {
        let date = now()
        let event = try await publisher.publish(
            type: "prn_taken",
            time: Self.timeString(date),
            medications: [medication],
            scheduledDate: Self.dateString(date)
        )
        let changed = try await stockEngine.apply(event)
        if !changed.isEmpty {
            try await onStockChanged?()
        }
        return event
    }

    static func dateString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    static func timeString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}
