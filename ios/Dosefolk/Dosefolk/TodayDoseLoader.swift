import Foundation

struct TodayDoseItem: Identifiable, Equatable {
    var id: String { "\(scheduledDate)|\(time)" }
    let scheduledDate: String
    let time: String
    let medications: [Medication]
    let status: DoseSessionStatus
}

struct TodayDoseLoader {
    let store: LocalStore
    var calendar: Calendar = .current

    func load(on date: Date = Date()) throws -> [TodayDoseItem] {
        let medications = try store.load([Medication].self, from: .medications, default: [])
        let rules = try store.load([ProgramRule].self, from: .programRules, default: [])
        let ruleByMedication = Dictionary(uniqueKeysWithValues: rules.map { ($0.medicationId, $0) })
        let day = calendar.startOfDay(for: date)
        let scheduledDate = dateString(day)

        var grouped: [String: [Medication]] = [:]
        for medication in medications {
            let rule = ruleByMedication[medication.id] ?? ProgramRule(medicationId: medication.id)
            guard DoseSchedulePlanner.isActive(rule, on: day, today: day, calendar: calendar) else { continue }
            for time in medication.times where isValidTime(time) {
                if !(grouped[time] ?? []).contains(where: { $0.id == medication.id }) {
                    grouped[time, default: []].append(medication)
                }
            }
        }

        let stateEngine = DoseStateEngine(store: store, calendar: calendar)
        return try grouped.keys.sorted().map { time in
            let meds = grouped[time] ?? []
            let state = try stateEngine.stateForTime(time, scheduledDate: scheduledDate)
            return TodayDoseItem(
                scheduledDate: scheduledDate,
                time: time,
                medications: state.medications.isEmpty ? meds : state.medications,
                status: state.status
            )
        }
    }

    private func isValidTime(_ time: String) -> Bool {
        let parts = time.split(separator: ":")
        guard parts.count == 2,
              let hour = Int(parts[0]), let minute = Int(parts[1]) else { return false }
        return (0...23).contains(hour) && (0...59).contains(minute)
    }

    private func dateString(_ date: Date) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}
