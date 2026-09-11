import CryptoKit
import Foundation
import UIKit

actor DosefolkQaLog {
    enum Category: String, Codable, CaseIterable {
        case APP, SYNC, NTFY_RX, NTFY_TX, PAIR, REVOKE, ALARM, ACTION, WORKER, ERROR, SECURITY_REJECT
    }

    static let shared = DosefolkQaLog()
    static let fileName = "dosefolk-qa.jsonl"
    static let maxBytes = 2 * 1024 * 1024
    static let keepBytes = 1 * 1024 * 1024

    private let fileManager: FileManager
    private let baseDirectory: URL
    private let sessionId: String

    init(
        fileManager: FileManager = .default,
        baseDirectory: URL? = nil,
        sessionId: String = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12))
    ) {
        self.fileManager = fileManager
        if let baseDirectory {
            self.baseDirectory = baseDirectory
        } else {
            let root = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? fileManager.temporaryDirectory
            self.baseDirectory = root.appendingPathComponent("Dosefolk", isDirectory: true)
        }
        self.sessionId = sessionId
    }

    func record(category: Category, event: String, details: [String: Any?] = [:]) {
        do {
            let url = try exportURL()
            try rotateIfNeeded(url)
            var object: [String: Any] = [
                "ts": ISO8601DateFormatter().string(from: Date()),
                "session": sessionId,
                "appVersion": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "",
                "appCode": Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "",
                "ios": UIDevice.current.systemVersion,
                "device": Self.deviceModel(),
                "category": category.rawValue,
                "event": Self.truncate(event)
            ]
            for (key, value) in details {
                guard let sanitized = Self.sanitize(key: key, value: value) else { continue }
                object[key] = sanitized
            }
            let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
            guard var text = String(data: data, encoding: .utf8) else { return }
            text.append("\n")
            if let output = text.data(using: .utf8) {
                if fileManager.fileExists(atPath: url.path) {
                    let handle = try FileHandle(forWritingTo: url)
                    try handle.seekToEnd()
                    try handle.write(contentsOf: output)
                    try handle.close()
                } else {
                    try output.write(to: url, options: .atomic)
                }
            }
        } catch {
            // QA logging must never affect medication behavior.
        }
    }

    func exportURL() throws -> URL {
        let directory = baseDirectory.appendingPathComponent("qa", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent(Self.fileName)
    }

    func clear() {
        if let url = try? exportURL() { try? fileManager.removeItem(at: url) }
    }

    func currentSessionId() -> String { sessionId }

    static func mask(_ value: String) -> String {
        guard !value.isEmpty else { return "" }
        let digest = SHA256.hash(data: Data(value.utf8))
        return "#" + digest.prefix(4).map { String(format: "%02x", $0) }.joined()
    }

    static func sanitize(key: String, value: Any?) -> Any? {
        guard let value else { return NSNull() }
        let lower = key.lowercased()
        let forbidden = ["medication", "medicine", "drug", "dose", "notificationbody", "notification_body", "body", "payload"]
        if forbidden.contains(where: { lower.contains($0) }) { return nil }
        let text = String(describing: value)
        if lower.contains("topic") || lower.contains("token") || lower.contains("secret") || lower.contains("credential") {
            return mask(text)
        }
        if text.count > 500 { return truncate(text) }
        if value is String || value is NSNumber || value is Bool || value is NSNull { return value }
        return text
    }

    private func rotateIfNeeded(_ url: URL) throws {
        guard fileManager.fileExists(atPath: url.path) else { return }
        let attrs = try fileManager.attributesOfItem(atPath: url.path)
        let size = (attrs[.size] as? NSNumber)?.intValue ?? 0
        guard size >= Self.maxBytes else { return }
        let data = try Data(contentsOf: url)
        let start = max(0, data.count - Self.keepBytes)
        var slice = data.subdata(in: start..<data.count)
        if let newline = slice.firstIndex(of: 0x0A), newline + 1 < slice.endIndex {
            slice = slice.subdata(in: (newline + 1)..<slice.endIndex)
        }
        try slice.write(to: url, options: .atomic)
    }

    private static func truncate(_ text: String) -> String {
        guard text.count > 500 else { return text }
        return String(text.prefix(500)) + "…"
    }

    private static func deviceModel() -> String {
        var system = utsname()
        uname(&system)
        let mirror = Mirror(reflecting: system.machine)
        return mirror.children.reduce(into: "") { result, element in
            guard let value = element.value as? Int8, value != 0 else { return }
            result.append(Character(UnicodeScalar(UInt8(value))))
        }
    }
}
