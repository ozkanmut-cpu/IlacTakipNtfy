# Dosefolk Encrypted Relay Design

## Goal

Remove ntfy completely and replace it with a Dosefolk-owned store-and-forward relay that never receives plaintext health/domain data. The relay permanently stores only minimal routing/security metadata and temporarily stores only end-to-end encrypted message envelopes.

## Non-negotiable privacy rules

- ntfy is removed from the final architecture.
- No server-side plaintext medication, dose, schedule, stock, note, health, reconciliation or domain-event storage.
- Domain payloads are encrypted on the sender device before network transmission and decrypted only on recipient devices.
- Pending relay ciphertext is deleted immediately after terminal recipient ACK and is hard-expired after 30 days.
- The server, not the client, computes `expiresAt = receivedAt + 30 days`.
- Relay queue storage is not retained in long-term backups. Backup/PITR/snapshot retention for queue storage must never exceed the 30-day policy.
- FCM/APNs carry only a generic wake payload; they never contain domain or health data.
- Private E2EE keys never leave the device and are never escrowed by Dosefolk.

## Permanent server metadata

The server may permanently retain only metadata needed to route and secure transport:

- opaque install ID
- platform (`android`/`ios`)
- active/revoked state
- install credential hash and rotation metadata
- FCM/APNs push target, encrypted at rest
- device encryption public key
- device signing public key
- key version and revocation metadata
- directional route/pairing relationships and permission state
- short-lived pairing enrollment token hashes
- security-event metadata such as key rotation/revocation, without domain payloads

The server must not contain permanent tables for medications, dose events, schedules, programs, stock, snapshots or health state.

## Relay queue record

A pending relay record contains only opaque transport metadata and ciphertext:

```text
relay_seq
message_id
route_id
sender_install_id
recipient_install_id
sender_key_version
recipient_key_version
ciphertext
received_at
expires_at
next_wake_at
wake_attempts
```

No plaintext event type or domain field is stored outside ciphertext.

## E2EE profile

Each installation owns two independent key pairs:

- HPKE encryption key pair using X25519 + HKDF-SHA256 + ChaCha20-Poly1305.
- Ed25519 signing key pair.

The implementation must use a vetted crypto library and RFC 9180 compatible HPKE rather than hand-written primitives. Android implementation should use Google Tink and pin an exact tested version.

Each outbound domain payload is canonicalized, signed by the sender, then HPKE-encrypted independently to each recipient's pinned encryption public key. Sender authentication is provided by the signature; HPKE is used for confidentiality/integrity to the recipient.

Public outer envelope fields that affect routing are bound into the HPKE context/AAD-equivalent context information. At minimum this includes `messageId`, `routeId`, sender install ID, recipient install ID, sender key version and recipient key version. Any mismatch causes decryption/verification failure.

## Trust bootstrap and pairing

Pairing v2 is an authenticated trust bootstrap, not merely a topic exchange.

The pairing QR contains:

```json
{
  "version": 2,
  "offerId": "opaque-random-id",
  "installId": "opaque-install-id",
  "encryptionPublicKey": "...",
  "signingPublicKey": "...",
  "keyVersion": 1,
  "pairingSecret": "256-bit-random-secret",
  "expiresAt": "server-independent-short-expiry",
  "signature": "..."
}
```

The QR directly transfers the offerer's identity keys to the scanning peer. The server is not the root of trust for public keys. Pairing secret lifetime is 10 minutes and is never stored server-side in plaintext.

After successful pairing, each device pins the peer install ID, encryption key, signing key, key version and identity fingerprint locally. A server-returned key that does not match the pinned identity is rejected.

Re-pair after revocation always requires a new pairing flow. Existing revoke tombstones are not silently cleared.

## Key rotation

A new identity key version must be authorized by the previous signing key. Rotation certificates bind install ID, new encryption public key, new signing public key, new key version, issued-at time and a nonce.

Recipients accept a new peer key version only after validating the old-key signature or after an explicit new pairing.

Previous decryption private keys are retained locally for 31 days so ciphertext created before rotation can still be opened within the 30-day queue lifetime. New messages always target the newest accepted recipient key version.

## Delivery semantics

Transport semantics are:

```text
at-least-once delivery + exactly-once-effective domain processing
```

Sender flow:

```text
create domain event
-> durable local EventStore/outbox
-> resolve active recipient routes and pinned keys
-> sign and encrypt one envelope per recipient
-> POST /v1/messages
-> relay transactionally persists ciphertext
-> generic push wake
```

Recipient flow:

```text
GET /v1/inbox
-> decrypt
-> verify pinned sender signature
-> verify routing/AAD context
-> run existing protocol/actor/target guards
-> eventId dedupe
-> durable local state commit
-> ACK
-> relay deletes ciphertext
```

ACK is forbidden before durable local processing. If the app crashes before ACK, the same message is delivered again and deduplicated locally.

Terminal ACK outcomes are `processed`, `duplicate`, and `rejected`; all three delete the ciphertext. Detailed rejection reasons remain device-local.

## Inbox model

