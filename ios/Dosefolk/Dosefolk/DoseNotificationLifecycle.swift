import Foundation
import UserNotifications

protocol DoseNotificationManaging {
    func resolve(time: String, scheduledDate: String)
    func snooze(time: String, scheduledDate: String, untilMillis: Int64)
}

struct DoseNotificationLifecycle: DoseNotificationManaging {
    private let center: UNUserNotificationCenter
    private let now: () -> Date

    init(
        center: UNUserNotificationCenter = .current(),
        now: @escaping () -> Date = Date.init
    ) {
        self.center = center
        self.now = now
    }

    func resolve(time: String, scheduledDate: String) {
        let identifiers = [
            Self.baseIdentifier(time: time, scheduledDate: scheduledDate),
            Self.snoozeIdentifier(time: time, scheduledDate: scheduledDate)
        ]
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    func snooze(time: String, scheduledDate: String, untilMillis: Int64) {
        resolve(time: time, scheduledDate: scheduledDate)
        let target = Date(timeIntervalSince1970: TimeInterval(untilMillis) / 1000.0)
        let interval = target.timeIntervalSince(now())
        guard interval > 0 else { return }

        let content = UNMutableNotificationContent()
        content.title = "Dosefolk"
        content.body = "İlaç zamanın geldi."
        content.sound = .default
        content.categoryIdentifier = "DOSE_REMINDER"

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, interval), repeats: false)
        let request = UNNotificationRequest(
            identifier: Self.snoozeIdentifier(time: time, scheduledDate: scheduledDate),
            content: content,
            trigger: trigger
        )
        center.add(request) { _ in }
    }

    static func baseIdentifier(time: String, scheduledDate: String) -> String {
        "dose|\(scheduledDate)|\(time)"
    }

    static func snoozeIdentifier(time: String, scheduledDate: String) -> String {
        "dose-snooze|\(scheduledDate)|\(time)"
    }
}
