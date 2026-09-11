import SwiftUI

struct CirclePairingView: View {
    let ownPayload: String
    let addPair: (_ rawPayload: String, _ fallbackName: String) async throws -> CirclePeer

    @State private var showOwnQR = false
    @State private var showScanner = false
    @State private var showManual = false
    @State private var manualCode = ""
    @State private var manualName = ""
    @State private var message = ""
    @State private var working = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Birini Circle’a ekle").font(.headline)
            Text("Bir telefon QR’ı göstersin, diğeri tarasın. Sonra tersini yapın; Dosefolk bağlantıyı kendisi doğrular.")
                .font(.footnote).foregroundStyle(.secondary)

            HStack {
                Button(showOwnQR ? "QR’ı gizle" : "QR göster") { showOwnQR.toggle() }
                    .buttonStyle(.borderedProminent)
                Button("QR tara") { showScanner = true }
                    .buttonStyle(.borderedProminent)
                    .disabled(working)
            }

            if showOwnQR, let image = PairingQRImage.make(ownPayload) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 240)
                    .accessibilityLabel("Dosefolk eşleştirme QR kodu")
                Text("Diğer telefondan bu QR’ı okut.")
                    .font(.footnote).foregroundStyle(.secondary)
            }

            if !message.isEmpty {
                Text(message).font(.footnote)
            }

            Button(showManual ? "Manuel kodu gizle" : "QR çalışmıyor mu?") { showManual.toggle() }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)

            if showManual {
                TextField("Ad", text: $manualName)
                    .textFieldStyle(.roundedBorder)
                TextField("Eşleştirme kodu", text: $manualCode)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button(working ? "Kontrol ediliyor…" : "Circle’a ekle") {
                    submit(manualCode, fallbackName: manualName)
                }
                .buttonStyle(.borderedProminent)
                .disabled(working || manualCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .sheet(isPresented: $showScanner) {
            NavigationStack {
                CirclePairingScanner(
                    onCode: { value in
                        showScanner = false
                        submit(value, fallbackName: "")
                    },
                    onFailure: { error in
                        showScanner = false
                        message = "QR tarayıcı açılamadı. \(error)"
                    }
                )
                .navigationTitle("QR tara")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Kapat") { showScanner = false }
                    }
                }
            }
        }
    }

    private func submit(_ raw: String, fallbackName: String) {
        guard !working else { return }
        working = true
        message = "Kontrol ediliyor…"
        Task {
            do {
                let peer = try await addPair(raw, fallbackName)
                await MainActor.run {
                    working = false
                    manualCode = ""
                    manualName = ""
                    message = "\(peer.name.isEmpty ? "Kişi" : peer.name) Circle’a eklendi. Şimdi diğer telefonda bu telefonun QR’ını tara."
                }
            } catch {
                await MainActor.run {
                    working = false
                    message = pairingMessage(for: error)
                }
            }
        }
    }

    private func pairingMessage(for error: Error) -> String {
        switch error as? CirclePairingServiceError {
        case .invalidPayload: return "Bu Dosefolk eşleştirme QR’ı değil."
        case .selfPair: return "Bu QR bu telefona ait."
        case .duplicatePeer: return "Bu kişi zaten Circle’da."
        case nil: return "Eşleştirme tamamlanamadı. İnternet bağlantısını kontrol edip tekrar dene."
        }
    }
}
