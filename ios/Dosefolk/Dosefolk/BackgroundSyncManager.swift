import BackgroundTasks
import Foundation

final class BackgroundSyncManager {
    static let refreshIdentifier = "com.ozkanmut.dosefolk.refresh"

    private let pullOnce: () async throws -> Int
    private let earliestDelay: TimeInterval

    init(
        earliestDelay: TimeInterval = 30 * 60,
        pullOnce: @escaping () async throws -> Int
    ) {
        self.earliestDelay = earliestDelay
        self.pullOnce = pullOnce
    }

    @discardableResult
    func register() -> Bool {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.refreshIdentifier,
            using: nil
        ) { [weak self] task in
            guard let self, let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            self.handle(refreshTask)
        }
    }

    func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: earliestDelay)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Background refresh is opportunistic; foreground and APNs paths remain available.
        }
    }

    private func handle(_ task: BGAppRefreshTask) {
        schedule()
        let work = Task {
            do {
                _ = try await pullOnce()
                guard !Task.isCancelled else {
                    task.setTaskCompleted(success: false)
                    return
                }
                task.setTaskCompleted(success: true)
            } catch {
                task.setTaskCompleted(success: false)
            }
        }
        task.expirationHandler = {
            work.cancel()
        }
    }
}
