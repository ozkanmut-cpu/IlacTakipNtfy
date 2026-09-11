import XCTest
@testable import Dosefolk

final class CircleInitialSyncPublisherTests: XCTestCase {
    func testPublishesPresenceProgramRuleAndStockBootstrap() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try LocalStore(directory: directory)
        let medication = Medication(id: "m1", name: "Drug", dose: "1", times: ["08:00", "20:00"])
        let meta = MedicationMeta(medicationId: "m1", form: .TABLET)
        let rule = ProgramRule(medicationId: "m1", weekdays: Set([1, 3, 5]), everyNDays: 1, routineLabel: "Routine")
        try store.save([medication], to: .medications)
        try store.save([meta], to: .medicationMeta)
        try store.save([rule], to: .programRules)

        var presenceTargets: [String] = []
        var published: [(String, String, String, [Medication], [MedicationMeta])] = []
        var stockTargets: [String] = []
        let sut = CircleInitialSyncPublisher(
            store: store,
            publishPresence: { presenceTargets.append($0) },
            publishEvent: { published.append(($0, $1, $2, $3, $4)) },
            publishStock: { stockTargets.append($0) }
        )

        try await sut.publish(to: "peer-topic")

        XCTAssertEqual(presenceTargets, ["peer-topic"])
        XCTAssertEqual(published.count, 2)
        XCTAssertEqual(published[0].0, "program_added")
        XCTAssertEqual(published[0].1, "08:00")
        XCTAssertEqual(published[0].2, "peer-topic")
        XCTAssertEqual(published[0].3, [medication])
        XCTAssertEqual(published[0].4, [meta])

        XCTAssertEqual(published[1].0, "program_rule_updated")
        let carrier = try XCTUnwrap(published[1].3.first)
        let decodedRule = try ProgramRuleCodec.decode(carrier.dose)
        XCTAssertEqual(decodedRule, rule)
        XCTAssertTrue(carrier.times.isEmpty)
        XCTAssertEqual(stockTargets, ["peer-topic"])
    }
}
