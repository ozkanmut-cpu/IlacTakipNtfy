import XCTest
@testable import Dosefolk

final class DosefolkQaLogTests: XCTestCase {
    private func directory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("dosefolk-qa-\(UUID().uuidString)", isDirectory: true)
    }

    func testMaskIsStableAndDoesNotRevealRawValue() {
        let raw = "dosefolk-secret-topic-123"
        let first = DosefolkQaLog.mask(raw)
        XCTAssertEqual(first, DosefolkQaLog.mask(raw))
        XCTAssertTrue(first.hasPrefix("#"))
        XCTAssertEqual(first.count, 9)
        XCTAssertFalse(first.contains(raw))
        XCTAssertNotEqual(first, DosefolkQaLog.mask("another-topic"))
    }

    func testSanitizeMasksSecretsAndDropsClinicalPayloadFields() {
        XCTAssertNotEqual(DosefolkQaLog.sanitize(key: "actorTopic", value: "dosefolk-private") as? String, "dosefolk-private")
        XCTAssertNil(DosefolkQaLog.sanitize(key: "medicationName", value: "Sensitive drug"))
        XCTAssertNil(DosefolkQaLog.sanitize(key: "dose", value: "2 tablet"))
        XCTAssertNil(DosefolkQaLog.sanitize(key: "protocolPayload", value: "{...}"))
        XCTAssertEqual(DosefolkQaLog.sanitize(key: "status", value: 200) as? Int, 200)
    }

    func testRecordCreatesJsonlInsideQaDirectoryWithStableSession() async throws {
        let root = directory()
        defer { try? FileManager.default.removeItem(at: root) }
        let logger = DosefolkQaLog(baseDirectory: root, sessionId: "session123")
        await logger.record(category: .NTFY_RX, event: "message", details: [
            "topic": "dosefolk-private-topic",
            "eventId": "evt-1",
            "eventType": "taken",
            "status": 200,
            "medicationName": "must-not-appear"
        ])
        await logger.record(category: .APP, event: "second")

        let url = try await logger.exportURL()
        XCTAssertEqual(url.deletingLastPathComponent().lastPathComponent, "qa")
        XCTAssertEqual(url.lastPathComponent, "dosefolk-qa.jsonl")
        let lines = try String(contentsOf: url).split(separator: "\n")
        XCTAssertEqual(lines.count, 2)
        let first = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(lines[0].utf8)) as? [String: Any])
        let second = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(lines[1].utf8)) as? [String: Any])
        XCTAssertEqual(first["session"] as? String, "session123")
        XCTAssertEqual(second["session"] as? String, "session123")
        XCTAssertEqual(first["category"] as? String, "NTFY_RX")
        XCTAssertEqual(first["eventId"] as? String, "evt-1")
        XCTAssertNil(first["medicationName"])
        XCTAssertNotEqual(first["topic"] as? String, "dosefolk-private-topic")
        XCTAssertNotNil(first["ios"])
        XCTAssertNotNil(first["device"])
    }
}
