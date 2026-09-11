import SwiftUI

struct ContentView: View {
    let runtime: DosefolkRuntime?
    let startupError: String?

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
            .task { runtime?.start() }
        }
    }
}

#Preview {
    ContentView(runtime: nil, startupError: nil)
}
