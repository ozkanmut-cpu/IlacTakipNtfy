import Foundation

struct APNsRegistrationPayload: Codable, Equatable {
    let deviceToken: String
    let localTopic: String
    let appBundleId: String
    let environment: String
}

enum APNsRegistration {
    static func tokenHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func makePayload(token: Data, localTopic: String, bundleId: String) -> APNsRegistrationPayload {
        APNsRegistrationPayload(
            deviceToken: tokenHex(token),
            localTopic: localTopic,
            appBundleId: bundleId,
            environment: Self.environment
        )
    }

    static var environment: String {
#if DEBUG
        return "sandbox"
#else
        return "production"
#endif
    }
}
