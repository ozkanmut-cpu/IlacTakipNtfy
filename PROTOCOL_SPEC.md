# Dosefolk Cross-Platform Protocol

This document is the platform-independent contract shared by Android and iOS.

## Transport

- Production endpoint: `https://ntfy.field-maintenance-prod.com`
- Each installation owns one publisher topic (`actorTopic`).
- A device subscribes to its own topic plus the publisher topics of known Circle peers.
- Protocol JSON is always published to the sender/publisher topic, never to the recipient topic.
- Targeted control messages use `targetTopic` inside the payload.

## Security invariants

Inbound processing MUST apply these checks before any state-changing side effect:

1. Parse ntfy envelope.
2. Ignore non-`message` envelope events.
3. Parse the JSON payload from the ntfy `message` field.
4. Require `envelope.topic == payload.actorTopic`.
5. Require `targetTopic` to be empty or equal to the local publisher topic.
6. Reject unsupported future protocol versions safely.
7. Apply replay/duplicate/revocation guards.
8. Only then apply stock, dose, program, rule, pairing or other state changes.

A truncated replay response (`X-Messages-Truncated: 1`) must not advance the durable checkpoint.

## Dose protocol version

Current Android dose-event protocol version: **v9**.

Canonical v9 payload fields:

```json
{
  "v": 9,
  "eventId": "string",
  "type": "string",
  "time": "string",
  "scheduledDate": "YYYY-MM-DD",
  "snoozeUntil": 0,
  "ownerId": "string",
  "targetTopic": "string",
  "actor": "string",
  "actorTopic": "string",
  "timestamp": 0,
  "revision": 0,
  "syncState": "pending|synced",
  "medications": [],
  "medicationMeta": []
}
```

Required for an inbound dose event: `eventId`, `type`, and `time` must be non-empty.

## Medication

```json
{
  "id": "string",
  "name": "string",
  "dose": "string",
  "times": ["HH:mm"]
}
```

## Medication metadata

Fields:

- `medicationId: String`
- `form: TABLET | INSULIN | INJECTION | NEBULE | INHALER | DROP | LIQUID | CREAM | PATCH | OTHER`
- `quantity: Double?`
- `administrationSite: String`
- `packageCount: Int?`
- `packageUnit: String`
- `source: String` (default `manual`)
- `doseUnitOverride: String`

## Lamport revision

`revision` is a monotonically increasing local Lamport-style counter. Receiving a higher remote revision advances the local observed revision. Duplicate `eventId` values must not create a second canonical event.

## Target routing

`targetTopic == ""` means untargeted/broadcast-to-subscribers behavior.

If `targetTopic` is non-empty, only the device whose local publisher topic equals `targetTopic` may process the payload.

Target routing MUST happen before every side effect, including stock synchronization.

## Circle presence

Presence is a targeted control event used to confirm reciprocal pairing.

Rules:

- sender publishes on its own publisher topic;
- `actorTopic` is the sender publisher topic;
- `targetTopic` is the intended peer publisher topic;
- a peer is considered confirmed only after a valid targeted presence from that peer has been accepted;
- revoked peers must not regain authority through retained/replayed events without re-pairing.

## Compatibility policy

- Android and iOS encoders must preserve canonical field names exactly.
- Unknown additive fields should be ignored when safe.
- Future unsupported protocol versions must be rejected/skipped without blocking the rest of a sync batch.
- CI must include fixtures that Android can encode/iOS can decode and iOS can encode/Android can decode.
