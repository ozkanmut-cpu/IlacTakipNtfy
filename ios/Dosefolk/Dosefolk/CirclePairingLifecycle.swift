import Foundation

enum CirclePairingLifecycleError: Error, Equatable {
    case invalidTopic
    case drainFailed
}

final class CirclePairingLifecycle {
    typealias Poll = (_ topics: [String], _ since: String) async throws -> NtfyPollResult

    private let localTopic: String
    private let securityState: CircleSecurityState
    private let poll: Poll

    init(
        localTopic: String,
        store: LocalStore,
        client: NtfyClient = NtfyClient(),
        poll: Poll? = nil
    ) {
        self.localTopic = localTopic
        self.securityState = CircleSecurityState(store: store)
        self.poll = poll ?? { topics, since in
            try await client.poll(topics: topics, since: since)
        }
    }

    func prepareRePair(topic: String) async throws {
        let peerTopic = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peerTopic.isEmpty, peerTopic != localTopic else {
            throw CirclePairingLifecycleError.invalidTopic
        }
        guard try securityState.isRevoked(actorTopic: peerTopic) else { return }

        let batch: NtfyPollResult
        do {
            batch = try await poll([peerTopic], "24h")
        } catch {
            throw CirclePairingLifecycleError.drainFailed
        }

        for envelope in batch.envelopes {
            _ = try InboundProtocolGate.inspectSecure(
                envelope: envelope,
                localTopic: localTopic,
                securityState: securityState
            )
        }
    }

    func completeRePair(topic: String) throws {
        let peerTopic = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peerTopic.isEmpty, peerTopic != localTopic else {
            throw CirclePairingLifecycleError.invalidTopic
        }
        try securityState.clearRevoke(actorTopic: peerTopic)
    }
}
