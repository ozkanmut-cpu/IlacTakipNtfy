import SwiftUI

@main
struct DosefolkApp: App {
    @UIApplicationDelegateAdaptor(DosefolkAppDelegate.self) private var appDelegate

    private let runtime: DosefolkRuntime?
    private let startupError: String?
    private let backgroundSync: BackgroundSyncManager?

    init() {
        do {
            let createdRuntime = try DosefolkRuntime()
            runtime = createdRuntime
            startupError = nil

            DosefolkAppDelegate.onDeviceToken = { token in
                Task {
                    let subscriptions = (try? createdRuntime.transport.subscriptionTopics()) ?? [createdRuntime.settings.localTopic]
                    let payload = APNsRegistration.makePayload(
                        token: token,
                        installId: createdRuntime.settings.ensureInstallId(),
                        localTopic: createdRuntime.settings.localTopic,
                        subscriptions: subscriptions,
                        bundleId: Bundle.main.bundleIdentifier ?? "com.ozkanmut.dosefolk"
                    )
                    _ = try? await PushGatewayClient().register(payload)
                }
            }
            DosefolkAppDelegate.onBackgroundWake = {
                do {
                    return try await createdRuntime.backgroundPullOnce() > 0
                } catch {
                    return false
                }
            }

            let manager = BackgroundSyncManager {
                try await createdRuntime.backgroundPullOnce()
            }
            _ = manager.register()
            manager.schedule()
            backgroundSync = manager
        } catch {
            runtime = nil
            startupError = error.localizedDescription
            backgroundSync = nil
        }
    }

    private func handleEnrollmentURL(_ url: URL) {
        guard let runtime,
              let enrollment = PushGatewayEnrollmentLink.parse(url),
              enrollment.installId == runtime.settings.ensureInstallId() else {
            return
        }

        let replayGuard = PushGatewayEnrollmentReplayGuard()
        guard !replayGuard.isConsumed(enrollment) else { return }

        Task {
            do {
                try await PushGatewayClient().provision(
                    installId: enrollment.installId,
                    ticket: enrollment.ticket
                )
                replayGuard.markConsumed(enrollment)
            } catch {
                // Leave the link unconsumed so a transient provisioning failure can be retried.
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView(runtime: runtime, startupError: startupError)
                .onOpenURL(perform: handleEnrollmentURL)
        }
    }
}
