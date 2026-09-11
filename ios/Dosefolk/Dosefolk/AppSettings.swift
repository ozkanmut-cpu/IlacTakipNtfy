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

    var lastSyncID: String {
        get { defaults.string(forKey: Key.lastSyncID) ?? "" }
        set { defaults.set(newValue, forKey: Key.lastSyncID) }
    }

    var lastSyncAt: Date? {
        get { defaults.object(forKey: Key.lastSyncAt) as? Date }
        set { defaults.set(newValue, forKey: Key.lastSyncAt) }
    }
}
