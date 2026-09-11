import XCTest
@testable import Dosefolk

final class RemoteStateReducerTests: XCTestCase {
    private var directory: URL!
    private var store: LocalStore!
    private var reducer: RemoteStateReducer!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        store = try LocalStore(directory: directory)
        reducer = RemoteStateReducer(store: store, localOwnerId: "local-topic")
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testTakenAndSnoozedUpdateDoseRuntime() throws {
        try reducer.apply(event(type: "snoozed", eventId: "e1", snoozeUntil: 1234))
        var runtime = try store.load([String: DoseRuntimeEntry].self, from: .doseRuntime, default: [:])
        XCTAssertEqual(runtime["local-topic|2026-09-11|08:00"]?.status, "snoozed")
        XCTAssertEqual(runtime["local-topic|2026-09-11|08:00"]?.snoozeUntil, 1234)

        try reducer.apply(event(type: "taken", eventId: "e2"))
        runtime = try store.load([String: DoseRuntimeEntry].self, from: .doseRuntime, default: [:])
        XCTAssertEqual(runtime["local-topic|2026-09-11|08:00"]?.status, "taken")
        XCTAssertEqual(runtime["local-topic|2026-09-11|08:00"]?.snoozeUntil, 0)
    }

    func testProgramRevisionRejectsOlderRemoteUpdate() throws {
        let newer = event(
            type: "program_updated",
            eventId: "newer",
            revision: 5,
            medication: Medication(id: "m1", name: "New", dose: "1", times: ["09:00"])
        )
        let older = event(
            type: "program_updated",
            eventId: "older",
            revision: 4,
            medication: Medication(id: "m1", name: "Old", dose: "1", times: ["07:00"])
        )
        try reducer.apply(newer)
        try reducer.apply(older)

        let medications = try store.load([Medication].self, from: .medications, default: [])
        XCTAssertEqual(medications.first?.name, "New")
        XCTAssertEqual(medications.first?.times, ["09:00"])
    }

    func testPresenceAndCapabilitiesArePersisted() throws {
        try reducer.apply(event(type: "circle_presence", eventId: "presence"))
        try reducer.apply(event(type: "capability_edit_program_granted", eventId: "cap"))

        let presence = try store.load([String: CirclePresenceEntry].self, from: .circlePresence, default: [:])
        XCTAssertEqual(presence["publisher-topic"]?.eventId, "presence")

        let capabilities = try store.load(RemoteCapabilityState.self, from: .remoteCapabilities, default: RemoteCapabilityState())
        XCTAssertTrue(capabilities.editProgram.contains("publisher-topic"))
    }

    private func event(
        type: String,
        eventId: String,
        snoozeUntil: Int64 = 0,
        revision: Int64 = 0,
        medication: Medication? = nil
    ) -> DoseEvent {
        DoseEvent(
            eventId: eventId,
            type: type,
            time: "08:00",
            scheduledDate: "2026-09-11",
            snoozeUntil: snoozeUntil,
            ownerId: "local-topic",
            actorTopic: "publisher-topic",
            timestamp: 1_789_000_000_000,
            revision: revision,
            medications: medication.map { [$0] } ?? []
        )
    }
}
