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

    func testSharedPairingFixtureMatchesIOSCodec() throws {
        let fixture = try JSONDecoder().decode(
            PairingFixture.self,
            from: Data(contentsOf: try fixtureURL("circle-pairing-v1"))
        )

        for testCase in fixture.cases {
            let parsed = CirclePairingPayload.parse(testCase.raw)
            if !testCase.valid {
                XCTAssertNil(parsed, "\(testCase.id) must be rejected")
                continue
            }

            let accepted = try XCTUnwrap(parsed, "\(testCase.id) must be accepted")
            XCTAssertEqual(accepted.topic, testCase.topic, "\(testCase.id) topic")
            XCTAssertEqual(accepted.name, testCase.name, "\(testCase.id) name")

            if testCase.canonicalEncode == true {
                XCTAssertEqual(
                    CirclePairingPayload.encode(topic: accepted.topic, name: accepted.name),
                    testCase.raw,
                    "\(testCase.id) canonical encoding"
                )
            }
        }
    }

    private func fixtureURL(_ name: String) throws -> URL {
        let bundle = Bundle(for: Self.self)
        if let direct = bundle.url(forResource: name, withExtension: "json") {
            return direct
        }
        if let resourceURL = bundle.resourceURL,
           let enumerator = FileManager.default.enumerator(
                at: resourceURL,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
           ) {
            for case let url as URL in enumerator where url.lastPathComponent == "\(name).json" {
                return url
            }
        }
        throw NSError(
            domain: "DosefolkTests.PairingFixture",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "Fixture \(name).json not found in test bundle"]
        )
    }
}

private struct PairingFixture: Decodable {
    let cases: [PairingFixtureCase]
}

private struct PairingFixtureCase: Decodable {
    let id: String
    let raw: String
    let valid: Bool
    let topic: String?
    let name: String?
    let canonicalEncode: Bool?
}
