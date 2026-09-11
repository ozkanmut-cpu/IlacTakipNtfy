import Foundation

enum PushGatewayError: Error, Equatable {
    case invalidURL
    case rejected(Int)
}

enum PushGatewayEndpoint {
    static let baseURL = "https://ntfy.field-maintenance-prod.com/dosefolk-push"
    static var registrationURL: URL? { URL(string: "\(baseURL)/v1/register") }
}

actor PushGatewayClient {
    private let session: URLSession
    private let keychain: KeychainStore

    init(session: URLSession = .shared, keychain: KeychainStore = KeychainStore()) {
        self.session = session
        self.keychain = keychain
    }

    func register(_ payload: APNsRegistrationPayload) async throws -> Bool {
        guard let credential = try keychain.string(for: SecureCredentialKey.provisioningSecret),
              !credential.isEmpty else {
            return false
        }
        guard let url = PushGatewayEndpoint.registrationURL else { throw PushGatewayError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(credential)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(payload)
        let (_, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw PushGatewayError.rejected(status) }
        return true
    }
}
