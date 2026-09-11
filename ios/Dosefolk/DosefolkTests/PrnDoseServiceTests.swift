import XCTest
@testable import Dosefolk

final class PrnDoseServiceTests: XCTestCase {
    func testDateAndTimeFormattingIsProtocolStable() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let date = try XCTUnwrap(calendar.date(from: DateComponents(
            year: 2026,
            month: 9,
            day: 11,
            hour: 8,
            minute: 7
        )))

        XCTAssertEqual(PrnDoseService.dateString(date), "2026-09-11")
        XCTAssertEqual(PrnDoseService.timeString(date), "08:07")
    }
}
