import XCTest
@testable import Dosefolk

/// Shared-byte/schema and CryptoKit Ed25519 contract only; NOT device HPKE/APNs/E2E coverage.
final class RelayProtocolTests: XCTestCase {
    func testSharedEnvelopeCanonicalBytesAndEd25519Signature() throws {
        let fixture = try load("relay-envelope-v1")
        XCTAssertEqual(fixture["testOnly"] as? Bool, true)
        let inner = try text(fixture, "canonicalInner")
        let context = try text(fixture, "canonicalContext")
        let signingKey = try text(fixture, "senderSigningPublicKey")
        let verified = try RelayProtocol.verifyEnvelope(Data(inner.utf8), senderSigningPublicKey: signingKey,
            expectedContext: Data(context.utf8))
        XCTAssertEqual(verified.canonicalPayload, try text(fixture, "canonicalPayload"))
        XCTAssertEqual(verified.signingInput, Data(try text(fixture, "signingInput").utf8))
        XCTAssertEqual(try RelayProtocol.hpkeContextInfo(Data(context.utf8)), Data(try text(fixture, "hpkeContextInfo").utf8))
    }

    func testSharedCanonicalNumbersUnicodeAndEscapingAreByteExact() throws {
        let fixture = try load("relay-envelope-v1")
        let cases = try XCTUnwrap(fixture["canonicalCases"] as? [[String: Any]])
        for item in cases {
            let canonical = try text(item, "canonical")
            XCTAssertEqual(try RelayProtocol.canonicalJSON(Data(canonical.utf8)), Data(canonical.utf8))
        }
    }

    func testAllSixChangedContextFieldsAreRejected() throws {
        let fixture = try load("relay-envelope-v1")
        let inner = try text(fixture, "canonicalInner")
        let context = try text(fixture, "canonicalContext")
        let mutations = [
            ("TEST-ONLY-message-001", "TEST-ONLY-message-002"),
            ("TEST-ONLY-route-001", "TEST-ONLY-route-002"),
            ("TEST-ONLY-sender", "TEST-ONLY-other-sender"),
            ("TEST-ONLY-recipient", "TEST-ONLY-other-recipient"),
            ("\"senderKeyVersion\":7", "\"senderKeyVersion\":8"),
            ("\"recipientKeyVersion\":11", "\"recipientKeyVersion\":12")
        ]
        for (old, new) in mutations {
            XCTAssertThrowsError(try RelayProtocol.verifyEnvelope(Data(inner.utf8),
                senderSigningPublicKey: text(fixture, "senderSigningPublicKey"),
                expectedContext: Data(context.replacingOccurrences(of: old, with: new).utf8)))
        }
    }

    func testWrongSenderPayloadMutationAndMalformedEnvelopeAreRejected() throws {
        let fixture = try load("relay-envelope-v1")
        let inner = try text(fixture, "canonicalInner")
        let context = Data(try text(fixture, "canonicalContext").utf8)
        let key = try text(fixture, "senderSigningPublicKey")
        XCTAssertThrowsError(try RelayProtocol.verifyEnvelope(Data(inner.utf8),
            senderSigningPublicKey: "PUAXw-hDiVqStwqnTRt-vJyYLM8uxJaMwM1V8Sr0Zgw", expectedContext: context))
        let invalid = [" " + inner, inner + "\n", inner + "{}",
            inner.replacingOccurrences(of: "TEST-ONLY-event-001", with: "TEST-ONLY-event-999"),
            inner.replacingOccurrences(of: "\"version\":1", with: "\"version\":1,\"version\":1"),
            inner.replacingOccurrences(of: "\"version\":1", with: "\"unexpected\":0,\"version\":1"),
            inner.replacingOccurrences(of: "\"version\":1", with: "\"version\":1.0"),
            inner.replacingOccurrences(of: "İlaç 🧪", with: "\\ud800")]
        for value in invalid {
            XCTAssertThrowsError(try RelayProtocol.verifyEnvelope(Data(value.utf8),
                senderSigningPublicKey: key, expectedContext: context))
        }
        XCTAssertThrowsError(try RelayProtocol.verifyEnvelope(Data([0xc3, 0x28]),
            senderSigningPublicKey: key, expectedContext: context))
    }

    func testSharedPairingSchemaAndSignatureWithoutLifecycleActivation() throws {
        let fixture = try load("relay-pairing-v2")
        XCTAssertEqual(fixture["testOnly"] as? Bool, true)
        let offer = try text(fixture, "canonicalOffer")
        let verified = try RelayProtocol.verifyPairing(Data(offer.utf8))
        XCTAssertEqual(verified.installId, "TEST-ONLY-sender")
        XCTAssertEqual(verified.offerId, "TEST-ONLY-offer-001")
        XCTAssertEqual(verified.keyVersion, 7)
        XCTAssertEqual(verified.signingInput, Data(try text(fixture, "signingInput").utf8))
        for invalid in [offer.replacingOccurrences(of: "TEST-ONLY-offer-001", with: "TEST-ONLY-offer-002"),
                        offer.replacingOccurrences(of: "\"version\":2", with: "\"extra\":0,\"version\":2"),
                        offer.replacingOccurrences(of: "\"version\":2", with: "\"version\":2,\"version\":2")] {
            XCTAssertThrowsError(try RelayProtocol.verifyPairing(Data(invalid.utf8)))
        }
    }

    private func load(_ name: String) throws -> [String: Any] {
        let bundle = Bundle(for: Self.self)
        // CI copies the exact shared fixtures into the existing test resource directory before
        // XcodeGen. Local source checkouts use the same repository fixtures without maintaining copies.
        let source = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("protocol-fixtures/\(name).json")
        let url = bundle.url(forResource: name, withExtension: "json") ?? source
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    private func text(_ object: [String: Any], _ key: String) throws -> String {
        try XCTUnwrap(object[key] as? String)
    }
}
