import Foundation

struct CircleSyncProcessor {
    let localTopic: String
    let securityState: CircleSecurityState

    @discardableResult
    func process(
        envelope: SyncEnvelope,
        apply: (DoseEvent) throws -> Void
    ) throws -> InboundProtocolResult {
        let result = try InboundProtocolGate.inspectSecure(
            envelope: envelope,
            localTopic: localTopic,
            securityState: securityState
        )
        guard case .accepted(let event) = result else { return result }

        try apply(event)
        try securityState.markProcessed(eventId: event.eventId)
        return .accepted(event)
    }
}