There is no destructive cursor. `GET /v1/inbox` returns the oldest unacked messages for the authenticated installation, with a maximum of 100 messages and 1 MiB response size per batch.

Messages remain pending until terminal ACK or 30-day expiry.

A per-recipient monotonic `relay_seq` is transport continuity metadata only. If a client observes a gap, it may request encrypted reconciliation from a trusted peer. It must not be interpreted as domain event ordering.

## Reconciliation

There is no plaintext snapshot endpoint. Reconciliation request and reconciliation snapshot are ordinary E2EE relay messages whose inner payload is invisible to the server.

If a device has been offline longer than queue retention, detects a relay sequence gap, changes device, or otherwise needs recovery, it sends an encrypted reconciliation request to an eligible trusted peer. That peer generates a local canonical snapshot, signs it, encrypts it to the requester, and sends it via the normal relay.

## Routes and authorization

Server authorization is directional. A route explicitly permits one authenticated sender installation to enqueue for one recipient installation.

The sender identity is derived from the bearer credential, never trusted from request JSON. A request cannot enqueue as another installation.

Revocation transactionally:

- marks the route revoked
- blocks all future enqueue
- deletes all pending ciphertext for that route
- preserves a durable client revoke tombstone

Full installation revocation disables all routes, invalidates credentials, removes push targets and purges pending inbound/outbound ciphertext for that installation.

## API surface

### `POST /v1/provision`
Creates/reprovisions an installation identity and returns the install credential once. The server stores only the credential hash.

### `PUT /v1/install/push`
Atomically replaces the installation's FCM/APNs target.

### `PUT /v1/install/keys`
Registers an authorized key rotation certificate.

### `GET /v1/routes`
Returns active directional recipient routes plus currently registered public-key metadata. Clients must compare this metadata with locally pinned identity before encrypting.

### `POST /v1/messages`
Accepts recipient-specific ciphertext only after authenticated sender, route, recipient state, key version, size and quota validation.

### `GET /v1/inbox?limit=100`
Returns unacked ciphertext for the authenticated recipient, oldest first, bounded by message count and response bytes.

### `POST /v1/messages/ack`
Deletes only messages belonging to the authenticated recipient.

### Pairing/revoke endpoints
Create/accept/confirm short-lived pairing offers and revoke directional routes without making the server a key-trust authority.

## Push behavior

FCM/APNs payload remains exactly generic wake metadata:

```json
{"wakeType":"sync","protocolVersion":"1"}
```

Push success is never considered message delivery. Only Dosefolk ACK is delivery confirmation.

Wake coalescing occurs per recipient, not per message. Suggested retry schedule while inbox remains pending is T+0, T+2m, T+15m, T+1h, T+6h, T+24h, then daily, with cancellation after inbox activity/ACK drains pending work.

Push provider outage must not prevent relay enqueue or inbox polling.

## Reliability and quotas

Initial hard limits:

- max ciphertext per message: 512 KiB
- max inbox batch count: 100
- max inbox response body: 1 MiB
- max pending messages per recipient: 10,000
- max pending bytes per recipient: 100 MiB
- normal API rate limit: 120 requests/minute/install

Quota exhaustion rejects new enqueue; it never silently deletes older ciphertext. Sender retains its durable local outbox and retries.

## Storage model

The first production target is a single Dosefolk VDS service with two physically separate persistence domains:

1. metadata storage: installations, credential hashes, keys, routes, pairing state, encrypted push targets; eligible for normal encrypted backup.
2. relay queue storage: ciphertext-only transient queue; no long-term backup, snapshots or PITR beyond 30 days.

The implementation plan may use separate database files/services to make the backup boundary mechanically enforceable. The queue must survive normal service/container restarts.

## Logging and observability

Forbidden in server logs:

- ciphertext
- push token
- bearer credential
- pairing secret
- full public/private key material
- domain payload or health data

Allowed metrics are aggregate transport health only, such as pending count/bytes, oldest queue age, ACK latency, expired count, enqueue failures and push-provider success/error totals.

## Android behavior

Android keeps existing local domain protocol guards and idempotency. Transport-specific code moves behind a relay transport boundary. FCM wake, app startup, WorkManager periodic work and connectivity recovery all trigger inbox reconciliation.

The new relay transport replaces ntfy publish, ntfy poll/history, ntfy live sync, ntfy ACL refresh and ntfy provisioning only after relay E2E and migration gates pass.

## iOS behavior

The protocol and cryptographic fixtures must remain cross-platform. Actual iOS/APNs device E2E is not claimable without Apple credentials/device. iOS implementation can remain gated, but no final architecture may require ntfy.

## Migration and decommission

No real domain event is dual-written to ntfy and the new relay during migration. Relay is validated with synthetic fixtures/canaries first, then a transport cutover switches real traffic atomically.

Rollback is permitted only before ntfy decommission and must be explicit. Once relay production E2E is proven, ntfy publication/polling is disabled, ntfy-specific credentials/ACLs/config are removed, and the ntfy service/container/data are decommissioned after a verified no-traffic observation window.

Final state contains no ntfy runtime dependency, endpoint, credential, container, ACL or application code path.
