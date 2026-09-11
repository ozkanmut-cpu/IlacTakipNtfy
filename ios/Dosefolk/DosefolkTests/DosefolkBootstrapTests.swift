import XCTest
@testable import Dosefolk

final class DosefolkBootstrapTests: XCTestCase {
    func testSharedV9FixtureDecodesOnIOS() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "dose-event-v9", withExtension: "json"))
        let event = try JSONDecoder().decode(DoseEvent.self, from: Data(contentsOf: url))

        XCTAssertEqual(event.v, 9)
        XCTAssertEqual(event.eventId, "fixture-v9-001")
        XCTAssertEqual(event.actorTopic, "publisher-fixture-topic")
        XCTAssertEqual(event.targetTopic, "target-fixture-topic")
        XCTAssertEqual(event.revision, 42)
        XCTAssertEqual(event.medications.first?.times, ["08:00", "20:00"])
        XCTAssertEqual(event.medicationMeta.first?.form, .TABLET)
    }

    func testSparseAndroidPayloadUsesAndroidCompatibleDefaults() throws {
        let json = #"{"v":9,"eventId":"sparse-1","type":"taken","time":"08:00","actorTopic":"publisher","medications":[{"id":"med-1"}],"medicationMeta":[{"medicationId":"med-1"}]}"#
        let event = try JSONDecoder().decode(DoseEvent.self, from: Data(json.utf8))

        XCTAssertEqual(event.eventId, "sparse-1")
        XCTAssertEqual(event.syncState, "pending")
        XCTAssertEqual(event.targetTopic, "")
        XCTAssertEqual(event.timestamp, 0)
        XCTAssertEqual(event.medications.single?.name, "")
        XCTAssertEqual(event.medications.single?.times, [])
        XCTAssertEqual(event.medicationMeta.single?.form, .OTHER)
        XCTAssertEqual(event.medicationMeta.single?.source, "manual")
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

    func testLocalStoreRoundTripsMedicationData() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("dosefolk-tests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let store = try LocalStore(directory: directory)
        let medications = [Medication(id: "med-1", name: "Test", dose: "1", times: ["08:00"])]

        try store.save(medications, to: .medications)
        let restored = try store.load([Medication].self, from: .medications, default: [])

        XCTAssertEqual(restored, medications)
    }

    func testLocalStoreReturnsDefaultForMissingCollection() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("dosefolk-tests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let store = try LocalStore(directory: directory)
        let restored = try store.load([DoseEvent].self, from: .doseEvents, default: [])

        XCTAssertTrue(restored.isEmpty)
    }

    func testAppSettingsUseUserDefaultsWithoutSecrets() throws {
        let suiteName = "DosefolkTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let settings = AppSettings(defaults: defaults)

        settings.displayName = "Test User"
        settings.language = "tr"
        settings.localTopic = "publisher-topic"
        settings.lastSyncID = "msg-1"

        XCTAssertEqual(settings.displayName, "Test User")
        XCTAssertEqual(settings.language, "tr")
        XCTAssertEqual(settings.localTopic, "publisher-topic")
        XCTAssertEqual(settings.lastSyncID, "msg-1")
        XCTAssertNil(defaults.string(forKey: SecureCredentialKey.ntfyToken))
        XCTAssertNil(defaults.string(forKey: SecureCredentialKey.provisioningSecret))
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
