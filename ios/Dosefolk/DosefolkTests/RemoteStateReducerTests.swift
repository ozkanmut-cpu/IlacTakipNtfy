import XCTest
@testable import Dosefolk

final class RemoteStateReducerTests: XCTestCase {
    private final class Notifications: DoseNotificationManaging {
        var resolved: [(String, String)] = []
        var snoozed: [(String, String, Int64)] = []
        func resolve(time: String, scheduledDate: String) { resolved.append((time, scheduledDate)) }
        func snooze(time: String, scheduledDate: String, untilMillis: Int64) { snoozed.append((time, scheduledDate, untilMillis)) }
    }

    private var directory: URL!
    private var store: LocalStore!
    private var notifications: Notifications!
    private var reducer: RemoteStateReducer!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        store = try LocalStore(directory: directory)
        notifications = Notifications()
        reducer = RemoteStateReducer(store: store, localOwnerId: "local-topic", notifications: notifications)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testTakenAndSnoozedUpdateDoseRuntimeAndNotifications() throws {
        try reducer.apply(event(type: "snoozed", eventId: "e1", snoozeUntil: 1234))
        var runtime = try store.load([String: DoseRuntimeEntry].self, from: .doseRuntime, default: [:])
        XCTAssertEqual(runtime["local-topic|2026-09-11|08:00"]?.status, "snoozed")
        XCTAssertEqual(runtime["local-topic|2026-09-11|08:00"]?.snoozeUntil, 1234)
        XCTAssertEqual(notifications.snoozed.count, 1)
        XCTAssertEqual(notifications.snoozed.first?.2, 1234)

        try reducer.apply(event(type: "taken", eventId: "e2"))
        runtime = try store.load([String: DoseRuntimeEntry].self, from: .doseRuntime, default: [:])
        XCTAssertEqual(runtime["local-topic|2026-09-11|08:00"]?.status, "taken")
        XCTAssertEqual(runtime["local-topic|2026-09-11|08:00"]?.snoozeUntil, 0)
        XCTAssertEqual(notifications.resolved.count, 1)
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

    func testOlderCapabilityGrantCannotReplayAfterNewerRevoke() throws {
        var grant = event(type: "capability_edit_program_granted", eventId: "grant", revision: 4)
        grant.timestamp = 100
        var revoke = event(type: "capability_edit_program_revoked", eventId: "revoke", revision: 5)
        revoke.timestamp = 200

        try reducer.apply(grant)
        try reducer.apply(revoke)
        try reducer.apply(grant)

        let capabilities = try store.load(RemoteCapabilityState.self, from: .remoteCapabilities, default: RemoteCapabilityState())
        XCTAssertFalse(capabilities.editProgram.contains("publisher-topic"))
    }

    func testRemoteRevokeFencesPeerAndClearsPeerState() throws {
        try store.save([CirclePeer(id: "peer", name: "Peer", topic: "publisher-topic")], to: .circlePeers)
        try reducer.apply(event(type: "circle_presence", eventId: "presence"))
        try reducer.apply(event(type: "capability_edit_program_granted", eventId: "cap"))

        var revoke = event(type: "circle_revoked", eventId: "revoke")
        revoke.targetTopic = "local-topic"
        try reducer.apply(revoke)

        XCTAssertTrue(try CircleSecurityState(store: store).isRevoked(actorTopic: "publisher-topic"))
        XCTAssertTrue(try store.load([CirclePeer].self, from: .circlePeers, default: []).isEmpty)
        XCTAssertNil(try store.load([String: CirclePresenceEntry].self, from: .circlePresence, default: [:])["publisher-topic"])
        XCTAssertFalse(try store.load(RemoteCapabilityState.self, from: .remoteCapabilities, default: RemoteCapabilityState()).editProgram.contains("publisher-topic"))
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
