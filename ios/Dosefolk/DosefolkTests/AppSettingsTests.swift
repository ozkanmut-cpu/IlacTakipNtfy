import XCTest
@testable import Dosefolk

final class AppSettingsTests: XCTestCase {
    func testNtfyReprovisionRequirementPersists() {
        let suite = "AppSettingsTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let settings = AppSettings(defaults: defaults)
        XCTAssertFalse(settings.ntfyReprovisionRequired)

        settings.ntfyReprovisionRequired = true
        XCTAssertTrue(AppSettings(defaults: defaults).ntfyReprovisionRequired)

        settings.ntfyReprovisionRequired = false
        XCTAssertFalse(AppSettings(defaults: defaults).ntfyReprovisionRequired)
    }
}
