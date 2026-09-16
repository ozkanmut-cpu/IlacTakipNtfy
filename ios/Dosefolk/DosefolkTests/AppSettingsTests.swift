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

    func testNtfyReprovisionStateChangePostsNotification() {
        let suite = "AppSettingsTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = AppSettings(defaults: defaults)
        let expectation = expectation(forNotification: .ntfyReprovisionStateDidChange, object: nil)

        settings.ntfyReprovisionRequired = true

        wait(for: [expectation], timeout: 1)
    }

    func testNtfyAccessFingerprintPersists() {
        let suite = "AppSettingsTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let settings = AppSettings(defaults: defaults)
        XCTAssertEqual(settings.ntfyAccessFingerprint, "")

        settings.ntfyAccessFingerprint = "dosefolk-local\ndosefolk-peer"
        XCTAssertEqual(
            AppSettings(defaults: defaults).ntfyAccessFingerprint,
            "dosefolk-local\ndosefolk-peer"
        )
    }
}
