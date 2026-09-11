import Foundation
import UserNotifications

final class DosefolkRuntime {
    let settings: AppSettings
    let store: LocalStore
    let transport: CircleTransport
    let coordinator: CircleSyncCoordinator
    let pairingService: CirclePairingService
    let localStockEngine: LocalStockEngine
    let doseActionService: DoseActionService
    let doseCorrectionService: DoseCorrectionService
    let notificationRouter: DoseNotificationRouter
    let notificationScheduler: DoseNotificationScheduler

    init(settings: AppSettings = AppSettings(), store: LocalStore? = nil) throws {
        self.settings = settings
        let localTopic = settings.ensureLocalTopic()
        let resolvedStore = try store ?? LocalStore()
        self.store = resolvedStore

        let scheduler = DoseNotificationScheduler(store: resolvedStore)
        self.notificationScheduler = scheduler
        let stockEngine = LocalStockEngine(store: resolvedStore)
        self.localStockEngine = stockEngine

        let transport = CircleTransport(settings: settings, store: resolvedStore)
        self.transport = transport
        let reducer = RemoteStateReducer(store: resolvedStore, localOwnerId: localTopic)
        self.coordinator = CircleSyncCoordinator(
            localTopic: localTopic,
            store: resolvedStore,
            topics: { (try? transport.subscriptionTopics()) ?? [localTopic] },
            remoteStateHandler: { event in
                try reducer.apply(event)
                if DoseNotificationReschedule.shouldReconcile(event: event, localTopic: localTopic) {
                    Task { try? await scheduler.reconcile() }
                }
            }
        )

        let publisher = ProtocolEventPublisher(settings: settings, store: resolvedStore)
        let doseActionService = DoseActionService(
            store: resolvedStore,
            publisher: publisher,
            stockEngine: stockEngine
        )
        self.doseActionService = doseActionService
        self.doseCorrectionService = DoseCorrectionService(
            store: resolvedStore,
            publisher: publisher,
            stockEngine: stockEngine
        )
        self.notificationRouter = DoseNotificationRouter(store: resolvedStore, service: doseActionService)

        let lifecycle = CirclePairingLifecycle(localTopic: localTopic, store: resolvedStore)
        let initialSync = CircleInitialSyncPublisher(
            store: resolvedStore,
            publisher: publisher,
            localTopic: localTopic,
            displayName: settings.displayName
        )
        self.pairingService = CirclePairingService(
            settings: settings,
            store: resolvedStore,
            lifecycle: lifecycle,
            initialSyncPublisher: initialSync
        )
    }

    var ownPairingPayload: String {
        CirclePairingPayload.encode(topic: settings.localTopic, name: settings.displayName) ?? settings.localTopic
    }

    func start() {
        notificationRouter.activate()
        Task { try? await notificationScheduler.reconcile() }
        coordinator.start()
    }

    func stop() {
        coordinator.stop()
    }

    func notificationAuthorizationStatus() async -> UNAuthorizationStatus {
        await notificationScheduler.authorizationStatus()
    }

    @discardableResult
    func requestNotificationAuthorization() async throws -> Bool {
        let granted = try await notificationScheduler.requestAuthorization()
        if granted {
            try await notificationScheduler.reconcile()
        }
        return granted
    }

    func programDidChange() {
        Task { try? await notificationScheduler.reconcile() }
    }

    func stockStates() async throws -> [StockState] {
        try await localStockEngine.all()
    }

    func configureStock(for medication: Medication, packSize: Int, lowThreshold: Int = 5) async throws {
        guard packSize > 0 else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try await localStockEngine.configure(StockState(
            medicationId: medication.id,
            medicationName: medication.name,
            remainingDoses: packSize,
            packSize: packSize,
            lowThreshold: max(0, lowThreshold),
            updatedAt: now
        ))
        try await publishStockToCircle()
    }

    @discardableResult
    func openNewBox(medicationID: String) async throws -> StockState? {
        let updated = try await localStockEngine.openNewBox(medicationID: medicationID)
        if updated != nil {
            try await publishStockToCircle()
        }
        return updated
    }

    @discardableResult
    func addPair(rawPayload: String, fallbackName: String) async throws -> CirclePeer {
        let peer = try await pairingService.add(rawPayload: rawPayload, fallbackName: fallbackName)
        coordinator.stop()
        coordinator.start()
        return peer
    }

    private func publishStockToCircle() async throws {
        let peers = try store.load([CirclePeer].self, from: .circlePeers, default: [])
        guard !peers.isEmpty else { return }
        let publisher = StockSyncPublisher(
            localTopic: settings.localTopic,
            displayName: settings.displayName,
            store: store
        )
        for peer in peers where !peer.topic.isEmpty && peer.topic != settings.localTopic {
            try await publisher.publishAll(to: peer.topic)
        }
    }
}
