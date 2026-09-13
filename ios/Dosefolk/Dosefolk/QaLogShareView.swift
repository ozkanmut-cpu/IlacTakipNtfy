import SwiftUI

struct QaLogShareView: View {
    @State private var exportURL: URL?
    @State private var preparing = false
    @State private var errorMessage: String?

    var body: some View {
        Group {
            if let exportURL {
                ShareLink(item: exportURL) {
                    Label("Tanılama kaydını paylaş", systemImage: "square.and.arrow.up")
                }
            } else {
                Button {
                    prepareExport()
                } label: {
                    Label(preparing ? "Tanılama kaydı hazırlanıyor…" : "Tanılama kaydını paylaş", systemImage: "square.and.arrow.up")
                }
                .disabled(preparing)
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func prepareExport() {
        guard !preparing else { return }
        preparing = true
        errorMessage = nil

        Task {
            await DosefolkQaLog.shared.record(category: .APP, event: "qa_export_requested")
            do {
                let url = try await DosefolkQaLog.shared.exportURL()
                await MainActor.run {
                    exportURL = url
                    preparing = false
                }
            } catch {
                await MainActor.run {
                    exportURL = nil
                    preparing = false
                    errorMessage = "Tanılama kaydı hazırlanamadı."
                }
            }
        }
    }
}
