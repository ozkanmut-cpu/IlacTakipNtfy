import XCTest
@testable import Dosefolk

final class DosefolkAppDelegateTokenRetryTests: XCTestCase {
    override func tearDown() {
        DosefolkAppDelegate.onDeviceToken = nil
        super.tearDown()
    }

    func testRetriesLatestDeviceTokenWithoutPersistingIt() {
        let token = Data([0x01, 0x02, 0x03, 0x04])
        var received: [Data] = []
        DosefolkAppDelegate.onDeviceToken = { received.append($0) }

        DosefolkAppDelegate.handleDeviceToken(token)
        DosefolkAppDelegate.retryDeviceTokenRegistration()

        XCTAssertEqual(received, [token, token])
        XCTAssertEqual(DosefolkAppDelegate.latestDeviceToken, token)
    }
}
