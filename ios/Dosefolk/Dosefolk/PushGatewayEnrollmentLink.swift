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
