import XCTest
@testable import Dosefolk

final class MedicationAmountTests: XCTestCase {
    func testTabletPresetsAndCustomDecimalBecomeSavedQuantities() {
        var amount = MedicationAmount(newTablet: true)
        XCTAssertEqual(amount.quantity, 1)
        amount.select(.quarter)
        XCTAssertEqual(amount.quantity, 0.25)
        amount.select(.half)
        XCTAssertEqual(amount.quantity, 0.5)
        amount.select(.custom)
        XCTAssertNil(amount.quantity)
        amount.text = "1,25"
        XCTAssertEqual(amount.quantity, 1.25)
        for text in ["", "0", "-1", "1,2.3", "NaN", "1e4"] {
            amount.text = text
            XCTAssertNil(amount.quantity)
        }
    }

    func testReopeningAnExistingUnspecifiedAmountDoesNotInventAQuantity() {
        let missing = MedicationAmount(existing: nil)
        XCTAssertNil(missing.quantity)
        XCTAssertEqual(missing.choice, .custom)
        XCTAssertEqual(MedicationAmount(existing: 0.25).choice, .quarter)
        XCTAssertEqual(MedicationAmount(existing: 1.5).quantity, 1.5)
    }
}
