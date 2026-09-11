import XCTest
@testable import Dosefolk

final class AssistantSummaryTests: XCTestCase {
    private let medication = Medication(id: "m1", name: "Test", dose: "1", times: ["08:00"])

    func testConflictWinsOverDoseAndStock() {
        let today = [
            TodayDoseItem(time: "08:00", scheduledDate: "2026-09-11", medications: [medication], status: .pending),
            TodayDoseItem(time: "09:00", scheduledDate: "2026-09-11", medications: [medication], status: .conflict)
        ]
        let stock = [StockState(medicationId: "m1", medicationName: "Test", remainingDoses: 1, packSize: 10, lowThreshold: 5, updatedAt: 1)]
        XCTAssertEqual(AssistantSummaryBuilder.build(today: today, stock: stock).priority, .conflict)
    }

    func testPendingDoseWinsOverLowStock() {
        let today = [TodayDoseItem(time: "08:00", scheduledDate: "2026-09-11", medications: [medication], status: .pending)]
        let stock = [StockState(medicationId: "m1", medicationName: "Test", remainingDoses: 1, packSize: 10, lowThreshold: 5, updatedAt: 1)]
        XCTAssertEqual(AssistantSummaryBuilder.build(today: today, stock: stock).priority, .doseDue)
    }

    func testLowStockShownWhenNoDoseNeedsAction() {
        let today = [TodayDoseItem(time: "08:00", scheduledDate: "2026-09-11", medications: [medication], status: .taken)]
        let stock = [StockState(medicationId: "m1", medicationName: "Test", remainingDoses: 1, packSize: 10, lowThreshold: 5, updatedAt: 1)]
        XCTAssertEqual(AssistantSummaryBuilder.build(today: today, stock: stock).priority, .lowStock)
    }

    func testAllGoodWhenNothingNeedsAttention() {
        let today = [TodayDoseItem(time: "08:00", scheduledDate: "2026-09-11", medications: [medication], status: .taken)]
        XCTAssertEqual(AssistantSummaryBuilder.build(today: today, stock: []).priority, .allGood)
    }
}
