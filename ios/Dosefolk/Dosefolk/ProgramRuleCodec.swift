import Foundation

enum ProgramRuleCodec {
    static func encode(_ rule: ProgramRule) throws -> String {
        let normalized = normalize(rule)
        let object: [String: Any] = [
            "medicationId": normalized.medicationId,
            "weekdays": normalized.weekdays.sorted(),
            "startDate": normalized.startDate ?? "",
            "endDate": normalized.endDate ?? "",
            "everyNDays": normalized.everyNDays,
            "anchorDate": normalized.anchorDate ?? "",
            "routineLabel": normalized.routineLabel
        ]
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        guard let text = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileWriteInapplicableStringEncoding)
        }
        return text
    }

    static func decode(_ text: String) throws -> ProgramRule {
        guard let data = text.data(using: .utf8),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let medicationId = object["medicationId"] as? String,
              !medicationId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let weekdayNumbers = (object["weekdays"] as? [Any] ?? []).compactMap { value -> Int? in
            if let number = value as? NSNumber { return number.intValue }
            if let int = value as? Int { return int }
            return nil
        }
        return normalize(ProgramRule(
            medicationId: medicationId,
            weekdays: Set(weekdayNumbers),
            startDate: nonBlank(object["startDate"] as? String),
            endDate: nonBlank(object["endDate"] as? String),
            everyNDays: (object["everyNDays"] as? NSNumber)?.intValue ?? 1,
            anchorDate: nonBlank(object["anchorDate"] as? String),
            routineLabel: object["routineLabel"] as? String ?? ""
        ))
    }

    static func normalize(_ rule: ProgramRule) -> ProgramRule {
        var copy = rule
        copy.weekdays = Set(copy.weekdays.filter { (1...7).contains($0) })
        copy.everyNDays = max(1, copy.everyNDays)
        copy.startDate = nonBlank(copy.startDate)
        copy.endDate = nonBlank(copy.endDate)
        copy.anchorDate = nonBlank(copy.anchorDate)
        return copy
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
