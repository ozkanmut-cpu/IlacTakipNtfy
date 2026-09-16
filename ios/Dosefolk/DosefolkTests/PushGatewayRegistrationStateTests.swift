import XCTest
@testable import Dosefolk

final class PushGatewayRegistrationStateTests: XCTestCase {
    func testRegistrationStatusTracksPersistentReprovisionState() {
        let suite = "PushGatewayRegistrationStateTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = AppSettings(defaults: defaults)

        settings.ntfyReprovisionRequired = false
        XCTAssertThrowsError(try PushGatewayClient.applyRegistrationStatus(409, settings: settings)) { error in
            XCTAssertEqual(error as? PushGatewayError, .rejected(409))
        }
        XCTAssertTrue(settings.ntfyReprovisionRequired)

        XCTAssertNoThrow(try PushGatewayClient.applyRegistrationStatus(204, settings: settings))
        XCTAssertFalse(settings.ntfyReprovisionRequired)

        settings.ntfyReprovisionRequired = true
        XCTAssertThrowsError(try PushGatewayClient.applyRegistrationStatus(503, settings: settings)) { error in
            XCTAssertEqual(error as? PushGatewayError, .rejected(503))
        }
        XCTAssertTrue(settings.ntfyReprovisionRequired)
    }
}
