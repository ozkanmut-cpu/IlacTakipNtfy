import XCTest
@testable import Dosefolk

final class APNsRegistrationTests: XCTestCase {
    func testDeviceTokenHexEncodingIsStable() {
        XCTAssertEqual(APNsRegistration.tokenHex(Data([0x00, 0x01, 0xab, 0xff])), "0001abff")
    }

    func testRegistrationPayloadDeduplicatesSubscriptions() {
        let payload = APNsRegistration.makePayload(
            token: Data([0xaa, 0xbb]),
            installId: "install-1",
            localTopic: "dosefolk-local",
            subscriptions: ["dosefolk-peer", "dosefolk-local", "dosefolk-peer"],
            bundleId: "com.ozkanmut.dosefolk"
        )
        XCTAssertEqual(payload.installId, "install-1")
        XCTAssertEqual(payload.deviceToken, "aabb")
        XCTAssertEqual(payload.subscriptions, ["dosefolk-local", "dosefolk-peer"])
    }

    func testSilentWakePayloadRecognition() {
        XCTAssertTrue(DosefolkAppDelegate.isWakePayload(["aps": ["content-available": 1]]))
        XCTAssertFalse(DosefolkAppDelegate.isWakePayload(["aps": ["alert": "x"]]))
        XCTAssertFalse(DosefolkAppDelegate.isWakePayload([:]))
    }
}
