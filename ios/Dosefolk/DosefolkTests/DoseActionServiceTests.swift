import XCTest
@testable import Dosefolk

final class DoseActionServiceTests: XCTestCase {
    func testActionDeliveryGuardMatchesAndroid() {
        XCTAssertTrue(DoseActionService.shouldApply(.snooze, to: .unknown))
        XCTAssertTrue(DoseActionService.shouldApply(.snooze, to: .pending))
        XCTAssertFalse(DoseActionService.shouldApply(.snooze, to: .snoozed))
        XCTAssertFalse(DoseActionService.shouldApply(.snooze, to: .taken))
        XCTAssertFalse(DoseActionService.shouldApply(.snooze, to: .missed))
        XCTAssertFalse(DoseActionService.shouldApply(.snooze, to: .conflict))

        for status in [DoseSessionStatus.unknown, .pending, .snoozed] {
            XCTAssertTrue(DoseActionService.shouldApply(.taken, to: status))
            XCTAssertTrue(DoseActionService.shouldApply(.missed, to: status))
        }
        for status in [DoseSessionStatus.taken, .missed, .conflict] {
            XCTAssertFalse(DoseActionService.shouldApply(.taken, to: status))
            XCTAssertFalse(DoseActionService.shouldApply(.missed, to: status))
        }
    }
}
