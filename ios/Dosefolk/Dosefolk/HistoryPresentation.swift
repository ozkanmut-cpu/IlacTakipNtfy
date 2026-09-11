import Foundation

struct HistoryEntry: Identifiable, Equatable {
    let event: DoseEvent
    let title: String
    let detail: String

    var id: String { event.eventId }
    var scheduledDate: String { event.scheduledDate }
    var timestamp: Int64 { event.timestamp }
}

struct HistoryDay: Identifiable, Equatable {
    let date: String
    let entries: [HistoryEntry]

    var id: String { date }
}

enum HistoryPresentation {
    private static let visibleTypes: Set<String> = [
        "taken", "missed", "snoozed", "prn_taken",
        "undo_taken", "undo_missed",
        "conflict_resolved_taken", "conflict_resolved_missed"
    ]

    static func days(from events: [DoseEvent]) -> [HistoryDay] {
        let entries = events
            .filter { visibleTypes.contains($0.type) }
            .map(makeEntry)
            .sorted { lhs, rhs in
                if lhs.timestamp != rhs.timestamp { return lhs.timestamp > rhs.timestamp }
                return lhs.id > rhs.id
            }

        let grouped = Dictionary(grouping: entries) { entry in
            if !entry.scheduledDate.isEmpty { return entry.scheduledDate }
            let date = Date(timeIntervalSince1970: TimeInterval(entry.timestamp) / 1000)
            return isoDate(date)
        }

        return grouped.keys.sorted(by: >).map { key in
            HistoryDay(date: key, entries: grouped[key] ?? [])
        }
    }

    static func isVisible(type: String) -> Bool {
        visibleTypes.contains(type)
    }

    private static func makeEntry(_ event: DoseEvent) -> HistoryEntry {
        let title: String
        switch event.type {
        case "taken": title = "İlaç alındı"
        case "prn_taken": title = "Gerektiğinde ilaç alındı"
        case "missed": title = "İlaç alınmadı"
        case "snoozed": title = "Hatırlatma ertelendi"
        case "undo_taken", "undo_missed": title = "Kayıt geri alındı"
        case "conflict_resolved_taken": title = "Kayıt 'alındı' olarak düzeltildi"
        case "conflict_resolved_missed": title = "Kayıt 'alınmadı' olarak düzeltildi"
        default: title = "Güncellendi"
        }

        var parts: [String] = []
        if !event.time.isEmpty, event.time != "program", event.time != "circle" {
            parts.append(event.time)
        }
        let names = event.medications.map(\.name).filter { !$0.isEmpty }
        if !names.isEmpty {
            parts.append(names.joined(separator: ", "))
        }
        if !event.actor.isEmpty {
            parts.append(event.actor)
        }
        return HistoryEntry(event: event, title: title, detail: parts.joined(separator: " • "))
    }

    private static func isoDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }
}
