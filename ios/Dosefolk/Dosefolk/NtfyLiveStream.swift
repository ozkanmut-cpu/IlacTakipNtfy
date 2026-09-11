import Foundation

struct NtfyReconnectPolicy {
    private(set) var attempt = 0

    mutating func reset() {
        attempt = 0
    }

    mutating func nextDelayNanoseconds() -> UInt64 {
        let seconds = min(30, 1 << min(attempt, 5))
        attempt += 1
        return UInt64(seconds) * 1_000_000_000
    }
}

final class NtfyLiveStream {
    private let session: URLSession
    private let keychain: KeychainStore

    init(session: URLSession = .shared, keychain: KeychainStore = KeychainStore()) {
        self.session = session
        self.keychain = keychain
    }

    func events(topics: [String], since: String = "10s") -> AsyncThrowingStream<SyncEnvelope, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    guard !topics.isEmpty, let url = NtfyEndpoint.streamURL(topics: topics, since: since) else {
                        throw NtfyClientError.invalidURL
                    }
                    var request = URLRequest(url: url, timeoutInterval: 60)
                    request.httpMethod = "GET"
                    if let token = try keychain.string(for: SecureCredentialKey.ntfyToken), !token.isEmpty {
                        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                    }

                    let (bytes, response) = try await session.bytes(for: request)
                    guard let http = response as? HTTPURLResponse else {
                        throw NtfyClientError.invalidResponse
                    }
                    guard (200...299).contains(http.statusCode) else {
                        throw NtfyClientError.http(http.statusCode)
                    }

                    let decoder = JSONDecoder()
                    for try await line in bytes.lines {
                        if Task.isCancelled { break }
                        guard !line.isEmpty,
                              let data = line.data(using: .utf8),
                              let envelope = try? decoder.decode(SyncEnvelope.self, from: data) else { continue }
                        continuation.yield(envelope)
                    }
                    continuation.finish()
                } catch is CancellationError {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func runReconnecting(
        topics: @escaping @Sendable () -> [String],
        since: String = "10s",
        onEnvelope: @escaping @Sendable (SyncEnvelope) async -> Void
    ) async {
        var policy = NtfyReconnectPolicy()
        while !Task.isCancelled {
            let currentTopics = topics()
            if currentTopics.isEmpty {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                continue
            }
            do {
                for try await envelope in events(topics: currentTopics, since: since) {
                    if Task.isCancelled { return }
                    if topics() != currentTopics { break }
                    policy.reset()
                    await onEnvelope(envelope)
                }
            } catch {
                if Task.isCancelled { return }
                if topics() != currentTopics {
                    policy.reset()
                    continue
                }
                let delay = policy.nextDelayNanoseconds()
                try? await Task.sleep(nanoseconds: delay)
            }
        }
    }
}
