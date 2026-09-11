import Foundation
import UserNotifications

actor DoseNotificationScheduler {
    private let center: UNUserNotificationCenter
    private let store: LocalStore
    private let calendar: Calendar

    init(
        center: UNUserNotificationCenter = .current(),
        store: LocalStore,
        calendar: Calendar = .current
    ) {
        self.center = center
        self.store = store
        self.calendar = calendar
    }

    @discardableResult
    func requestAuthorization() async throws -> Bool {
        try await center.requestAuthorization(options: [.alert, .sound, .badge])
    }

    func reconcile(now: Date = Date()) async throws {
        let medications = try store.load([Medication].self, from: .medications, default: [])
        let rules = try store.load([ProgramRule].self, from: .programRules, default: [])
        let plan = DoseSchedulePlanner.plan(
            medications: medications,
            rules: rules,
            now: now,
            horizonDays: 14,
            maxRequests: 48,
            calendar: calendar
        )

        let pending = await center.pendingNotificationRequests()
        let regularIDs = pending.map(\.identifier).filter { $0.hasPrefix("dose|") }
        center.removePendingNotificationRequests(withIdentifiers: regularIDs)

        for item in plan {
            let content = UNMutableNotificationContent()
            content.title = "\(item.time) • \(item.medications.count) ilaç"
            content.body = item.medications.map(\.name).joined(separator: ", ")
            content.sound = .default
            content.categoryIdentifier = DoseNotificationActionIdentifier.category
            content.userInfo = [
                "time": item.time,
                "scheduledDate": item.scheduledDate,
                "medicationIDs": item.medications.map(\.id)
            ]
            let components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: item.fireDate)
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            let request = UNNotificationRequest(
                identifier: DoseNotificationLifecycle.baseIdentifier(time: item.time, scheduledDate: item.scheduledDate),
                content: content,
                trigger: trigger
            )
            try await center.add(request)
        }
    }
}
