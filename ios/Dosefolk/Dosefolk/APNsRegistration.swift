import Foundation

struct APNsRegistrationPayload: Codable, Equatable {
    let installId: String
    let deviceToken: String
    let localTopic: String
    let subscriptions: [String]
    let appBundleId: String
    let environment: String
}

enum APNsRegistration {
    static func tokenHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func makePayload(
        token: Data,
        installId: String,
        localTopic: String,
        subscriptions: [String],
        bundleId: String
    ) -> APNsRegistrationPayload {
        APNsRegistrationPayload(
            installId: installId,
            deviceToken: tokenHex(token),
            localTopic: localTopic,
            subscriptions: Array(Set(subscriptions)).sorted(),
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
