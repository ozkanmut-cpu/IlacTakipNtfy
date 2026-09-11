import Foundation

struct LocalStockRuntime: Codable, Equatable {
    var stocks: [StockState] = []
    var processedEventIDs: Set<String> = []
    var consumedSessionKeys: Set<String> = []
    var restoredSessionKeys: Set<String> = []
}

actor LocalStockEngine {
    private let store: LocalStore

    init(store: LocalStore) {
        self.store = store
    }

    @discardableResult
    func apply(_ event: DoseEvent) throws -> [StockState] {
        let supported: Set<String> = [
            "taken", "prn_taken", "undo_taken",
            "conflict_resolved_taken", "conflict_resolved_missed"
        ]
        guard supported.contains(event.type), !event.eventId.isEmpty else { return [] }

        var runtime = try loadRuntime()
        guard !runtime.processedEventIDs.contains(event.eventId) else { return [] }

        let shouldBeConsumed = ["taken", "prn_taken", "conflict_resolved_taken"].contains(event.type)
        let metadata = try store.load([MedicationMeta].self, from: .medicationMeta, default: [])
        let metadataByID = Dictionary(uniqueKeysWithValues: metadata.map { ($0.medicationId, $0) })
        var stockByID = Dictionary(uniqueKeysWithValues: runtime.stocks.map { ($0.medicationId, $0) })
        var changed: [StockState] = []

        for medication in uniqueMedications(event.medications) {
            guard var stock = stockByID[medication.id] else { continue }
            let key = consumptionKey(event: event, medicationID: medication.id)
            let isConsumed = runtime.consumedSessionKeys.contains(key)
            let isRestored = runtime.restoredSessionKeys.contains(key)

            if shouldBeConsumed {
                guard !isConsumed else { continue }
                runtime.consumedSessionKeys.insert(key)
                runtime.restoredSessionKeys.remove(key)
            } else {
                guard !isRestored else { continue }
                runtime.consumedSessionKeys.remove(key)
                runtime.restoredSessionKeys.insert(key)
            }

            let units = consumptionUnits(metadataByID[medication.id])
            if shouldBeConsumed {
                stock.remainingDoses = max(0, stock.remainingDoses - units)
            } else {
                stock.remainingDoses += units
            }
            stock.updatedAt = event.timestamp
            stockByID[medication.id] = stock
            changed.append(stock)
        }

        runtime.processedEventIDs.insert(event.eventId)
        runtime.stocks = Array(stockByID.values).sorted { $0.medicationId < $1.medicationId }
        try persist(runtime)
        return changed
    }

    func configure(_ stock: StockState) throws {
        var runtime = try loadRuntime()
        runtime.stocks.removeAll { $0.medicationId == stock.medicationId }
        runtime.stocks.append(sanitize(stock))
        runtime.stocks.sort { $0.medicationId < $1.medicationId }
        try persist(runtime)
    }

    @discardableResult
    func openNewBox(medicationID: String, nowMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> StockState? {
        var runtime = try loadRuntime()
        guard let index = runtime.stocks.firstIndex(where: { $0.medicationId == medicationID }) else { return nil }
        var stock = runtime.stocks[index]
        guard stock.packSize > 0 else { return nil }
        stock.remainingDoses = max(0, stock.remainingDoses) + stock.packSize
        stock.updatedAt = nowMillis
        runtime.stocks[index] = stock
        try persist(runtime)
        return stock
    }

    func stock(for medicationID: String) throws -> StockState? {
        try loadRuntime().stocks.first { $0.medicationId == medicationID }
    }

    func all() throws -> [StockState] {
        try loadRuntime().stocks
    }

    private func loadRuntime() throws -> LocalStockRuntime {
        let fallbackStocks = try store.load([StockState].self, from: .stock, default: [])
        return try store.load(
            LocalStockRuntime.self,
            from: .stockRuntime,
            default: LocalStockRuntime(stocks: fallbackStocks)
        )
    }

    private func persist(_ runtime: LocalStockRuntime) throws {
        try store.save(runtime, to: .stockRuntime)
        try store.save(runtime.stocks, to: .stock)
    }

    private func sanitize(_ stock: StockState) -> StockState {
        var value = stock
        value.remainingDoses = max(0, value.remainingDoses)
        value.packSize = max(0, value.packSize)
        value.lowThreshold = max(0, value.lowThreshold)
        return value
    }

    private func uniqueMedications(_ medications: [Medication]) -> [Medication] {
        var seen = Set<String>()
        return medications.filter { seen.insert($0.id).inserted }
    }

    private func consumptionKey(event: DoseEvent, medicationID: String) -> String {
        if event.type == "prn_taken" {
            return "prn|\(event.eventId)|\(medicationID)"
        }
        return "dose|\(event.scheduledDate)|\(event.time)|\(medicationID)"
    }

    private func consumptionUnits(_ metadata: MedicationMeta?) -> Int {
        guard let metadata else { return 1 }
        let countable: Set<MedicationForm> = [.TABLET, .INSULIN, .NEBULE, .INHALER, .DROP, .PATCH]
        guard countable.contains(metadata.form) else { return 1 }
        let quantity = metadata.quantity ?? 1
        return max(1, Int(quantity.rounded()))
    }
}
