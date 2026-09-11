import XCTest
@testable import Dosefolk

final class ProgramRuleCompatibilityTests: XCTestCase {
    func testDecodesAndroidProgramRuleCarrier() throws {
        let json = #"{"medicationId":"m1","weekdays":[1,3,7],"startDate":"2026-09-01","endDate":"","everyNDays":2,"anchorDate":"2026-09-01","routineLabel":"Sabah"}"#
        let rule = try ProgramRuleCodec.decode(json)

        XCTAssertEqual(rule.medicationId, "m1")
        XCTAssertEqual(rule.weekdays, Set([1, 3, 7]))
        XCTAssertEqual(rule.startDate, "2026-09-01")
        XCTAssertNil(rule.endDate)
        XCTAssertEqual(rule.everyNDays, 2)
        XCTAssertEqual(rule.anchorDate, "2026-09-01")
        XCTAssertEqual(rule.routineLabel, "Sabah")
    }

    func testEncodesAndroidFieldNamesAndNormalizesValues() throws {
        let rule = ProgramRule(
            medicationId: "m1",
            weekdays: Set([0, 1, 8, 5]),
            startDate: "",
            endDate: nil,
            everyNDays: 0,
            anchorDate: "",
            routineLabel: "Night"
        )
        let encoded = try ProgramRuleCodec.encode(rule)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])

        XCTAssertEqual(object["medicationId"] as? String, "m1")
        XCTAssertEqual(object["weekdays"] as? [Int], [1, 5])
        XCTAssertEqual(object["startDate"] as? String, "")
        XCTAssertEqual(object["endDate"] as? String, "")
        XCTAssertEqual(object["everyNDays"] as? Int, 1)
        XCTAssertEqual(object["anchorDate"] as? String, "")
        XCTAssertEqual(object["routineLabel"] as? String, "Night")
    }
}
