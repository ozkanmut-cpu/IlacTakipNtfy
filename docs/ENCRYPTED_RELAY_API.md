# Encrypted relay API (Task 6)

Implemented on `encrypted-relay-migration`; not a production deployment or
mobile transport cutover. All endpoints require a native installation Bearer
credential. Actor identity is derived from that credential and rechecked after
reading request bodies. No domain event is published to either transport by
these endpoints.

## Enqueue

`POST /v1/messages` accepts `{ "messages": [envelope, ...] }` and returns 201
with `{ "messages": [{ "messageId": "...", "inserted": true, "relaySeq": 1 }] }`.
Each envelope has only:

- `messageId`: opaque unique ID (ASCII letters/digits/dot/underscore/hyphen, 1–128).
- `routeId`, `recipientInstallId`: opaque routing IDs with the same bounds.
- `senderKeyVersion`, `recipientKeyVersion`: positive integer current key versions.
- `ciphertext`: canonical standard padded base64, at most 512 KiB decoded.

Client `receivedAt`/`expiresAt` fields are tolerated but discarded. Server time
sets both persisted timestamps with an exact 30-day lifetime. All other extra
fields, including `senderInstallId`, domain fields and plaintext payload objects,
are rejected. The relay validates envelope syntax, not encryption correctness;
recipient devices must authenticate/decrypt using pinned peer identity.

Batches contain 1–100 envelopes; the complete HTTP JSON body is capped at 1 MiB.
Split larger requests locally (e.g. send one maximum-size ciphertext at a time).
Every member is validated before insertion. Queue insertion, quota checks and
per-recipient sequence increments share a SQLite IMMEDIATE transaction. A batch
failure leaves no partial inserts or consumed sequences. Quotas are 10,000
stored messages and 100 MiB decoded ciphertext per recipient. Existing rows
are never evicted to admit new messages; sender outboxes must retain/retry.

While a message remains in the queue, retrying identical routing, versions and
ciphertext returns its existing sequence with `inserted: false`; timestamps are
not extended. Reusing an ID for different content or routing returns a conflict.
After terminal deletion, no server message-history tombstone is retained: a
lost enqueue response followed by retry can enqueue again. Exactly-once-effective
domain processing therefore requires durable device event-ID deduplication.

## Inbox

`GET /v1/inbox?limit=100` returns `{ "messages": [...] }` for the authenticated
recipient only. Each record includes the envelope fields, server-derived
`senderInstallId`, `relaySeq`, `receivedAt` and `expiresAt`. Ciphertext is base64;
wake scheduling metadata is not included.

Results are ordered by recipient relay sequence (insertion order, not domain
ordering). Positive integer limits are clamped to 100. The exact serialized JSON
body, including base64 overhead, is limited to 1 MiB. GET does not mutate messages
or advance a cursor. Expired rows are filtered even before physical cleanup.
Sequences survive deletion and queue reopen; they are transport continuity
metadata only.

## Terminal ACK

`POST /v1/messages/ack` accepts:

```json
{"acks":[{"messageId":"opaque-message-id","outcome":"processed"}]}
```

Batches contain 1–100 ACKs. Outcomes `processed`, `duplicate`, and `rejected`
all delete ciphertext; `{ "ok": true }` is returned with status 200. Unknown
fields (including rejection details) and other outcomes are rejected. All
ownership checks run before any deletion in the same queue transaction. An
existing message owned by another recipient fails the whole batch. Missing IDs
are successful no-ops so lost ACK responses can be retried without storing
per-message ACK history. Clients must ACK only after durable local processing.

## Errors and persistence

- 400: malformed envelope, batch, ACK, JSON or inbox limit.
- 401: missing/invalid/revoked installation credential.
- 403: unauthorized route/recipient or ACK ownership.
- 409: key-version mismatch or conflicting message ID.
- 413: ciphertext or HTTP request too large.
- 429: recipient queue quota exceeded.
- 503: relay storage unavailable.

Errors deliberately contain a generic code, never request values or parser
exception text. The queue database uses WAL, FULL synchronization and secure
SQLite row deletion. A terminal ACK removes the active queue record immediately;
this is not a promise of forensic erasure from historical WAL/filesystem copies.
The separate queue storage must remain excluded from long-term backups/PITR.

Task 8 adds generic push wakes. Tasks 9 onward add device crypto, pinned
pairing identity, durable domain processing and ACK timing. Those later gates
are not claimed complete by this API implementation.

## Retention, rate limits and observability (Task 7)

Inbox reads exclude `expiresAt <= serverNow` even if scheduled cleanup has not
run. The service physically purges expired ciphertext hourly, and enqueue also
purges expired rows inside the same write transaction before evaluating pending
count/byte quotas. Expired rows therefore cannot block fresh delivery.

Authenticated relay, route and pairing APIs share a fixed-window limit of 120
requests per minute per installation. Authentication and limit consumption
precede request-body parsing. A rejected request returns `429 rate_limited` with
a bounded `Retry-After` value. The single-node counter table is capped at 10,000
installations and fails closed at capacity without resetting active counters.

`GET /health` reports only aggregate relay transport state: pending ciphertext
count, decoded ciphertext bytes and oldest pending age. Expired rows are omitted.
Message, route and installation IDs, ciphertext, credentials, keys, push tokens
and domain fields never appear in these metrics. Server error logs use fixed
messages and do not serialize request bodies or parser exceptions.
