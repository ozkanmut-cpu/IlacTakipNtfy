import SwiftUI

@main
struct DosefolkApp: App {
    private let runtime: DosefolkRuntime?
    private let startupError: String?
    private let backgroundSync: BackgroundSyncManager?

    init() {
        do {
            let createdRuntime = try DosefolkRuntime()
            runtime = createdRuntime
            startupError = nil

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

    var body: some Scene {
        WindowGroup {
            ContentView(runtime: runtime, startupError: startupError)
        }
    }
}
