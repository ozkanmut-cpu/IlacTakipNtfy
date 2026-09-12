import XCTest
@testable import Dosefolk

final class PushGatewayEnrollmentLinkTests: XCTestCase {
    func testParsesValidEnrollmentLink() throws {
        let url = try XCTUnwrap(URL(string: "dosefolk://enroll?installId=install-1234&ticket=ticket_ABC-123"))
        XCTAssertEqual(
            PushGatewayEnrollmentLink.parse(url),
            PushGatewayEnrollmentLink(installId: "install-1234", ticket: "ticket_ABC-123")
        )
    }

    func testRejectsWrongSchemeOrHost() throws {
        XCTAssertNil(PushGatewayEnrollmentLink.parse(try XCTUnwrap(URL(string: "https://enroll?installId=install-1234&ticket=x"))))
        XCTAssertNil(PushGatewayEnrollmentLink.parse(try XCTUnwrap(URL(string: "dosefolk://other?installId=install-1234&ticket=x"))))
    }

    func testRejectsInvalidInstallIdAndMissingTicket() throws {
        XCTAssertNil(PushGatewayEnrollmentLink.parse(try XCTUnwrap(URL(string: "dosefolk://enroll?installId=bad&ticket=x"))))
        XCTAssertNil(PushGatewayEnrollmentLink.parse(try XCTUnwrap(URL(string: "dosefolk://enroll?installId=install-1234"))))
    }
}
