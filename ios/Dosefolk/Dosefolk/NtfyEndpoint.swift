import Foundation

enum NtfyEndpoint {
    static let baseURL = "https://ntfy.field-maintenance-prod.com"

    static func topicURL(_ topic: String) -> URL? {
        URL(string: "\(baseURL)/\(encode(topic))")
    }

    static func pollURL(topics: [String], since: String) -> URL? {
        let path = topics.map(encode).joined(separator: ",")
        return URL(string: "\(baseURL)/\(path)/json?poll=1&since=\(encode(since))")
    }

    static func streamURL(topics: [String], since: String) -> URL? {
        let path = topics.map(encode).joined(separator: ",")
        return URL(string: "\(baseURL)/\(path)/json?since=\(encode(since))")
    }

    private static func encode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }
}
