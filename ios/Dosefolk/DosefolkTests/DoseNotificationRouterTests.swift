import XCTest
@testable import Dosefolk

final class DoseNotificationRouterTests: XCTestCase {
    func testActionIdentifiersMapOnlyKnownDoseActions() {
        XCTAssertEqual(DoseNotificationActionIdentifier.action(for: DoseNotificationActionIdentifier.taken), .taken)
        XCTAssertEqual(DoseNotificationActionIdentifier.action(for: DoseNotificationActionIdentifier.snooze), .snooze)
        XCTAssertEqual(DoseNotificationActionIdentifier.action(for: DoseNotificationActionIdentifier.missed), .missed)
        XCTAssertNil(DoseNotificationActionIdentifier.action(for: "UNKNOWN"))
    }

    func testPayloadParsesArrayAndCommaSeparatedMedicationIDs() throws {
        let arrayPayload = try XCTUnwrap(DoseNotificationPayload.parse([
            "time": "08:00",
            "scheduledDate": "2026-09-11",
            "medicationIDs": ["m1", "m2"]
        ]))
        XCTAssertEqual(arrayPayload.medicationIDs, ["m1", "m2"])

        let stringPayload = try XCTUnwrap(DoseNotificationPayload.parse([
            "time": "20:00",
            "scheduledDate": "2026-09-11",
            "medicationIDs": "m1,m2"
        ]))
        XCTAssertEqual(stringPayload.medicationIDs, ["m1", "m2"])
    }

    func testPayloadRejectsMissingSessionIdentity() {
        XCTAssertNil(DoseNotificationPayload.parse(["time": "08:00"]))
        XCTAssertNil(DoseNotificationPayload.parse(["scheduledDate": "2026-09-11"]))
    }
}
