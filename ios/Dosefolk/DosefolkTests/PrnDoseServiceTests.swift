import XCTest
@testable import Dosefolk

final class PrnDoseServiceTests: XCTestCase {
    func testDateAndTimeFormattingIsProtocolStable() throws {
        var components = DateComponents()
        components.calendar = Calendar(identifier: .gregorian)
        components.timeZone = TimeZone(secondsFromGMT: 0)
        components.year = 2026
        components.month = 9
        components.day = 11
        components.hour = 8
        components.minute = 7
        let date = try XCTUnwrap(components.date)

        XCTAssertEqual(PrnDoseService.dateString(date), "2026-09-11")
        XCTAssertEqual(PrnDoseService.timeString(date), "08:07")
    }
}
