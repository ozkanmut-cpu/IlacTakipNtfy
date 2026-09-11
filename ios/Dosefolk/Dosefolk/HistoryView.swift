import SwiftUI

struct HistoryView: View {
    let runtime: DosefolkRuntime

    @State private var days: [HistoryDay] = []
    @State private var errorMessage: String?

    var body: some View {
        List {
            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.secondary)
                    Button("Tekrar yükle") { reload() }
                }
            } else if days.isEmpty {
                ContentUnavailableView(
                    "Henüz geçmiş yok",
                    systemImage: "clock.arrow.circlepath",
                    description: Text("İlaç aldığında, ertelediğinde veya bir kaydı düzelttiğinde burada görünecek.")
                )
            } else {
                ForEach(days) { day in
                    Section(dayTitle(day.date)) {
                        ForEach(day.entries) { entry in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(entry.title)
                                    .font(.body.weight(.semibold))
                                if !entry.detail.isEmpty {
                                    Text(entry.detail)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }
            }
        }
        .navigationTitle("Geçmiş")
        .task { reload() }
    }

    @MainActor
    private func reload() {
        do {
            let events = try DoseEventStore(store: runtime.store).all()
            days = HistoryPresentation.days(from: events)
            errorMessage = nil
        } catch {
            errorMessage = "Geçmiş yüklenemedi."
        }
    }

    private func dayTitle(_ value: String) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "tr_TR")
        formatter.dateFormat = "yyyy-MM-dd"
        guard let date = formatter.date(from: value) else { return value }
        formatter.dateStyle = .full
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }
}
