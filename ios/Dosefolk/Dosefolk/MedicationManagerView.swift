import SwiftUI

struct MedicationManagerView: View {
    let runtime: DosefolkRuntime

    @State private var medications: [Medication] = []
    @State private var editing: Medication?
    @State private var showingNew = false
    @State private var errorMessage: String?

    var body: some View {
        List {
            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(.secondary)
                    Button("Tekrar yükle") { reload() }
                }
            }

            if medications.isEmpty {
                ContentUnavailableView(
                    "Henüz ilaç yok",
                    systemImage: "pills",
                    description: Text("İlk ilacını ekleyerek başlayabilirsin.")
                )
            } else {
                ForEach(medications) { medication in
                    Button {
                        editing = medication
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(medication.name)
                                .font(.body.weight(.semibold))
                                .foregroundStyle(.primary)
                            Text(summary(medication))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .onDelete { indexSet in
                    let ids = indexSet.map { medications[$0].id }
                    Task { await delete(ids) }
                }
            }
        }
        .navigationTitle("İlaçlar")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showingNew = true
                } label: {
                    Label("İlaç ekle", systemImage: "plus")
                }
            }
        }
        .task { reload() }
        .sheet(isPresented: $showingNew) {
            MedicationEditorView(runtime: runtime, medication: nil) {
                showingNew = false
                reload()
            }
        }
        .sheet(item: $editing) { medication in
            MedicationEditorView(runtime: runtime, medication: medication) {
                editing = nil
                reload()
            }
        }
    }

    @MainActor
    private func reload() {
        do {
            medications = try runtime.store.load([Medication].self, from: .medications, default: [])
            errorMessage = nil
        } catch {
            errorMessage = "İlaçlar yüklenemedi."
        }
    }

    @MainActor
    private func delete(_ ids: [String]) async {
        do {
            for id in ids {
                try await runtime.medicationProgramService.delete(medicationID: id)
            }
            reload()
        } catch {
            errorMessage = "İlaç silinemedi."
        }
    }

    private func summary(_ medication: Medication) -> String {
        let dose = medication.dose.isEmpty ? "" : "\(medication.dose) • "
        return dose + medication.times.joined(separator: ", ")
    }
}

private struct MedicationEditorView: View {
    let runtime: DosefolkRuntime
    let medication: Medication?
    let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var dose: String
    @State private var times: [String]
    @State private var newTime = "08:00"
    @State private var showAdvanced = false
    @State private var weekdays: Set<Int>
    @State private var busy = false
    @State private var errorMessage: String?

    init(runtime: DosefolkRuntime, medication: Medication?, onSaved: @escaping () -> Void) {
        self.runtime = runtime
        self.medication = medication
        self.onSaved = onSaved
        _name = State(initialValue: medication?.name ?? "")
        _dose = State(initialValue: medication?.dose ?? "")
        _times = State(initialValue: medication?.times ?? [])
        _weekdays = State(initialValue: [])
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Temel bilgiler") {
                    TextField("İlaç adı", text: $name)
                    TextField("Doz (isteğe bağlı)", text: $dose)
                }

                Section("Saatler") {
                    ForEach(times, id: \.self) { time in
                        HStack {
                            Text(time)
                                .monospacedDigit()
                            Spacer()
                            Button(role: .destructive) {
                                times.removeAll { $0 == time }
                            } label: {
                                Image(systemName: "minus.circle")
                            }
                            .buttonStyle(.borderless)
                        }
                    }

                    HStack {
                        TextField("08:00", text: $newTime)
                            .keyboardType(.numbersAndPunctuation)
                        Button("Ekle") { addTime() }
                            .disabled(MedicationProgramService.normalizeTime(newTime) == nil)
                    }
                }

                Section {
                    DisclosureGroup("Gelişmiş program", isExpanded: $showAdvanced) {
                        Text("Belirli günler seçmezsen ilaç her gün aktif olur.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        weekdayPicker
                    }
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(medication == nil ? "İlaç ekle" : "İlacı düzenle")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Vazgeç") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Kaydet") {
                        Task { await save() }
                    }
                    .disabled(busy || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || times.isEmpty)
                }
            }
        }
    }

    private var weekdayPicker: some View {
        let labels = [(1, "Pzt"), (2, "Sal"), (3, "Çar"), (4, "Per"), (5, "Cum"), (6, "Cmt"), (7, "Paz")]
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 8) {
            ForEach(labels, id: \.0) { item in
                Button(item.1) {
                    if weekdays.contains(item.0) {
                        weekdays.remove(item.0)
                    } else {
                        weekdays.insert(item.0)
                    }
                }
                .buttonStyle(weekdays.contains(item.0) ? .borderedProminent : .bordered)
            }
        }
    }

    private func addTime() {
        guard let normalized = MedicationProgramService.normalizeTime(newTime), !times.contains(normalized) else { return }
        times.append(normalized)
        times.sort()
    }

    @MainActor
    private func save() async {
        busy = true
        errorMessage = nil
        let id = medication?.id ?? UUID().uuidString
        let value = Medication(id: id, name: name, dose: dose, times: times)
        let rule = ProgramRule(medicationId: id, weekdays: weekdays)
        do {
            _ = try await runtime.medicationProgramService.upsert(medication: value, rule: rule)
            busy = false
            onSaved()
            dismiss()
        } catch {
            busy = false
            errorMessage = "İlaç kaydedilemedi."
        }
    }
}
