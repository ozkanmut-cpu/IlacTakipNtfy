import Foundation

enum AssistantPriority: Int, Comparable {
    case allGood = 0
    case lowStock = 1
    case doseDue = 2
    case conflict = 3

    static func < (lhs: AssistantPriority, rhs: AssistantPriority) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}

struct AssistantSummary: Equatable {
    let priority: AssistantPriority
    let title: String
    let detail: String
    let targetDoseID: String?
}

enum AssistantSummaryBuilder {
    static func build(today: [TodayDoseItem], stock: [StockState]) -> AssistantSummary {
        if let item = today.first(where: { $0.status == .conflict }) {
            return AssistantSummary(
                priority: .conflict,
                title: "Bir kaydı kontrol etmen gerekiyor",
                detail: "\(item.time) dozunda çelişkili kayıt var. Doğru durumu Bugün ekranından seç.",
                targetDoseID: item.id
            )
        }

        if let item = today.first(where: { $0.status == .pending || $0.status == .unknown || $0.status == .snoozed }) {
            let names = item.medications.map(\.name).joined(separator: ", ")
            return AssistantSummary(
                priority: .doseDue,
                title: "Sıradaki işin \(item.time) dozu",
                detail: names.isEmpty ? "Doz bekliyor." : "\(names) için kayıt bekliyor.",
                targetDoseID: item.id
            )
        }

        let low = stock.filter { $0.remainingDoses <= $0.lowThreshold }
        if !low.isEmpty {
            let names = low.map(\.medicationName).filter { !$0.isEmpty }.joined(separator: ", ")
            return AssistantSummary(
                priority: .lowStock,
                title: "Stok kontrolü gerekiyor",
                detail: names.isEmpty ? "Düşük stoklu ilaç var." : "Düşük stok: \(names)",
                targetDoseID: nil
            )
        }

        return AssistantSummary(
            priority: .allGood,
            title: "Her şey yolunda",
            detail: "Şu anda senden beklenen önemli bir işlem görünmüyor.",
            targetDoseID: nil
        )
    }
}
