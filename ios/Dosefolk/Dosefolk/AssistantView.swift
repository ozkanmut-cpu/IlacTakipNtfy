import SwiftUI

struct AssistantView: View {
    let runtime: DosefolkRuntime

    @State private var summary = AssistantSummary(
        priority: .allGood,
        title: "Kontrol ediliyor…",
        detail: "",
        targetDoseID: nil
    )
    @State private var errorMessage: String?

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Image(systemName: iconName)
                        .font(.system(size: 34, weight: .semibold))
                        .accessibilityHidden(true)
                    Text(summary.title)
                        .font(.title2.bold())
                    Text(summary.detail)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 8)
                .accessibilityElement(children: .combine)
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.secondary)
                    Button("Tekrar kontrol et") { reload() }
                }
            } else {
                Section("Ne yapabilirim?") {
                    switch summary.priority {
                    case .conflict, .doseDue:
                        Text("Bugün ekranına dönüp ilgili dozun tek belirgin aksiyonunu kullan.")
                            .foregroundStyle(.secondary)
                    case .lowStock:
                        NavigationLink {
                            StockView(runtime: runtime)
                        } label: {
                            Label("Stoku aç", systemImage: "shippingbox")
                        }
                    case .allGood:
                        Text("Ek işlem gerekmiyor.")
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Asistan")
        .task { reload() }
    }

    private var iconName: String {
        switch summary.priority {
        case .conflict: return "exclamationmark.triangle.fill"
        case .doseDue: return "pills.fill"
        case .lowStock: return "shippingbox.fill"
        case .allGood: return "checkmark.circle.fill"
        }
    }

    @MainActor
    private func reload() {
        do {
            let today = try TodayDoseLoader(store: runtime.store).load()
            Task {
                do {
                    let stock = try await runtime.stockStates()
                    await MainActor.run {
                        summary = AssistantSummaryBuilder.build(today: today, stock: stock)
                        errorMessage = nil
                    }
                } catch {
                    await MainActor.run { errorMessage = "Asistan durumu yüklenemedi." }
                }
            }
        } catch {
            errorMessage = "Asistan durumu yüklenemedi."
        }
    }
}
