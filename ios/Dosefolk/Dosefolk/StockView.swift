import SwiftUI

struct StockView: View {
    let runtime: DosefolkRuntime

    @State private var medications: [Medication] = []
    @State private var stocks: [StockState] = []
    @State private var packInputs: [String: String] = [:]
    @State private var busyMedicationID: String?
    @State private var errorMessage: String?

    var body: some View {
        List {
            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.secondary)
                    Button("Tekrar yükle") { Task { await reload() } }
                }
            }

            Section("Takip edilenler") {
                if tracked.isEmpty {
                    Text("Henüz stok takibi yok.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(tracked, id: \.medication.id) { row in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(row.medication.name)
                                        .font(.body.weight(.semibold))
                                    Text("\(row.stock.remainingDoses) doz kaldı")
                                        .font(.title3.weight(.bold))
                                }
                                Spacer()
                                if row.stock.remainingDoses <= row.stock.lowThreshold {
                                    Label("Düşük", systemImage: "exclamationmark.triangle.fill")
                                        .font(.caption)
                                        .foregroundStyle(.orange)
                                }
                            }

                            Text("Kutu: \(row.stock.packSize) doz")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)

                            Button("Yeni kutu açtım") {
                                Task { await openNewBox(row.medication.id) }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(busyMedicationID != nil)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }

            if !untracked.isEmpty {
                Section("Stok takibi ekle") {
                    ForEach(untracked) { medication in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(medication.name)
                                .font(.body.weight(.semibold))
                            TextField(
                                "Kutudaki doz sayısı",
                                text: Binding(
                                    get: { packInputs[medication.id, default: ""] },
                                    set: { packInputs[medication.id] = $0.filter(\.isNumber) }
                                )
                            )
                            .keyboardType(.numberPad)

                            Button("Takibi başlat") {
                                Task { await configure(medication) }
                            }
                            .buttonStyle(.bordered)
                            .disabled((Int(packInputs[medication.id, default: ""]) ?? 0) <= 0 || busyMedicationID != nil)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
        }
        .navigationTitle("Stok")
        .task { await reload() }
    }

    private var tracked: [(medication: Medication, stock: StockState)] {
        let byID = Dictionary(uniqueKeysWithValues: stocks.map { ($0.medicationId, $0) })
        return medications.compactMap { medication in
            guard let stock = byID[medication.id] else { return nil }
            return (medication, stock)
        }
    }

    private var untracked: [Medication] {
        let trackedIDs = Set(stocks.map(\.medicationId))
        return medications.filter { !trackedIDs.contains($0.id) }
    }

    @MainActor
    private func reload() async {
        do {
            medications = try runtime.store.load([Medication].self, from: .medications, default: [])
            stocks = try await runtime.stockStates()
            errorMessage = nil
        } catch {
            errorMessage = "Stok bilgileri yüklenemedi."
        }
    }

    @MainActor
    private func configure(_ medication: Medication) async {
        guard let packSize = Int(packInputs[medication.id, default: ""]), packSize > 0 else { return }
        busyMedicationID = medication.id
        do {
            try await runtime.configureStock(for: medication, packSize: packSize)
            packInputs[medication.id] = ""
            busyMedicationID = nil
            await reload()
        } catch {
            busyMedicationID = nil
            errorMessage = "Stok takibi başlatılamadı."
        }
    }

    @MainActor
    private func openNewBox(_ medicationID: String) async {
        busyMedicationID = medicationID
        do {
            _ = try await runtime.openNewBox(medicationID: medicationID)
            busyMedicationID = nil
            await reload()
        } catch {
            busyMedicationID = nil
            errorMessage = "Stok güncellenemedi."
        }
    }
}
