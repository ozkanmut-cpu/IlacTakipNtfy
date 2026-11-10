import XCTest
@testable import Dosefolk

final class MedicationProgramServiceTests: XCTestCase {
    func testEditingWithoutOpeningDetailsPreservesStoredMetadataAndRule() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try LocalStore(directory: directory)
        let medication = Medication(id: "m", name: "Old name", dose: "custom note", times: ["07:00"])
        let meta = MedicationMeta(medicationId: "m", form: .DROP, quantity: 2, administrationSite: "sol göz",
                                  packageCount: 7, packageUnit: "şişe", source: "import", doseUnitOverride: "damla")
        let rule = ProgramRule(medicationId: "m", weekdays: [1, 3], startDate: "2026-09-01",
                               endDate: "2026-10-01", everyNDays: 2, anchorDate: "2026-09-01", routineLabel: "sabah")
        try store.save([medication], to: .medications)
        try store.save([meta], to: .medicationMeta)
        try store.save([rule], to: .programRules)

        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = AppSettings(defaults: defaults)
        settings.localTopic = "test-only"
        let gate = NtfyRateGate(defaults: defaults)
        gate.record429(retryAfter: "300")
        let publisher = ProtocolEventPublisher(settings: settings, store: store, client: NtfyClient(rateGate: gate))
        let service = MedicationProgramService(store: store, publisher: publisher)
        var edit = medication
        edit.name = "New name"
        do {
            try await service.upsert(medication: edit)
            XCTFail("The test transport is rate-limited")
        } catch NtfyClientError.blocked {}

        XCTAssertEqual(try store.load([Medication].self, from: .medications, default: []), [edit])
        XCTAssertEqual(try store.load([MedicationMeta].self, from: .medicationMeta, default: []), [meta])
        XCTAssertEqual(try store.load([ProgramRule].self, from: .programRules, default: []), [rule])
        let events = try store.load([DoseEvent].self, from: .doseEvents, default: [])
        XCTAssertEqual(events.last?.medicationMeta, [meta])
    }

    func testNormalizeTrimsAndDeduplicatesTimes() {
        let medication = Medication(
            id: "m1",
            name: "  Test  ",
            dose: " 1 tablet ",
            times: ["8:5", "08:05", "25:00", " 20:30 "]
        )

        let normalized = MedicationProgramService.normalize(medication)

        XCTAssertEqual(normalized.name, "Test")
        XCTAssertEqual(normalized.dose, "1 tablet")
        XCTAssertEqual(normalized.times, ["08:05", "20:30"])
    }

    func testNormalizeTimeRejectsInvalidValues() {
        XCTAssertNil(MedicationProgramService.normalizeTime("24:00"))
        XCTAssertNil(MedicationProgramService.normalizeTime("12:60"))
        XCTAssertNil(MedicationProgramService.normalizeTime("nope"))
        XCTAssertEqual(MedicationProgramService.normalizeTime("7:03"), "07:03")
    }
}
