import Foundation
import UserNotifications

enum DoseNotificationActionIdentifier {
    static let category = "DOSE_REMINDER"
    static let taken = "DOSE_TAKEN"
    static let snooze = "DOSE_SNOOZE_30"
    static let missed = "DOSE_MISSED"

    static func action(for identifier: String) -> DoseUserAction? {
        switch identifier {
        case taken: return .taken
        case snooze: return .snooze
        case missed: return .missed
        default: return nil
        }
    }
}

struct DoseNotificationPayload: Equatable {
    let time: String
    let scheduledDate: String
    let medicationIDs: [String]

    static func parse(_ userInfo: [AnyHashable: Any]) -> DoseNotificationPayload? {
        guard let time = userInfo["time"] as? String, !time.isEmpty,
              let scheduledDate = userInfo["scheduledDate"] as? String, !scheduledDate.isEmpty else {
            return nil
        }
        let ids: [String]
        if let values = userInfo["medicationIDs"] as? [String] {
            ids = values.filter { !$0.isEmpty }
        } else if let value = userInfo["medicationIDs"] as? String {
            ids = value.split(separator: ",").map(String.init).filter { !$0.isEmpty }
        } else {
            ids = []
        }
        return DoseNotificationPayload(time: time, scheduledDate: scheduledDate, medicationIDs: ids)
    }
}

final class DoseNotificationRouter: NSObject, UNUserNotificationCenterDelegate {
    private let center: UNUserNotificationCenter
    private let store: LocalStore
    private let service: DoseActionService

    init(center: UNUserNotificationCenter = .current(), store: LocalStore, service: DoseActionService) {
        self.center = center
        self.store = store
        self.service = service
        super.init()
    }

    func activate() {
        let actions = [
            UNNotificationAction(identifier: DoseNotificationActionIdentifier.taken, title: "İçtim"),
            UNNotificationAction(identifier: DoseNotificationActionIdentifier.snooze, title: "30 dk ertele"),
            UNNotificationAction(identifier: DoseNotificationActionIdentifier.missed, title: "İçilmedi")
        ]
        let category = UNNotificationCategory(
            identifier: DoseNotificationActionIdentifier.category,
            actions: actions,
            intentIdentifiers: [],
            options: []
        )
        center.setNotificationCategories([category])
        center.delegate = self
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard let action = DoseNotificationActionIdentifier.action(for: response.actionIdentifier),
              let payload = DoseNotificationPayload.parse(response.notification.request.content.userInfo) else {
            return
        }
        do {
            let all = try store.load([Medication].self, from: .medications, default: [])
            let selected = payload.medicationIDs.isEmpty
                ? all.filter { $0.times.contains(payload.time) }
                : all.filter { payload.medicationIDs.contains($0.id) }
            _ = try await service.apply(
                action,
                time: payload.time,
                scheduledDate: payload.scheduledDate,
                medications: selected
            )
        } catch {
            return
        }
    }
}
