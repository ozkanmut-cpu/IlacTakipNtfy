import Foundation

struct MedicationAmount {
    enum Choice: String, CaseIterable, Identifiable {
        case one = "1", quarter = "0.25", half = "0.5", custom = ""
        var id: Self { self }
        var label: String {
            switch self {
            case .one: return "1 tablet"
            case .quarter: return "¼ tablet"
            case .half: return "½ tablet"
            case .custom: return "Diğer miktar"
            }
        }
    }

    private(set) var choice: Choice
    var text: String

    init(newTablet: Bool) {
        choice = newTablet ? .one : .custom
        text = choice.rawValue
    }

    init(existing: Double?) {
        text = existing.map { NSDecimalNumber(value: $0).stringValue } ?? ""
        choice = Choice(rawValue: text) ?? .custom
    }

    mutating func select(_ next: Choice) {
        guard next != choice else { return }
        choice = next
        text = next.rawValue
    }

    var quantity: Double? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
        guard value.range(of: #"^(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)$"#, options: .regularExpression) != nil,
              let number = Double(value), number.isFinite, number > 0 else { return nil }
        return number
    }
}

extension MedicationMeta {
    var isCapsule: Bool { ["kapsül", "kapsul", "capsule"].contains(doseUnitOverride.lowercased()) }

    var editorDoseLabel: String {
        guard let quantity else { return "" }
        let number = NSDecimalNumber(value: quantity).stringValue
        let unit = doseUnitOverride.isEmpty ? form.editorUnit : doseUnitOverride
        return "\(number) \(unit)" + (administrationSite.isEmpty ? "" : " • \(administrationSite)")
    }

    func editorNote(from dose: String) -> String {
        guard quantity != nil else { return dose }
        let units = doseUnitOverride.isEmpty ? form.oldDisplayUnits : [doseUnitOverride]
        for unit in units {
            var candidate = self
            candidate.doseUnitOverride = unit
            let label = candidate.editorDoseLabel
            if dose == label { return "" }
            let prefix = label + " • "
            if dose.hasPrefix(prefix) { return String(dose.dropFirst(prefix.count)) }
        }
        return dose
    }
}

extension MedicationForm {
    var editorLabel: String {
        switch self {
        case .TABLET: return "Tablet"
        case .INSULIN: return "İnsülin"
        case .INJECTION: return "Enjeksiyon"
        case .NEBULE: return "Nebül"
        case .INHALER: return "İnhaler / sprey"
        case .DROP: return "Damla"
        case .LIQUID: return "Şurup / solüsyon"
        case .CREAM: return "Krem / merhem"
        case .PATCH: return "Yama"
        case .OTHER: return "Diğer"
        }
    }

    var oldDisplayUnits: [String] {
        switch self {
        case .TABLET: return ["tablet", "adet", "count"]
        case .INJECTION, .OTHER: return ["doz", "dose"]
        case .NEBULE: return ["nebül", "nebule"]
        case .INHALER: return ["puf", "puff"]
        case .DROP: return ["damla", "drop"]
        case .CREAM: return ["uygulama", "application"]
        case .PATCH: return ["adet", "patch"]
        case .INSULIN, .LIQUID: return [editorUnit]
        }
    }

    var editorUnit: String {
        switch self {
        case .TABLET: return "tablet"
        case .INSULIN: return "U"
        case .INJECTION, .OTHER: return "doz"
        case .NEBULE: return "nebül"
        case .INHALER: return "puf"
        case .DROP: return "damla"
        case .LIQUID: return "mL"
        case .CREAM: return "uygulama"
        case .PATCH: return "adet"
        }
    }
}
