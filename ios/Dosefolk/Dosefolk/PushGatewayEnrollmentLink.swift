import CryptoKit
import Foundation

struct PushGatewayEnrollmentLink: Equatable {
    let installId: String
    let ticket: String

    static func parse(_ url: URL) -> PushGatewayEnrollmentLink? {
        guard url.scheme?.lowercased() == "dosefolk",
              url.host?.lowercased() == "enroll",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return nil
        }

        let values = Dictionary(
            uniqueKeysWithValues: (components.queryItems ?? []).compactMap { item in
                item.value.map { (item.name, $0) }
            }
        )
        guard let installId = values["installId"]?.trimmingCharacters(in: .whitespacesAndNewlines),
              let ticket = values["ticket"]?.trimmingCharacters(in: .whitespacesAndNewlines),
              installId.range(of: #"^[A-Za-z0-9._-]{8,128}$"#, options: .regularExpression) != nil,
              !ticket.isEmpty,
              ticket.count <= 256 else {
            return nil
        }
        return PushGatewayEnrollmentLink(installId: installId, ticket: ticket)
    }
}

struct PushGatewayEnrollmentReplayGuard {
    private static let defaultsKey = "push-gateway-consumed-enrollment-fingerprints"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func isConsumed(_ enrollment: PushGatewayEnrollmentLink) -> Bool {
        consumedFingerprints.contains(Self.fingerprint(enrollment.ticket))
    }

    func markConsumed(_ enrollment: PushGatewayEnrollmentLink) {
        let fingerprint = Self.fingerprint(enrollment.ticket)
        var fingerprints = consumedFingerprints.filter { $0 != fingerprint }
        fingerprints.append(fingerprint)
        defaults.set(Array(fingerprints.suffix(16)), forKey: Self.defaultsKey)
    }

    private var consumedFingerprints: [String] {
        defaults.stringArray(forKey: Self.defaultsKey) ?? []
    }

    private static func fingerprint(_ ticket: String) -> String {
        SHA256.hash(data: Data(ticket.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
