import XCTest
@testable import Dosefolk

final class MedicationProgramServiceTests: XCTestCase {
    func testNormalizeTrimsAndDeduplicatesTimes() {
        let medication = Medication(
            id: "m1",
            name: "  Test  ",
            dose: " 1 tablet ",
            times: ["8:5", "08:05", "25:00", " 20:30 "]
        )

        let normalized = MedicationProgramService.normalize(medication)

        XCTAssertEqual(normalized.name, "Test")
        XCTAssertEqual(normalized.dose, "1 tablet")
        XCTAssertEqual(normalized.times, ["08:05", "20:30"])
    }

    func testNormalizeTimeRejectsInvalidValues() {
        XCTAssertNil(MedicationProgramService.normalizeTime("24:00"))
        XCTAssertNil(MedicationProgramService.normalizeTime("12:60"))
        XCTAssertNil(MedicationProgramService.normalizeTime("nope"))
        XCTAssertEqual(MedicationProgramService.normalizeTime("7:03"), "07:03")
    }
}
