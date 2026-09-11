import XCTest
@testable import Dosefolk

final class APNsRegistrationTests: XCTestCase {
    func testDeviceTokenHexEncodingIsStable() {
        XCTAssertEqual(APNsRegistration.tokenHex(Data([0x00, 0x01, 0xab, 0xff])), "0001abff")
    }

    func testSilentWakePayloadRecognition() {
        XCTAssertTrue(DosefolkAppDelegate.isWakePayload(["aps": ["content-available": 1]]))
        XCTAssertFalse(DosefolkAppDelegate.isWakePayload(["aps": ["alert": "x"]]))
        XCTAssertFalse(DosefolkAppDelegate.isWakePayload([:]))
    }
}
