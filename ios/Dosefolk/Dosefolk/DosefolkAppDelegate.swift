import UIKit

final class DosefolkAppDelegate: NSObject, UIApplicationDelegate {
    static var onDeviceToken: ((Data) -> Void)?
    static var onBackgroundWake: (() async -> Bool)?
    static private(set) var latestDeviceToken: Data?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        application.registerForRemoteNotifications()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Self.handleDeviceToken(deviceToken)
    }

    static func handleDeviceToken(_ deviceToken: Data) {
        latestDeviceToken = deviceToken
        onDeviceToken?(deviceToken)
    }

    static func retryDeviceTokenRegistration() {
        guard let latestDeviceToken else { return }
        onDeviceToken?(latestDeviceToken)
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard Self.isWakePayload(userInfo), let onBackgroundWake = Self.onBackgroundWake else {
            completionHandler(.noData)
            return
        }
        Task {
            let changed = await onBackgroundWake()
            completionHandler(changed ? .newData : .noData)
        }
    }

    static func isWakePayload(_ userInfo: [AnyHashable: Any]) -> Bool {
        guard let aps = userInfo["aps"] as? [String: Any] else { return false }
        if let value = aps["content-available"] as? Int { return value == 1 }
        if let value = aps["content-available"] as? NSNumber { return value.intValue == 1 }
        return false
    }
}
