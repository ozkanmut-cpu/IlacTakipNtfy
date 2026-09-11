import XCTest
@testable import Dosefolk

final class CirclePairingPayloadTests: XCTestCase {
    func testEncodeAndParseMatchesAndroidFormat() throws {
        let encoded = try XCTUnwrap(CirclePairingPayload.encode(topic: "dosefolk-abc12345", name: "Özkan Mut"))
        XCTAssertTrue(encoded.hasPrefix("dosefolk://pair?"))

        let parsed = try XCTUnwrap(CirclePairingPayload.parse(encoded))
        XCTAssertEqual(parsed.topic, "dosefolk-abc12345")
        XCTAssertEqual(parsed.name, "Özkan Mut")
    }

    func testParsesLegacyTopicCode() throws {
        let parsed = try XCTUnwrap(CirclePairingPayload.parse("  dosefolk-legacy123  "))
        XCTAssertEqual(parsed.topic, "dosefolk-legacy123")
        XCTAssertEqual(parsed.name, "")
    }

    func testRejectsMissingTopicAndUnrelatedQr() {
        XCTAssertNil(CirclePairingPayload.parse("dosefolk://pair?name=Someone"))
        XCTAssertNil(CirclePairingPayload.parse("https://example.com"))
    }
}
