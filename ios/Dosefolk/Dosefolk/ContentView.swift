import SwiftUI
import UserNotifications

struct ContentView: View {
    let runtime: DosefolkRuntime?
    let startupError: String?

    @Environment(\.scenePhase) private var scenePhase
    @State private var notificationStatus: UNAuthorizationStatus?
    @State private var notificationError: String?
    @State private var todayItems: [TodayDoseItem] = []
    @State private var todayError: String?
    @State private var busyDoseID: String?

    private let timeZoneDidChange = Notification.Name("NSSystemTimeZoneDidChangeNotification")
    private let calendarDayDidChange = Notification.Name("NSCalendarDayChangedNotification")

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Dosefolk")
                            .font(.largeTitle.bold())
                        Text(todayItems.isEmpty ? "Bugün planlı doz görünmüyor." : "Bugünkü ilaçların burada.")
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
                }

                Section("Bugünkü dozlar") {
                    todayDoseContent
                }

                Section("Hatırlatmalar") {
                    notificationPermissionContent
                }

                Section("Circle") {
                    if let runtime {
                        NavigationLink("Birini Circle’a ekle") {
                            CirclePairingView(
                                ownPayload: runtime.ownPairingPayload,
                                addPair: { raw, name in
                                    try await runtime.addPair(rawPayload: raw, fallbackName: name)
                                }
                            )
                            .padding()
                            .navigationTitle("Circle")
                        }
                    } else {
                        Text("Circle başlatılamadı.")
                            .foregroundStyle(.secondary)
                    }
                }

                if let startupError, !startupError.isEmpty {
                    Section("Başlatma") {
                        Text(startupError)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Bugün")
            .task {
                runtime?.start()
                await refreshNotificationStatus()
                refreshToday()
            }
            .onChange(of: scenePhase) { _, newPhase in
                guard newPhase == .active else { return }
                Task {
                    await refreshNotificationStatus()
                    runtime?.programDidChange()
                    refreshToday()
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: timeZoneDidChange)) { _ in
                runtime?.programDidChange()
                refreshToday()
            }
            .onReceive(NotificationCenter.default.publisher(for: calendarDayDidChange)) { _ in
                runtime?.programDidChange()
                refreshToday()
            }
        }
    }

    @ViewBuilder
    private var todayDoseContent: some View {
        if let todayError {
            VStack(alignment: .leading, spacing: 8) {
                Text(todayError)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("Tekrar yükle") { refreshToday() }
            }
        } else if todayItems.isEmpty {
            Text("Bugün için kayıtlı bir doz yok.")
                .foregroundStyle(.secondary)
        } else {
            ForEach(todayItems) { item in
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(item.time)
                            .font(.title3.monospacedDigit().bold())
                        Spacer()
                        statusLabel(item.status)
                    }

                    Text(item.medications.map { medicationText($0) }.joined(separator: " • "))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    doseActions(item)
                }
                .padding(.vertical, 4)
            }
        }
    }

    @ViewBuilder
    private func doseActions(_ item: TodayDoseItem) -> some View {
        let isBusy = busyDoseID == item.id
        switch item.status {
        case .unknown, .pending:
            HStack {
                Button("Aldım") { runAction(.taken, item: item) }
                    .buttonStyle(.borderedProminent)
                Button("30 dk ertele") { runAction(.snooze, item: item) }
                    .buttonStyle(.bordered)
                Button("Almadım") { runAction(.missed, item: item) }
                    .buttonStyle(.bordered)
            }
            .disabled(isBusy)
        case .snoozed:
            HStack {
                Button("Aldım") { runAction(.taken, item: item) }
                    .buttonStyle(.borderedProminent)
                Button("Almadım") { runAction(.missed, item: item) }
                    .buttonStyle(.bordered)
            }
            .disabled(isBusy)
        case .taken, .missed:
            Button("Geri al") { runCorrection(.undo, item: item) }
                .buttonStyle(.bordered)
                .disabled(isBusy)
        case .conflict:
            HStack {
                Button("Aldım olarak düzelt") { runCorrection(.correctToTaken, item: item) }
                    .buttonStyle(.borderedProminent)
                Button("Almadım olarak düzelt") { runCorrection(.correctToMissed, item: item) }
                    .buttonStyle(.bordered)
            }
            .disabled(isBusy)
        }
    }

    @ViewBuilder
    private func statusLabel(_ status: DoseSessionStatus) -> some View {
        switch status {
        case .taken:
            Label("Alındı", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .missed:
            Label("Alınmadı", systemImage: "xmark.circle.fill")
                .foregroundStyle(.red)
        case .snoozed:
            Label("Ertelendi", systemImage: "clock.fill")
                .foregroundStyle(.orange)
        case .conflict:
            Label("Kontrol gerekli", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        case .unknown, .pending:
            Label("Bekliyor", systemImage: "circle")
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var notificationPermissionContent: some View {
        if runtime == nil {
            Text("Hatırlatmalar başlatılamadı.")
                .foregroundStyle(.secondary)
        } else if let notificationError {
            Text(notificationError)
                .font(.footnote)
                .foregroundStyle(.secondary)
            Button("Tekrar dene") {
                Task { await requestNotificationPermission() }
            }
        } else {
            switch notificationStatus {
            case .notDetermined:
                VStack(alignment: .leading, spacing: 8) {
                    Text("İlaç saatlerinde bildirim almak için iznini aç.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Bildirimleri aç") {
                        Task { await requestNotificationPermission() }
                    }
                }
            case .denied:
                VStack(alignment: .leading, spacing: 6) {
                    Label("Bildirimler kapalı", systemImage: "bell.slash")
                    Text("iPhone Ayarlar > Bildirimler > Dosefolk bölümünden bildirimlere izin ver.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            case .authorized, .provisional, .ephemeral:
                Label("Bildirimler açık", systemImage: "checkmark.circle.fill")
            case .none:
                ProgressView("Bildirim durumu kontrol ediliyor…")
            @unknown default:
                Text("Bildirim durumu kullanılamıyor.")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func medicationText(_ medication: Medication) -> String {
        medication.dose.isEmpty ? medication.name : "\(medication.name) \(medication.dose)"
    }

    @MainActor
    private func refreshToday() {
        guard let runtime else {
            todayItems = []
            return
        }
        do {
            todayItems = try TodayDoseLoader(store: runtime.store).load()
            todayError = nil
        } catch {
            todayError = "Bugünkü dozlar yüklenemedi."
        }
    }

    private func runAction(_ action: DoseUserAction, item: TodayDoseItem) {
        guard let runtime else { return }
        busyDoseID = item.id
        Task {
            do {
                _ = try await runtime.doseActionService.apply(
                    action,
                    time: item.time,
                    scheduledDate: item.scheduledDate,
                    medications: item.medications
                )
                await MainActor.run {
                    busyDoseID = nil
                    refreshToday()
                }
            } catch {
                await MainActor.run {
                    busyDoseID = nil
                    todayError = "Doz işlemi tamamlanamadı."
                }
            }
        }
    }

    private func runCorrection(_ intent: DoseCorrectionIntent, item: TodayDoseItem) {
        guard let runtime else { return }
        busyDoseID = item.id
        Task {
            do {
                _ = try await runtime.doseCorrectionService.apply(
                    intent,
                    time: item.time,
                    scheduledDate: item.scheduledDate
                )
                await MainActor.run {
                    busyDoseID = nil
                    refreshToday()
                }
            } catch {
                await MainActor.run {
                    busyDoseID = nil
                    todayError = "Düzeltme tamamlanamadı."
                }
            }
        }
    }

    @MainActor
    private func refreshNotificationStatus() async {
        guard let runtime else { return }
        notificationStatus = await runtime.notificationAuthorizationStatus()
    }

    @MainActor
    private func requestNotificationPermission() async {
        guard let runtime else { return }
        notificationError = nil
        do {
            _ = try await runtime.requestNotificationAuthorization()
            notificationStatus = await runtime.notificationAuthorizationStatus()
        } catch {
            notificationError = "Bildirim izni alınamadı. Daha sonra tekrar deneyebilirsin."
        }
    }
}

#Preview {
    ContentView(runtime: nil, startupError: nil)
}
