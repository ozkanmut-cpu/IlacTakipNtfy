import Foundation

struct CirclePresencePublisher {
    let publisher: ProtocolEventPublisher
    let localTopic: String

    func publish(to targetTopic: String) async throws {
        let target = targetTopic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty, target != localTopic else { return }
        _ = try await publisher.publish(
            type: "circle_presence",
            time: "circle",
            targetTopic: target
        )
    }
}
