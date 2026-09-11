import SwiftUI
import UserNotifications

struct ContentView: View {
    let runtime: DosefolkRuntime?
    let startupError: String?

    @State private var notificationStatus: UNAuthorizationStatus?
    @State private var notificationError: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Dosefolk")
                            .font(.largeTitle.bold())
                        Text("İlaç takibin hazır.")
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
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
            }
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
