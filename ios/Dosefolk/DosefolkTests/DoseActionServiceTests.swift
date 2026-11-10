import XCTest
@testable import Dosefolk

final class DoseActionServiceTests: XCTestCase {
    func testSnoozeWritesTheFifteenMinuteDeadlineToTheLocalEvent() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try LocalStore(directory: directory)
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = AppSettings(defaults: defaults)
        settings.localTopic = "test-only"
        let gate = NtfyRateGate(defaults: defaults)
        gate.record429(retryAfter: "300")
        let publisher = ProtocolEventPublisher(settings: settings, store: store, client: NtfyClient(rateGate: gate))
        let service = DoseActionService(store: store, publisher: publisher, nowMillis: { 1_000 })
        let med = Medication(id: "m", name: "Test", dose: "", times: ["08:00"])
        do {
            _ = try await service.apply(.snooze, time: "08:00", scheduledDate: "2026-10-01", medications: [med])
            XCTFail("The test transport is rate-limited")
        } catch NtfyClientError.blocked {}

        let event = try XCTUnwrap(DoseEventStore(store: store).all().first)
        XCTAssertEqual(event.type, "snoozed")
        XCTAssertEqual(event.snoozeUntil, 901_000)
    }

    func testActionDeliveryGuardMatchesAndroid() {
        XCTAssertTrue(DoseActionService.shouldApply(.snooze, to: .unknown))
        XCTAssertTrue(DoseActionService.shouldApply(.snooze, to: .pending))
        XCTAssertFalse(DoseActionService.shouldApply(.snooze, to: .snoozed))
        XCTAssertFalse(DoseActionService.shouldApply(.snooze, to: .taken))
        XCTAssertFalse(DoseActionService.shouldApply(.snooze, to: .missed))
        XCTAssertFalse(DoseActionService.shouldApply(.snooze, to: .conflict))

        for status in [DoseSessionStatus.unknown, .pending, .snoozed] {
            XCTAssertTrue(DoseActionService.shouldApply(.taken, to: status))
            XCTAssertTrue(DoseActionService.shouldApply(.missed, to: status))
        }
        for status in [DoseSessionStatus.taken, .missed, .conflict] {
            XCTAssertFalse(DoseActionService.shouldApply(.taken, to: status))
            XCTAssertFalse(DoseActionService.shouldApply(.missed, to: status))
        }
    }
}
