# Relay Durable Processing Recovery Design

## Purpose

This Task 12 delta makes the Android encrypted-relay client recover safely from process death, local persistence failure, acknowledgement response loss, route revocation, and key/route replacement. It preserves the existing migration fence: `NtfySyncTransport` remains the selected runtime transport until Task 16.

## Invariants

- No terminal ACK is sent until the exact inbound delivery has a durable terminal journal entry.
- A journal terminal entry is written only after every required local domain effect reports durable success.
- Relay ACK batches contain at most 100 outcomes. A successful batch compacts only its own receipt IDs and journal entries; an uncertain batch remains durable and is retried idempotently.
- Journal state stores no ciphertext, credential, key material, or plaintext domain payload. It stores only opaque relay message ID, locally bounded source-event reference, SHA-256 canonical payload digest, phase, and terminal outcome.
- `JSONObject.NULL` is decoded explicitly; journal schema rejects malformed phase/outcome combinations.
- The first successful route resolution for one source event durably snapshots its intended recipients. A broadcast cannot gain a newly paired recipient on retry.
- A record whose route ID, recipient identity, or key version is no longer active is never enqueued. Revocation/key replacement retires its old ciphertext; re-pair does not silently receive historical event data.
- Required domain effects use checked durable boundaries. A failed persistence operation leaves the journal nonterminal and prevents receipt/terminal ACK progression; replay is idempotent.
- Sender identity remains credential-derived server-side; client continues to compare route keys only with locally pinned pairing identity.

## Inbox State Machine

`STARTED -> APPENDED -> EFFECTS -> TERMINAL` is durable. Replaying `STARTED` safely performs append; replaying `APPENDED` resumes the complete idempotent domain-effect bundle; replaying `EFFECTS` writes only `TERMINAL`; replaying `TERMINAL` only retries ACK.

ACK processing is chunked. For one acknowledged chunk: server success -> compact exactly that chunk's relay receipt IDs -> remove exactly that chunk's terminal journal entries. Any failure retains the journal entries for replay. Replaying a lost response is safe because server ACK is a missing-ID no-op.

## Checked Domain Effects

The relay adapter is responsible for one checked, resumable bundle: owner binding, remote medication metadata, PRN/stock changes, remote state application, and receipt marking. Every synchronous preference write used by that bundle must return success or throw. Ordering-sensitive program, rule, and capability writes must report the combined body-and-checkpoint result rather than a partial result.

## Outbox Snapshot and Retirement

When a pending source has no snapshot, an authenticated routes read is normalized by recipient and compared against pinned identities. The durable source snapshot contains only recipient install IDs plus route/key identity references. Materialization creates one ciphertext record per snapshot recipient.

On later flushes, current authenticated routes are compared to each unaccepted record. Missing/revoked/replaced routes retire old ciphertext locally; they are not retried or rematerialized for a new peer. Accepted records retain evidence until the source is durably synced, then are cleaned up. Route-fetch failure leaves all existing evidence untouched.

## Verification

Focused tests cover Android JSON-null journal round trips, journal write failure, process death at every phase, >100 terminal ACK recovery, partial ACK failure, exact receipt compaction, local effect persistence failure, broadcast snapshot stability, route revocation/key replacement, and no stale ciphertext enqueue. Each change follows RED -> verified failure -> minimal GREEN -> full Android regression -> exact-SHA Build APK and Cross-Platform Gate success.
