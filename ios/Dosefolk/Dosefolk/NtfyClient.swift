import Foundation

enum NtfyClientError: Error, Equatable {
    case invalidURL
    case blocked
    case invalidResponse
    case http(Int)
}

final class NtfyClient {
    private let session: URLSession
    private let rateGate: NtfyRateGate
    private let keychain: KeychainStore

    init(session: URLSession = .shared, rateGate: NtfyRateGate = NtfyRateGate(), keychain: KeychainStore = KeychainStore()) {
        self.session = session
        self.rateGate = rateGate
        self.keychain = keychain
    }

    func publish(topic: String, body: String, title: String = "Dosefolk sync", priority: String = "min") async throws {
        guard !rateGate.isBlocked() else { throw NtfyClientError.blocked }
        guard let url = NtfyEndpoint.topicURL(topic) else { throw NtfyClientError.invalidURL }

        var request = URLRequest(url: url, timeoutInterval: 10)
        request.httpMethod = "POST"
        request.httpBody = Data(body.utf8)
        request.setValue(title, forHTTPHeaderField: "Title")
        request.setValue(priority, forHTTPHeaderField: "Priority")
        request.setValue("text/plain; charset=utf-8", forHTTPHeaderField: "Content-Type")
        try addAuthorization(to: &request)

        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw NtfyClientError.invalidResponse }
        if http.statusCode == 429 {
            rateGate.record429(retryAfter: http.value(forHTTPHeaderField: "Retry-After"))
            throw NtfyClientError.http(429)
        }
        guard (200...299).contains(http.statusCode) else { throw NtfyClientError.http(http.statusCode) }
        rateGate.clearAfterSuccess()
    }

    func poll(topics: [String], since: String) async throws -> [SyncEnvelope] {
        guard let url = NtfyEndpoint.pollURL(topics: topics, since: since) else { throw NtfyClientError.invalidURL }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = "GET"
        try addAuthorization(to: &request)

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw NtfyClientError.invalidResponse }
        guard (200...299).contains(http.statusCode) else { throw NtfyClientError.http(http.statusCode) }

        let decoder = JSONDecoder()
        return String(decoding: data, as: UTF8.self)
            .split(whereSeparator: \.isNewline)
            .compactMap { try? decoder.decode(SyncEnvelope.self, from: Data($0.utf8)) }
    }

    private func addAuthorization(to request: inout URLRequest) throws {
        if let token = try keychain.string(for: SecureCredentialKey.ntfyToken), !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
    }
}
