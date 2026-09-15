import Foundation

enum PushGatewayError: Error, Equatable {
    case invalidURL
    case rejected(Int)
    case invalidResponse
}

enum PushGatewayEndpoint {
    static let baseURL = "https://ntfy.field-maintenance-prod.com/dosefolk-push"
    static var registrationURL: URL? { URL(string: "\(baseURL)/v1/register") }
    static var provisioningURL: URL? { URL(string: "\(baseURL)/v1/provision") }
    static var accessURL: URL? { URL(string: "\(baseURL)/v1/access") }
}

struct PushGatewayProvisionRequest: Codable, Equatable {
    let installId: String
    let ticket: String
    let requireNtfyToken: Bool
    let localTopic: String
    let subscriptions: [String]
}

struct PushGatewayProvisionResponse: Codable, Equatable {
    let installId: String
    let credential: String
    let ntfyToken: String
}

struct PushGatewayAccessRequest: Codable, Equatable {
    let installId: String
    let subscriptions: [String]
}

actor PushGatewayClient {
    private let session: URLSession
    private let keychain: KeychainStore

    init(session: URLSession = .shared, keychain: KeychainStore = KeychainStore()) {
        self.session = session
        self.keychain = keychain
    }

    static func makeProvisioningRequest(
        installId: String,
        ticket: String,
        localTopic: String,
        subscriptions: [String],
        url: URL
    ) throws -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(PushGatewayProvisionRequest(
            installId: installId,
            ticket: ticket,
            requireNtfyToken: true,
            localTopic: localTopic,
            subscriptions: CircleTransport.normalizeTopics([localTopic] + subscriptions)
        ))
        return request
    }

    static func decodeProvisioningResponse(_ data: Data, expectedInstallId: String) throws -> PushGatewayProvisionResponse {
        let body = try JSONDecoder().decode(PushGatewayProvisionResponse.self, from: data)
        guard body.installId == expectedInstallId,
              !body.credential.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !body.ntfyToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw PushGatewayError.invalidResponse
        }
        return body
    }

    func provision(
        installId: String,
        ticket: String,
        localTopic: String,
        subscriptions: [String]
    ) async throws {
        guard let url = PushGatewayEndpoint.provisioningURL else { throw PushGatewayError.invalidURL }
        let request = try Self.makeProvisioningRequest(
            installId: installId,
            ticket: ticket,
            localTopic: localTopic,
            subscriptions: subscriptions,
            url: url
        )
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw PushGatewayError.rejected(status) }
        let credentials = try Self.decodeProvisioningResponse(data, expectedInstallId: installId)
        try keychain.set(credentials.credential, for: SecureCredentialKey.provisioningSecret)
        try keychain.set(credentials.ntfyToken, for: SecureCredentialKey.ntfyToken)
    }

    static func makeRegistrationRequest(
        payload: APNsRegistrationPayload,
        credential: String,
        url: URL
    ) throws -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(credential)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(payload)
        return request
    }

    static func makeAccessRequest(
        installId: String,
        subscriptions: [String],
        credential: String,
        url: URL
    ) throws -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(credential)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(PushGatewayAccessRequest(
            installId: installId,
            subscriptions: CircleTransport.normalizeTopics(subscriptions)
        ))
        return request
    }

    func register(_ payload: APNsRegistrationPayload) async throws -> Bool {
        guard let credential = try keychain.string(for: SecureCredentialKey.provisioningSecret),
              !credential.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }
        guard let url = PushGatewayEndpoint.registrationURL else { throw PushGatewayError.invalidURL }
        let request = try Self.makeRegistrationRequest(
            payload: payload,
            credential: credential,
            url: url
        )
        let (_, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw PushGatewayError.rejected(status) }
        return true
    }

    func refreshAccess(installId: String, subscriptions: [String]) async throws -> Bool {
        guard let credential = try keychain.string(for: SecureCredentialKey.provisioningSecret),
              !credential.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let ntfyToken = try keychain.string(for: SecureCredentialKey.ntfyToken),
              !ntfyToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }
        guard let url = PushGatewayEndpoint.accessURL else { throw PushGatewayError.invalidURL }
        let request = try Self.makeAccessRequest(
            installId: installId,
            subscriptions: subscriptions,
            credential: credential,
            url: url
        )
        let (_, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw PushGatewayError.rejected(status) }
        return true
    }
}
