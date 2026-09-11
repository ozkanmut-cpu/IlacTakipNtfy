import Foundation

final class AppSettings {
    private enum Key {
        static let displayName = "displayName"
        static let language = "language"
        static let localTopic = "localTopic"
        static let lastSyncID = "lastSyncID"
        static let lastSyncAt = "lastSyncAt"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var displayName: String {
        get { defaults.string(forKey: Key.displayName) ?? "" }
        set { defaults.set(newValue, forKey: Key.displayName) }
    }

    var language: String {
        get { defaults.string(forKey: Key.language) ?? "tr" }
        set { defaults.set(newValue, forKey: Key.language) }
    }

    var localTopic: String {
        get { defaults.string(forKey: Key.localTopic) ?? "" }
        set { defaults.set(newValue, forKey: Key.localTopic) }
    }

    @discardableResult
    func ensureLocalTopic() -> String {
        let current = localTopic.trimmingCharacters(in: .whitespacesAndNewlines)
        if !current.isEmpty { return current }
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(24)
        let generated = "dosefolk-\(suffix)"
        localTopic = generated
        return generated
    }

    var lastSyncID: String {
        get { defaults.string(forKey: Key.lastSyncID) ?? "" }
        set { defaults.set(newValue, forKey: Key.lastSyncID) }
    }

    var lastSyncAt: Date? {
        get { defaults.object(forKey: Key.lastSyncAt) as? Date }
        set { defaults.set(newValue, forKey: Key.lastSyncAt) }
    }
}
