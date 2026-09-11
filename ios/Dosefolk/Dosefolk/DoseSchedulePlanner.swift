import Foundation

struct PlannedDoseNotification: Equatable {
    let scheduledDate: String
    let time: String
    let medications: [Medication]
    let fireDate: Date
}

enum DoseSchedulePlanner {
    static func plan(
        medications: [Medication],
        rules: [ProgramRule],
        now: Date,
        horizonDays: Int = 14,
        maxRequests: Int = 48,
        calendar: Calendar = .current
    ) -> [PlannedDoseNotification] {
        guard horizonDays > 0, maxRequests > 0 else { return [] }
        let ruleByMedication = Dictionary(uniqueKeysWithValues: rules.map { ($0.medicationId, $0) })
        let today = calendar.startOfDay(for: now)
        var sessions: [String: (Date, String, String, [Medication])] = [:]

        for offset in 0..<horizonDays {
            guard let day = calendar.date(byAdding: .day, value: offset, to: today) else { continue }
            let dateText = dateString(day, calendar: calendar)
            for medication in medications {
                let rule = ruleByMedication[medication.id] ?? ProgramRule(medicationId: medication.id)
                guard isActive(rule, on: day, today: today, calendar: calendar) else { continue }
                for time in medication.times {
                    guard let fire = fireDate(day: day, time: time, calendar: calendar), fire > now else { continue }
                    let key = "\(dateText)|\(time)"
                    if var existing = sessions[key] {
                        if !existing.3.contains(where: { $0.id == medication.id }) { existing.3.append(medication) }
                        sessions[key] = existing
                    } else {
                        sessions[key] = (fire, dateText, time, [medication])
                    }
                }
            }
        }

        return sessions.values
            .sorted { $0.0 < $1.0 }
            .prefix(maxRequests)
            .map { PlannedDoseNotification(scheduledDate: $0.1, time: $0.2, medications: $0.3, fireDate: $0.0) }
    }

    static func isActive(_ rule: ProgramRule, on date: Date, today: Date, calendar: Calendar = .current) -> Bool {
        let day = calendar.startOfDay(for: date)
        if let start = parseDate(rule.startDate, calendar: calendar), day < start { return false }
        if let end = parseDate(rule.endDate, calendar: calendar), day > end { return false }
        let androidWeekday = ((calendar.component(.weekday, from: day) + 5) % 7) + 1
        if !rule.weekdays.isEmpty && !rule.weekdays.contains(androidWeekday) { return false }
        if rule.everyNDays > 1 {
            let anchor = parseDate(rule.anchorDate, calendar: calendar)
                ?? parseDate(rule.startDate, calendar: calendar)
                ?? calendar.startOfDay(for: today)
            guard let delta = calendar.dateComponents([.day], from: anchor, to: day).day,
                  delta >= 0, delta % max(1, rule.everyNDays) == 0 else { return false }
        }
        return true
    }

    private static func fireDate(day: Date, time: String, calendar: Calendar) -> Date? {
        let parts = time.split(separator: ":")
        guard parts.count == 2, let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        var components = calendar.dateComponents([.year, .month, .day], from: day)
        components.hour = hour
        components.minute = minute
        components.second = 0
        return calendar.date(from: components)
    }

    private static func parseDate(_ text: String?, calendar: Calendar) -> Date? {
        guard let text, !text.isEmpty else { return nil }
        let parts = text.split(separator: "-")
        guard parts.count == 3, let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]) else { return nil }
        return calendar.date(from: DateComponents(year: year, month: month, day: day)).map { calendar.startOfDay(for: $0) }
    }

    private static func dateString(_ date: Date, calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}
