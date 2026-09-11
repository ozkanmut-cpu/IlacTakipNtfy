import Foundation

struct CircleTransport {
    let settings: AppSettings
    let store: LocalStore

    func publishTopic() -> String {
        settings.localTopic.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func subscriptionTopics() throws -> [String] {
        let peers = try store.load([CirclePeer].self, from: .circlePeers, default: [])
        return Self.normalizeTopics([publishTopic()] + peers.map(\.topic))
    }

    static func normalizeTopics(_ topics: [String]) -> [String] {
        var result: [String] = []
        for topic in topics {
            let value = topic.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !value.isEmpty, !result.contains(value) else { continue }
            result.append(value)
        }
        return result
    }
}
