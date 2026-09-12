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

    func testProvisioningRequestContainsOnlyInstallAndTicket() throws {
        let url = try XCTUnwrap(URL(string: "https://example.invalid/v1/provision"))
        let request = try PushGatewayClient.makeProvisioningRequest(
            installId: "install-1234",
            ticket: "ticket-1",
            url: url
        )

        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(request.timeoutInterval, 10)
        XCTAssertEqual(
            try JSONDecoder().decode(PushGatewayProvisionRequest.self, from: try XCTUnwrap(request.httpBody)),
            PushGatewayProvisionRequest(installId: "install-1234", ticket: "ticket-1")
        )
    }

    func testProvisioningResponseRequiresMatchingInstallAndCredential() throws {
        let valid = try JSONEncoder().encode(PushGatewayProvisionResponse(
            installId: "install-1234",
            credential: "credential-1"
        ))
        XCTAssertEqual(
            try PushGatewayClient.decodeProvisioningResponse(valid, expectedInstallId: "install-1234"),
            "credential-1"
        )

        let wrongInstall = try JSONEncoder().encode(PushGatewayProvisionResponse(
            installId: "install-other",
            credential: "credential-1"
        ))
        XCTAssertThrowsError(
            try PushGatewayClient.decodeProvisioningResponse(wrongInstall, expectedInstallId: "install-1234")
        ) { error in
            XCTAssertEqual(error as? PushGatewayError, .invalidResponse)
        }

        let emptyCredential = try JSONEncoder().encode(PushGatewayProvisionResponse(
            installId: "install-1234",
            credential: "   "
        ))
        XCTAssertThrowsError(
            try PushGatewayClient.decodeProvisioningResponse(emptyCredential, expectedInstallId: "install-1234")
        ) { error in
            XCTAssertEqual(error as? PushGatewayError, .invalidResponse)
        }
    }

    func testRegistrationRequestCarriesCredentialAndPayload() throws {
        let payload = APNsRegistrationPayload(
            installId: "install-1",
            deviceToken: "aabb",
            localTopic: "dosefolk-local",
            subscriptions: ["dosefolk-local", "dosefolk-peer"],
            appBundleId: "com.ozkanmut.dosefolk",
            environment: "sandbox"
        )
        let url = try XCTUnwrap(URL(string: "https://example.invalid/v1/register"))
        let request = try PushGatewayClient.makeRegistrationRequest(
            payload: payload,
            credential: "credential-1",
            url: url
        )

        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer credential-1")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(request.timeoutInterval, 10)
        XCTAssertEqual(try JSONDecoder().decode(APNsRegistrationPayload.self, from: try XCTUnwrap(request.httpBody)), payload)
    }

    func testSilentWakePayloadRecognition() {
        XCTAssertTrue(DosefolkAppDelegate.isWakePayload(["aps": ["content-available": 1]]))
        XCTAssertFalse(DosefolkAppDelegate.isWakePayload(["aps": ["alert": "x"]]))
        XCTAssertFalse(DosefolkAppDelegate.isWakePayload([:]))
    }
}
