import Foundation

struct CirclePairingPayload: Equatable {
    let topic: String
    let name: String

    static func encode(topic: String, name: String) -> String? {
        let cleanTopic = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTopic.isEmpty else { return nil }
        var components = URLComponents()
        components.scheme = "dosefolk"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "topic", value: cleanTopic),
            URLQueryItem(name: "name", value: name.trimmingCharacters(in: .whitespacesAndNewlines))
        ]
        return components.string
    }

    static func parse(_ raw: String) -> CirclePairingPayload? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("dosefolk://pair"), let components = URLComponents(string: value) {
            let topic = components.queryItems?.first(where: { $0.name == "topic" })?.value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let name = components.queryItems?.first(where: { $0.name == "name" })?.value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !topic.isEmpty else { return nil }
            return CirclePairingPayload(topic: topic, name: name)
        }
        if value.hasPrefix("dosefolk-"), value.count >= 12 {
            return CirclePairingPayload(topic: value, name: "")
        }
        return nil
    }
}
