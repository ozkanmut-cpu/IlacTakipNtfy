import Foundation

enum InboundRejection: Equatable {
    case nonMessage
    case invalidPayload
    case publisherTopicMismatch
    case wrongTarget
    case unsupportedProtocol(Int)
    case invalidDoseEvent
}

enum InboundProtocolResult: Equatable {
    case accepted(DoseEvent)
    case rejected(InboundRejection)
}

enum NtfyReplayGuard {
    static func isTruncated(_ header: String?) -> Bool {
        header?.trimmingCharacters(in: .whitespacesAndNewlines) == "1"
    }
}

enum InboundProtocolGate {
    static let maximumDoseProtocolVersion = 9

    static func inspect(envelope: SyncEnvelope, localTopic: String) -> InboundProtocolResult {
        guard envelope.event == "message" else { return .rejected(.nonMessage) }
        guard let message = envelope.message, let data = message.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return .rejected(.invalidPayload)
        }

        let actorTopic = (object["actorTopic"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let envelopeTopic = envelope.topic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !envelopeTopic.isEmpty, !actorTopic.isEmpty, envelopeTopic == actorTopic else {
            return .rejected(.publisherTopicMismatch)
        }

        let targetTopic = (object["targetTopic"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard targetTopic.isEmpty || targetTopic == localTopic else {
            return .rejected(.wrongTarget)
        }

        let version: Int
        if object.keys.contains("v") {
            if let int = object["v"] as? Int {
                version = int
            } else if let number = object["v"] as? NSNumber {
                version = number.intValue
            } else {
                version = -1
            }
        } else {
            version = 1
        }
        guard (1...maximumDoseProtocolVersion).contains(version) else {
            return .rejected(.unsupportedProtocol(version))
        }

        guard let event = try? JSONDecoder().decode(DoseEvent.self, from: data),
              !event.eventId.isEmpty, !event.type.isEmpty, !event.time.isEmpty else {
            return .rejected(.invalidDoseEvent)
        }
        return .accepted(event)
    }
}
