import XCTest
@testable import Dosefolk

final class DosefolkBootstrapTests: XCTestCase {
    func testDoseEventDecodesAndroidV9Payload() throws {
        let json = #"{"v":9,"eventId":"evt-1","type":"taken","time":"08:00","scheduledDate":"2026-09-11","snoozeUntil":0,"ownerId":"owner-1","targetTopic":"peer-topic","actor":"Ozkan","actorTopic":"publisher-topic","timestamp":1789084800000,"revision":7,"syncState":"synced","medications":[{"id":"med-1","name":"Example","dose":"1 tablet","times":["08:00","20:00"]}],"medicationMeta":[{"medicationId":"med-1","form":"TABLET","quantity":1,"administrationSite":"","packageCount":30,"packageUnit":"tablet","source":"manual","doseUnitOverride":""}]}"#

        let event = try JSONDecoder().decode(DoseEvent.self, from: Data(json.utf8))

        XCTAssertEqual(event.v, 9)
        XCTAssertEqual(event.eventId, "evt-1")
        XCTAssertEqual(event.actorTopic, "publisher-topic")
        XCTAssertEqual(event.targetTopic, "peer-topic")
        XCTAssertEqual(event.revision, 7)
        XCTAssertEqual(event.medications.first?.times, ["08:00", "20:00"])
        XCTAssertEqual(event.medicationMeta.first?.form, .TABLET)
    }

    func testDoseEventEncodesCanonicalAndroidFieldNames() throws {
        let event = DoseEvent(
            eventId: "evt-2",
            type: "snoozed",
            time: "09:00",
            actorTopic: "publisher-topic",
            revision: 3
        )

        let data = try JSONEncoder().encode(event)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(object["v"] as? Int, 9)
        XCTAssertEqual(object["eventId"] as? String, "evt-2")
        XCTAssertEqual(object["actorTopic"] as? String, "publisher-topic")
        XCTAssertEqual(object["revision"] as? Int, 3)
        XCTAssertNotNil(object["medications"])
        XCTAssertNotNil(object["medicationMeta"])
    }
}
