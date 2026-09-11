import Foundation

enum DoseNotificationReschedule {
    private static let localProgramTypes: Set<String> = [
        "program_added",
        "program_updated",
        "program_deleted",
        "program_rule_updated"
    ]

    static func shouldReconcile(event: DoseEvent, localTopic: String) -> Bool {
        guard localProgramTypes.contains(event.type) else { return false }
        let ownerId = event.ownerId.isEmpty ? event.actorTopic : event.ownerId
        return !localTopic.isEmpty && ownerId == localTopic
    }
}
