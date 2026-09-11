import Foundation

final class NtfyRateGate {
    private enum Key { static let blockedUntil = "ntfyBlockedUntil" }
    private static let minimumBackoff: TimeInterval = 30
    private static let defaultBackoff: TimeInterval = 60
    private static let maximumBackoff: TimeInterval = 6 * 60 * 60

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var blockedUntil: Date? {
        let value = defaults.double(forKey: Key.blockedUntil)
        return value > 0 ? Date(timeIntervalSince1970: value) : nil
    }

    func isBlocked(at now: Date = Date()) -> Bool {
        guard let blockedUntil else { return false }
        return blockedUntil > now
    }

    @discardableResult
    func record429(retryAfter: String?, now: Date = Date()) -> Date {
        let until = Self.retryAfterDate(retryAfter, now: now)
        defaults.set(until.timeIntervalSince1970, forKey: Key.blockedUntil)
        return until
    }

    func clearAfterSuccess() {
        defaults.removeObject(forKey: Key.blockedUntil)
    }

    static func retryAfterDate(_ header: String?, now: Date = Date()) -> Date {
        let rawDelay: TimeInterval
        if let value = header?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty {
            if let seconds = TimeInterval(value) {
                rawDelay = seconds
            } else if let date = httpDate(value) {
                rawDelay = date.timeIntervalSince(now)
            } else {
                rawDelay = defaultBackoff
            }
        } else {
            rawDelay = defaultBackoff
        }
        let clamped = min(max(rawDelay, minimumBackoff), maximumBackoff)
        return now.addingTimeInterval(clamped)
    }

    private static func httpDate(_ value: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter.date(from: value)
    }
}
