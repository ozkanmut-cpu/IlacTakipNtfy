import SwiftUI

struct MedicationManagerView: View {
    let runtime: DosefolkRuntime

    @State private var medications: [Medication] = []
    @State private var metadata: [MedicationMeta] = []
    @State private var rules: [ProgramRule] = []
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

            if medications.isEmpty && errorMessage == nil {
                ContentUnavailableView(
                    "Henüz ilaç yok",
                    systemImage: "pills",
                    description: Text("İlk ilacını ekleyerek başlayabilirsin.")
                )
            } else if errorMessage == nil {
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
            MedicationEditorView(runtime: runtime, medication: nil, meta: nil, rule: nil) {
                showingNew = false
                reload()
            }
        }
        .sheet(item: $editing) { medication in
            MedicationEditorView(
                runtime: runtime,
                medication: medication,
                meta: metadata.first { $0.medicationId == medication.id },
                rule: rules.first(where: { $0.medicationId == medication.id })
            ) {
                editing = nil
                reload()
            }
        }
    }

    @MainActor
    private func reload() {
        do {
            medications = try runtime.store.load([Medication].self, from: .medications, default: [])
            metadata = try runtime.store.load([MedicationMeta].self, from: .medicationMeta, default: [])
            rules = try runtime.store.load([ProgramRule].self, from: .programRules, default: [])
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
    let originalMeta: MedicationMeta?
    let originalRule: ProgramRule?
    let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var medicationID: String
    @State private var name: String
    @State private var dose: String
    @State private var form: MedicationForm
    @State private var capsule: Bool
    @State private var amount: MedicationAmount
    @State private var times: [String]
    @State private var newTime = Calendar.current.date(from: DateComponents(hour: 8, minute: 0)) ?? Date()
    @State private var showAdvanced = false
    @State private var weekdays: Set<Int>
    @State private var busy = false
    @State private var errorMessage: String?

    init(runtime: DosefolkRuntime, medication: Medication?, meta: MedicationMeta?, rule: ProgramRule?, onSaved: @escaping () -> Void) {
        self.runtime = runtime
        self.medication = medication
        self.originalMeta = meta
        self.originalRule = rule
        self.onSaved = onSaved
        _medicationID = State(initialValue: medication?.id ?? UUID().uuidString)
        _name = State(initialValue: medication?.name ?? "")
        _dose = State(initialValue: meta?.editorNote(from: medication?.dose ?? "") ?? medication?.dose ?? "")
        _form = State(initialValue: meta?.form ?? .OTHER)
        _capsule = State(initialValue: meta?.isCapsule == true)
        _amount = State(initialValue: MedicationAmount(existing: meta?.quantity))
        _times = State(initialValue: medication?.times ?? [])
        _weekdays = State(initialValue: rule?.weekdays ?? [])
        _showAdvanced = State(initialValue: UserDefaults.standard.string(forKey: DailyViewMode.preference) == DailyViewMode.advanced.rawValue || !(rule?.weekdays.isEmpty ?? true))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("İlaç") {
                    TextField("İlaç adı", text: $name)
                    Picker("İlaç biçimi", selection: Binding(get: {
                        capsule ? "CAPSULE" : form.rawValue
                    }, set: selectForm)) {
                        ForEach(MedicationForm.allCases, id: \.self) { value in
                            Text(value.editorLabel).tag(value.rawValue)
                            if value == .TABLET { Text("Kapsül").tag("CAPSULE") }
                        }
                    }
                }

                Section("Her kullanımda") {
                    if isTablet {
                        Picker("Tablet miktarı", selection: Binding(get: { amount.choice }, set: { amount.select($0) })) {
                            ForEach(MedicationAmount.Choice.allCases) { option in
                                Text(option.label).tag(option)
                            }
                        }
                    }
                    if !isTablet || amount.choice == .custom {
                        TextField("Kullanım miktarı (\(doseUnit))", text: $amount.text)
                            .keyboardType(.decimalPad)
                        if !amount.text.isEmpty && amount.quantity == nil {
                            Text("Sıfırdan büyük bir sayı gir.").foregroundStyle(.red)
                        }
                    }
                    TextField("Kullanım notu (isteğe bağlı)", text: $dose)
                }

                Section("Hatırlatma saatleri") {
                    ForEach(times, id: \.self) { time in
                        HStack {
                            Text(time).monospacedDigit()
                            Spacer()
                            Button(role: .destructive) {
                                times.removeAll { $0 == time }
                            } label: {
                                Image(systemName: "minus.circle").frame(minWidth: 44, minHeight: 44)
                            }
                            .accessibilityLabel("\(time) saatini kaldır")
                            .buttonStyle(.borderless)
                        }
                    }
                    DatePicker("Saat", selection: $newTime, displayedComponents: .hourAndMinute)
                    Button("Saati ekle") { times = (times + [timeText]).sorted() }
                        .disabled(times.contains(timeText))
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
                    .disabled(!amountValid || busy || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || times.isEmpty)
                }
            }
        }
    }

    private var weekdayPicker: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 8) {
            ForEach(1...7, id: \.self) { day in
                if weekdays.contains(day) {
                    Button(weekdayLabel(day)) { weekdays.remove(day) }
                        .buttonStyle(.borderedProminent)
                } else {
                    Button(weekdayLabel(day)) { weekdays.insert(day) }
                        .buttonStyle(.bordered)
                }
            }
        }
    }

    private func weekdayLabel(_ day: Int) -> String {
        switch day {
        case 1: return "Pzt"
        case 2: return "Sal"
        case 3: return "Çar"
        case 4: return "Per"
        case 5: return "Cum"
        case 6: return "Cmt"
        default: return "Paz"
        }
    }

    private var timeText: String {
        let time = Calendar.current.dateComponents([.hour, .minute], from: newTime)
        return String(format: "%02d:%02d", time.hour ?? 0, time.minute ?? 0)
    }

    private var isTablet: Bool { form == .TABLET && !capsule }

    private var keepingForm: Bool {
        originalMeta?.form == form && (originalMeta?.isCapsule == true) == capsule
    }

    private var amountValid: Bool {
        if amount.quantity != nil { return true }
        guard amount.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        return !isTablet || (keepingForm && originalMeta?.quantity == nil)
    }

    private var doseUnit: String {
        if keepingForm, let unit = originalMeta?.doseUnitOverride, !unit.isEmpty { return unit }
        return capsule ? "kapsül" : form.editorUnit
    }

    private func selectForm(_ value: String) {
        let nextCapsule = value == "CAPSULE"
        let next = nextCapsule ? MedicationForm.TABLET : (MedicationForm(rawValue: value) ?? .OTHER)
        guard next != form || nextCapsule != capsule else { return }
        form = next
        capsule = nextCapsule
        amount = MedicationAmount(newTablet: isTablet)
    }

    @MainActor
    private func save() async {
        busy = true
        errorMessage = nil
        let id = medicationID
        var meta = originalMeta ?? MedicationMeta(medicationId: id)
        meta.form = form
        meta.quantity = amount.quantity
        if !keepingForm {
            meta.doseUnitOverride = doseUnit
            meta.administrationSite = ""
        }
        let originalDose = medication?.dose ?? ""
        let savedDose: String
        if keepingForm, meta.quantity == originalMeta?.quantity, dose == originalMeta?.editorNote(from: originalDose) {
            savedDose = originalDose
        } else {
            savedDose = [meta.editorDoseLabel, dose.trimmingCharacters(in: .whitespacesAndNewlines)]
                .filter { !$0.isEmpty }.joined(separator: " • ")
        }
        let value = Medication(id: id, name: name, dose: savedDose, times: times)
        var rule = originalRule ?? ProgramRule(medicationId: id)
        rule.weekdays = weekdays
        do {
            _ = try await runtime.medicationProgramService.upsert(medication: value, meta: meta, rule: rule)
            busy = false
            onSaved()
            dismiss()
        } catch {
            busy = false
            errorMessage = "İlaç kaydedilemedi."
        }
    }
}
