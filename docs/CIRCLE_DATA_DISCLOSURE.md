# Dosefolk Circle Data Disclosure

This document describes the application data transmitted through Dosefolk Circle so the behavior is explicit for privacy review, App Store disclosure work, and cross-platform QA.

## Transport

Dosefolk Circle uses the self-hosted ntfy endpoint documented in `PROTOCOL_SPEC.md`. Each installation publishes protocol events on its own publisher topic (`actorTopic`). A device subscribes to its own topic and the publisher topics of known Circle peers. Targeted control messages include `targetTopic` in the protocol payload.

## Data transmitted through Circle

Depending on the action being synchronized, Circle protocol messages can contain:

- protocol metadata such as protocol version, event ID, event type, timestamps, Lamport revision and synchronization state;
- Circle routing/identity metadata such as owner ID, actor display name, actor topic and optional target topic;
- medication schedule data, including medication ID, medication name, dose text and scheduled times;
- medication metadata, including medication form, quantity, administration site, package count/unit, source and dose-unit override when present;
- dose-event state needed for synchronization, including scheduled date, snooze-until time and states/events such as Taken, Snoozed, Missed or correction;
- program-rule data, including weekdays, start/end dates, every-N-days cadence, anchor date and optional routine label;
- stock data, including medication ID/name, remaining doses, pack size, low-stock threshold and stock update time;
- Circle presence/pairing control events required to establish or confirm peer relationships.

## Data not intentionally included in Circle protocol messages

The Circle protocol is not designed to transmit:

- passwords;
- Apple ID or Google account credentials;
- payment-card or banking information;
- address-book/contact lists;
- precise location data;
- advertising identifiers;
- analytics or advertising tracking identifiers;
- photos, camera images or microphone recordings;
- QA log files as part of normal Circle synchronization.

Credentials/tokens used to authenticate transport are handled separately from the medication/event payload. QA logging is also separate from Circle synchronization and is designed to avoid writing medication names, dose text, notification bodies, raw protocol payloads, topics, tokens or secrets into exported QA logs.

## Purpose

The transmitted data is used only to provide the user-requested Circle functionality: pairing trusted devices/people, synchronizing medication schedules and dose status, synchronizing program rules and stock, and maintaining deterministic cross-platform state.

## Routing and safety rules

Before applying any inbound state-changing event, Dosefolk validates publisher-topic binding, target routing, protocol compatibility, replay/duplicate protection and peer revocation state as defined in `PROTOCOL_SPEC.md`.

A targeted message may only be processed by the installation whose local publisher topic matches `targetTopic`. Duplicate event IDs must not create duplicate canonical events, and revoked peers must not regain authority through retained/replayed events without re-pairing.

## App Store privacy review note

This file documents what the application protocol is capable of transmitting. App Store Connect privacy answers must still be reviewed against the production build, enabled features, backend retention behavior and Apple's current privacy taxonomy before submission.
