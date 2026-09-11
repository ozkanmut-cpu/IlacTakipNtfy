import XCTest
@testable import Dosefolk

final class LocalStockEngineTests: XCTestCase {
    private func makeStore() throws -> LocalStore {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("dosefolk-stock-\(UUID().uuidString)", isDirectory: true)
        return try LocalStore(directory: url)
    }

    private func event(id: String, type: String, medication: Medication) -> DoseEvent {
        DoseEvent(
            eventId: id,
            type: type,
            time: "08:00",
            scheduledDate: "2026-09-11",
            ownerId: "local-topic",
            actorTopic: "local-topic",
            timestamp: 1_789_113_600_000,
            medications: [medication]
        )
    }

    func testReplayAndSameSessionDoNotDoubleConsume() async throws {
        let store = try makeStore()
        let engine = LocalStockEngine(store: store)
        let medication = Medication(id: "med-1", name: "Test", dose: "1", times: ["08:00"])
        try await engine.configure(StockState(
            medicationId: medication.id,
            medicationName: medication.name,
            remainingDoses: 10,
            packSize: 10,
            updatedAt: 1
        ))

        let first = event(id: "taken-1", type: "taken", medication: medication)
        _ = try await engine.apply(first)
        _ = try await engine.apply(first)
        _ = try await engine.apply(event(id: "taken-2", type: "taken", medication: medication))

        XCTAssertEqual(try await engine.all().first?.remainingDoses, 9)
    }

    func testUndoTakenRestoresOnlyOnce() async throws {
        let store = try makeStore()
        let engine = LocalStockEngine(store: store)
        let medication = Medication(id: "med-1", name: "Test", dose: "1", times: ["08:00"])
        try await engine.configure(StockState(
            medicationId: medication.id,
            medicationName: medication.name,
            remainingDoses: 10,
            packSize: 10,
            updatedAt: 1
        ))

        _ = try await engine.apply(event(id: "taken-1", type: "taken", medication: medication))
        _ = try await engine.apply(event(id: "undo-1", type: "undo_taken", medication: medication))
        _ = try await engine.apply(event(id: "undo-2", type: "undo_taken", medication: medication))

        XCTAssertEqual(try await engine.all().first?.remainingDoses, 10)
    }

    func testCountableMedicationUsesConfiguredQuantity() async throws {
        let store = try makeStore()
        let engine = LocalStockEngine(store: store)
        let medication = Medication(id: "med-1", name: "Test", dose: "2", times: ["08:00"])
        try store.save([
            MedicationMeta(medicationId: medication.id, form: .TABLET, quantity: 2)
        ], to: .medicationMeta)
        try await engine.configure(StockState(
            medicationId: medication.id,
            medicationName: medication.name,
            remainingDoses: 10,
            packSize: 10,
            updatedAt: 1
        ))

        _ = try await engine.apply(event(id: "taken-1", type: "taken", medication: medication))

        XCTAssertEqual(try await engine.all().first?.remainingDoses, 8)
    }
}
