import SwiftUI

struct PrnView: View {
    let runtime: DosefolkRuntime

    @State private var medications: [Medication] = []
    @State private var busyID: String?
    @State private var confirmation: String?
    @State private var errorMessage: String?

    var body: some View {
        List {
            Section {
                Text("Bu ekran yalnız gerektiğinde kullanılan bir ilacı kaydetmek içindir. Normal günlük planını etkilemez.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if medications.isEmpty {
                ContentUnavailableView(
                    "Kayıtlı ilaç yok",
                    systemImage: "pills",
                    description: Text("Önce İlaçlar bölümünden bir ilaç ekle.")
                )
            } else {
                Section("İlaç seç") {
                    ForEach(medications) { medication in
                        Button {
                            record(medication)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(medication.name)
                                        .foregroundStyle(.primary)
                                    if !medication.dose.isEmpty {
                                        Text(medication.dose)
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                if busyID == medication.id {
                                    ProgressView()
                                } else {
                                    Image(systemName: "checkmark.circle")
                                }
                            }
                        }
                        .disabled(busyID != nil)
                    }
                }
            }

            if let confirmation {
                Section {
                    Label(confirmation, systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                }
            }
            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("Gerektiğinde kullanım")
        .task { load() }
    }

    @MainActor
    private func load() {
        do {
            medications = try runtime.store.load([Medication].self, from: .medications, default: [])
                .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            errorMessage = nil
        } catch {
            errorMessage = "İlaçlar yüklenemedi."
        }
    }

    private func record(_ medication: Medication) {
        busyID = medication.id
        confirmation = nil
        errorMessage = nil
        Task {
            do {
                _ = try await runtime.prnDoseService.record(medication: medication)
                await MainActor.run {
                    busyID = nil
                    confirmation = "\(medication.name) kaydedildi."
                }
            } catch {
                await MainActor.run {
                    busyID = nil
                    errorMessage = "Kullanım kaydedilemedi."
                }
            }
        }
    }
}
