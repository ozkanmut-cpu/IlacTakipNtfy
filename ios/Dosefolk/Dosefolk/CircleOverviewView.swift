import SwiftUI

struct CircleOverviewView: View {
    let runtime: DosefolkRuntime

    @State private var peers: [CirclePeer] = []
    @State private var presence: [String: CirclePresenceEntry] = [:]
    @State private var errorMessage: String?

    var body: some View {
        List {
            Section {
                NavigationLink {
                    CirclePairingView(
                        ownPayload: runtime.ownPairingPayload,
                        addPair: { raw, name in
                            let peer = try await runtime.addPair(rawPayload: raw, fallbackName: name)
                            await MainActor.run { reload() }
                            return peer
                        }
                    )
                    .padding()
                    .navigationTitle("Circle’a ekle")
                } label: {
                    Label("Birini Circle’a ekle", systemImage: "qrcode.viewfinder")
                }
            }

            Section("Kişiler") {
                if peers.isEmpty {
                    Text("Henüz Circle’a eklenmiş kişi yok.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(peers) { peer in
                        HStack(spacing: 12) {
                            Image(systemName: "person.crop.circle.fill")
                                .font(.title2)
                                .foregroundStyle(.secondary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(peer.name.isEmpty ? "Circle üyesi" : peer.name)
                                    .font(.body.weight(.semibold))
                                Text(presenceText(for: peer.topic))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if presence[peer.topic] != nil {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(.green)
                            }
                        }
                    }
                }
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.secondary)
                    Button("Tekrar yükle") { reload() }
                }
            }
        }
        .navigationTitle("Circle")
        .task { reload() }
    }

    @MainActor
    private func reload() {
        do {
            peers = try runtime.store.load([CirclePeer].self, from: .circlePeers, default: [])
            presence = try runtime.store.load([String: CirclePresenceEntry].self, from: .circlePresence, default: [:])
            errorMessage = nil
        } catch {
            errorMessage = "Circle bilgileri yüklenemedi."
        }
    }

    private func presenceText(for topic: String) -> String {
        guard let entry = presence[topic] else { return "Eşleşti" }
        let date = Date(timeIntervalSince1970: TimeInterval(entry.lastSeenAt) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Locale(identifier: "tr_TR")
        formatter.unitsStyle = .full
        return "Son görüldü " + formatter.localizedString(for: date, relativeTo: Date())
    }
}
