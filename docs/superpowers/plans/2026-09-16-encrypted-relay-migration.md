# Dosefolk Encrypted Relay Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ntfy completely with a Dosefolk-owned E2EE store-and-forward relay that permanently retains only minimal routing/security metadata and temporarily retains ciphertext for at most 30 days.

**Architecture:** Build a new relay service beside the existing ntfy/push stack, validate it using synthetic fixtures, then atomically cut Android traffic to the relay. The server never decrypts domain payloads; devices sign and HPKE-encrypt per recipient, recipients durably process then ACK, and ACK/TTL deletes ciphertext. Real domain traffic is never dual-written to ntfy and relay.

**Tech Stack:** Node.js 22+, `better-sqlite3` 13.0.3, Firebase Admin 14.4.0, existing APNs adapter, Android Kotlin/WorkManager, Google Tink Android 1.23.0, HPKE X25519/HKDF-SHA256/ChaCha20-Poly1305, Ed25519, existing protocol fixtures and GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-16-encrypted-relay-design.md`

## Global Constraints

- Work only on `encrypted-relay-migration`, based from `ios-milestone-1` SHA `de736bb5563d5c417a5288db466eb32f6056e5c5`; never touch `main` without explicit approval.
- Never touch `/opt/field-maintenance/app`.
- New production service lives under `/opt/dosefolk-relay`; do not develop inside a production checkout.
- Final runtime has no ntfy dependency, ntfy token, ntfy ACL, ntfy publish/poll/live-sync path or ntfy container.
- No server-side plaintext health/domain storage.
- Queue payload is E2EE ciphertext only; ACK deletes immediately; hard TTL is 30 days and is computed server-side.
- Previous local decryption keys are retained for 31 days after rotation.
- Relay queue has no long-term backup/PITR/snapshot retention beyond 30 days.
- FCM/APNs payload remains exactly `{ "wakeType": "sync", "protocolVersion": "1" }`.
- No real domain event is dual-written to ntfy and relay during migration.
- Sender identity is derived from authenticated install credential, never request JSON.
- Public keys received from the server are not trust anchors; clients must pin peer identity from authenticated pairing.
- Private E2EE keys, bearer credentials, pairing secrets, push tokens, ciphertext and domain payloads must never appear in logs.
- Every task follows RED -> verify expected failure -> minimal GREEN -> full regression -> commit.
- Exact-SHA CI must be `completed/success`; queued/in-progress is not GREEN.

---

### Task 1: Introduce transport boundary without behavior change

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/SyncTransport.kt`
- Create: `app/src/test/java/com/ozkanmut/ilactakip/SyncTransportTest.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/SyncEngine.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/AlertOutbox.kt`

**Interfaces:**
- Produces `interface SyncTransport { fun flushPendingBlocking(Context): Boolean; fun pullBlocking(Context): Boolean }`.
- Produces `object NtfySyncTransport : SyncTransport` as a temporary adapter over current behavior.
- Later tasks replace the selected transport without rewriting domain guards.

- [ ] **Step 1: Write failing selection test** asserting the default adapter delegates existing ntfy flush/pull and that domain processing remains outside the transport.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*SyncTransportTest*'` and require failure because `SyncTransport` does not exist.
- [ ] **Step 3: Implement minimal interface and ntfy adapter**; move no domain parsing or state application into the adapter.
- [ ] **Step 4: Change `DosefolkSyncWorker`/`SyncEngine` call sites** to consume the adapter while preserving current output and retry semantics.
- [ ] **Step 5: Run** targeted test, full Android unit tests, and Build APK workflow.
- [ ] **Step 6: Commit** `Introduce sync transport boundary`.

---

### Task 2: Add physically separated relay metadata and queue stores

**Files:**
- Create: `server/push-gateway/src/relay-metadata-store.mjs`
- Create: `server/push-gateway/src/relay-queue-store.mjs`
- Create: `server/push-gateway/test/relay-storage.test.mjs`
- Modify: `server/push-gateway/package.json`
- Modify: `server/push-gateway/package-lock.json`
- Modify: `server/push-gateway/Dockerfile`
- Modify: `server/push-gateway/docker-compose.yml`

**Interfaces:**
- `openMetadataStore(path)` returns operations for installations, credentials, public keys, routes, pairing records and push targets.
- `openRelayQueue(path)` returns operations for pending ciphertext, recipient sequence, ACK/delete, purge and queue quotas.
- Metadata DB path: `/data/metadata/relay-metadata.sqlite3`.
- Queue DB path: `/data/queue/relay-queue.sqlite3`.

**Required schema:**

```sql
CREATE TABLE installations (
  install_id TEXT PRIMARY KEY,
  platform TEXT NOT NULL CHECK(platform IN ('android','ios')),
  credential_hash TEXT NOT NULL,
  encryption_public_key TEXT NOT NULL,
  signing_public_key TEXT NOT NULL,
  key_version INTEGER NOT NULL,
  revoked_at INTEGER,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL
);
CREATE TABLE routes (
  route_id TEXT PRIMARY KEY,
  sender_install_id TEXT NOT NULL,
  recipient_install_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('active','revoked')),
  created_at INTEGER NOT NULL,
  revoked_at INTEGER
);
CREATE TABLE relay_messages (
  message_id TEXT PRIMARY KEY,
  relay_seq INTEGER NOT NULL,
  route_id TEXT NOT NULL,
  sender_install_id TEXT NOT NULL,
  recipient_install_id TEXT NOT NULL,
  sender_key_version INTEGER NOT NULL,
  recipient_key_version INTEGER NOT NULL,
  ciphertext BLOB NOT NULL,
  received_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  next_wake_at INTEGER,
  wake_attempts INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX relay_recipient_seq
  ON relay_messages(recipient_install_id, relay_seq);
```

- [ ] **Step 1: RED tests** verify separate DB files, schema constraints, duplicate message idempotency, per-recipient monotonic sequence and restart durability.
- [ ] **Step 2: Run** `cd server/push-gateway && npm test -- --test-name-pattern='relay storage'` and verify module-not-found failure.
- [ ] **Step 3: Pin** `better-sqlite3` to `13.0.3`; regenerate lockfile; Docker must install production dependencies with `npm ci --omit=dev`.
- [ ] **Step 4: Implement stores** with WAL, foreign keys, prepared statements, explicit transactions and no domain columns.
- [ ] **Step 5: Compose mounts** metadata and queue directories separately so production backup policy can exclude the queue path.
- [ ] **Step 6: Run** `npm run check`, `npm test`, Docker build.
- [ ] **Step 7: Commit** `Add isolated encrypted relay storage`.

---

### Task 3: Replace ntfy provisioning credentials with native install auth

**Files:**
- Create: `server/push-gateway/src/install-auth.mjs`
- Create: `server/push-gateway/test/install-auth.test.mjs`
- Modify: `server/push-gateway/src/server.mjs`
- Modify: `server/push-gateway/src/provisioning-credentials.mjs`

**Interfaces:**
- `issueInstallCredential()` -> `{ credential, credentialHash }` using 32 random bytes and SHA-256 hash.
- `authenticateInstall(authorizationHeader, metadataStore)` -> authenticated install or `null`.
- `POST /v1/provision` accepts platform + public identity keys and returns credential exactly once.

- [ ] **Step 1: RED tests** cover missing auth, wrong token, revoked install, credential not persisted plaintext, same token authenticates, random token does not.
- [ ] **Step 2: Run** gateway tests and verify expected failure.
- [ ] **Step 3: Implement native install auth** using constant-time hash comparison where applicable.
- [ ] **Step 4: Provision response must contain no `ntfyToken`** and metadata DB must contain only the hash.
- [ ] **Step 5: Preserve legacy endpoints behind existing code only until cutover; do not remove ntfy yet.**
- [ ] **Step 6: Full gateway regression + container build.**
- [ ] **Step 7: Commit** `Add native relay install authentication`.

---

### Task 4: Implement directional routes and authorization

**Files:**
- Create: `server/push-gateway/src/relay-routes.mjs`
- Create: `server/push-gateway/test/relay-routes.test.mjs`
- Modify: `server/push-gateway/src/server.mjs`

**Interfaces:**
- `listRoutesForSender(installId)`.
- `authorizeRoute(routeId, authenticatedSender)`.
- `revokeRoute(routeId, actorInstallId)`.
- `GET /v1/routes` returns active recipients plus current public-key metadata.

- [ ] **Step 1: RED tests** verify A->B permits only A enqueue to B, B cannot impersonate A, revoked routes fail, revoked installs fail, key versions are returned but are not server trust anchors.
- [ ] **Step 2: Implement route authorization** with sender identity entirely derived from bearer credential.
- [ ] **Step 3: Add route revocation transaction** that marks route revoked and purges queue rows for that route.
- [ ] **Step 4: Run gateway tests and Docker build.**
- [ ] **Step 5: Commit** `Add directional relay routing`.

---

### Task 5: Implement pairing v2 server transcript flow

**Files:**
- Create: `server/push-gateway/src/relay-pairing.mjs`
- Create: `server/push-gateway/test/relay-pairing.test.mjs`
- Modify: `server/push-gateway/src/server.mjs`

**Interfaces:**
- `POST /v1/pairing/offers` registers `offerId`, creator install, expiry and secret proof/hash only.
- `POST /v1/pairing/offers/:id/accept` records the peer transcript proof.
- `POST /v1/pairing/offers/:id/confirm` atomically activates routes after both device proofs are valid.
- Pairing offer hard lifetime: 10 minutes.

- [ ] **Step 1: RED tests** verify plaintext pairing secret is never stored, expired/replayed offer fails, server cannot confirm without client proofs, revoked identity cannot silently re-pair.
- [ ] **Step 2: Implement pairing state** as short-lived metadata only.
- [ ] **Step 3: Activation transaction creates directional A->B and B->A routes only after confirmation.**
- [ ] **Step 4: Purge expired offers on access and periodic cleanup.**
- [ ] **Step 5: Commit** `Add authenticated relay pairing flow` after full gateway tests.

---

### Task 6: Implement message enqueue, inbox and ACK semantics

**Files:**
- Create: `server/push-gateway/src/relay-messages.mjs`
- Create: `server/push-gateway/test/relay-messages.test.mjs`
- Modify: `server/push-gateway/src/server.mjs`

**Interfaces:**
- `POST /v1/messages` batch-enqueues recipient-specific ciphertext.
- `GET /v1/inbox?limit=100` returns oldest unacked rows for authenticated recipient.
- `POST /v1/messages/ack` terminally deletes recipient-owned rows.

**Limits:** 512 KiB/message, 10,000 pending messages/recipient, 100 MiB pending bytes/recipient, inbox max 100 rows and 1 MiB body.

- [ ] **Step 1: RED tests** cover route authorization, duplicate `messageId`, no plaintext event fields, 30-day server TTL, batch bounds, recipient isolation, ACK ownership and ACK idempotency.
- [ ] **Step 2: Implement enqueue transaction** assigning a monotonic recipient `relay_seq` and `expires_at = serverNow + 30 days` regardless of client input.
- [ ] **Step 3: Implement inbox** with no destructive cursor; repeated GET before ACK must return the same messages.
- [ ] **Step 4: Implement terminal ACK** for `processed|duplicate|rejected`; never persist detailed reject reason.
- [ ] **Step 5: Run gateway tests + Docker build.**
- [ ] **Step 6: Commit** `Add durable encrypted relay inbox`.

---

### Task 7: Add retention, quotas and privacy-safe observability

**Files:**
- Create: `server/push-gateway/src/relay-retention.mjs`
- Create: `server/push-gateway/src/relay-metrics.mjs`
- Create: `server/push-gateway/test/relay-retention.test.mjs`
- Create: `server/push-gateway/test/relay-privacy.test.mjs`
- Modify: `server/push-gateway/src/server.mjs`

**Interfaces:**
- `purgeExpired(now)` deletes all `expires_at <= now` rows.
- `relayMetrics()` exposes aggregate counts/age/bytes only.

- [ ] **Step 1: RED tests** prove messages cannot be read after TTL even before purge job, purge removes expired rows, quota rejection never deletes existing rows, and logs never include secrets/ciphertext/domain fields.
- [ ] **Step 2: Implement hourly cleanup plus read-time expiry filtering.**
- [ ] **Step 3: Add rate limit 120 requests/min/install** with bounded in-memory counters suitable for single-node V1.
- [ ] **Step 4: Health/metrics expose only aggregate transport state.**
- [ ] **Step 5: Commit** `Enforce relay retention and privacy limits`.

---

### Task 8: Drive generic push wakes from the encrypted queue

**Files:**
- Create: `server/push-gateway/src/relay-wake-scheduler.mjs`
- Create: `server/push-gateway/test/relay-wake-scheduler.test.mjs`
- Modify: `server/push-gateway/src/push-dispatcher.mjs`
- Modify: `server/push-gateway/src/server.mjs`

**Interfaces:**
- Enqueue creates/coalesces one pending wake job per recipient.
- Retry schedule: T+0, 2m, 15m, 1h, 6h, 24h, then daily while inbox remains pending.
- Push payload remains exactly the two-field wake object.

- [ ] **Step 1: RED tests** verify ten messages for one recipient coalesce, provider failure does not roll back queue insertion, FCM/APNs success is not delivery confirmation, and ACK-drained inbox cancels future wake attempts.
- [ ] **Step 2: Implement scheduler** using queue metadata, not domain data.
- [ ] **Step 3: Reuse existing `PushDispatcher`, `FCMClient`, and APNs adapter.**
- [ ] **Step 4: Full gateway regression.**
- [ ] **Step 5: Commit** `Wake relay recipients without exposing payloads`.

---

### Task 9: Add Android E2EE identity and pinned peer store

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayIdentityStore.kt`
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayPeerStore.kt`
- Create: `app/src/test/java/com/ozkanmut/ilactakip/RelayIdentityStoreTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Pin `com.google.crypto.tink:tink-android:1.23.0`.
- Local identity exposes public encryption/signing keys and opaque key version, never raw private keys.
- Peer store pins install ID + encryption key + signing key + version + fingerprint + revoked tombstone.

- [ ] **Step 1: RED tests** for stable identity across restart, public export only, peer key mismatch reject, revoked peer reject.
- [ ] **Step 2: Add Tink dependency** and register hybrid/signature primitives.
- [ ] **Step 3: Store Tink keysets encrypted using Android Keystore-backed master protection; no private-key log/export API.**
- [ ] **Step 4: Implement fingerprint as SHA-256 over canonical public identity bundle.**
- [ ] **Step 5: Run Android tests + APK build.**
- [ ] **Step 6: Commit** `Add device-owned relay identity keys`.

---

### Task 10: Add canonical signed HPKE envelope codec and cross-platform fixtures

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayCrypto.kt`
- Create: `app/src/test/java/com/ozkanmut/ilactakip/RelayCryptoTest.kt`
- Create: `protocol-fixtures/relay-envelope-v1.json`
- Create: `protocol-fixtures/relay-pairing-v2.json`
- Create: `ios/Dosefolk/Dosefolk/RelayProtocol.swift`
- Create: `ios/Dosefolk/DosefolkTests/RelayProtocolTests.swift`
- Modify: `.github/workflows/cross-platform-protocol-gate.yml`

**Interfaces:**
- `RelayCrypto.seal(domainPayload, recipient, outerContext): ByteArray`.
- `RelayCrypto.open(ciphertext, sender, outerContext): VerifiedPayload`.
- Outer context binds message/route/install/key-version fields.

- [ ] **Step 1: RED Android fixture tests** for deterministic canonical bytes/signature verification, AAD/context mutation rejection, wrong recipient rejection and wrong sender signature rejection.
- [ ] **Step 2: Implement canonical signed inner envelope** and HPKE seal/open using Tink; no handwritten crypto.
- [ ] **Step 3: Add synthetic fixture vectors** with test-only keys; no production key material.
- [ ] **Step 4: Add iOS fixture parser/verifier contract tests** sufficient for cross-platform byte/schema compatibility; do not claim device E2E.
- [ ] **Step 5: Cross-platform gate must be completed/success on exact SHA.**
- [ ] **Step 6: Commit** `Define cross-platform encrypted relay envelope`.

---

### Task 11: Upgrade Circle pairing to authenticated pairing v2

**Files:**
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/CirclePairingPayload.kt`
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayPairing.kt`
- Create: `app/src/test/java/com/ozkanmut/ilactakip/RelayPairingTest.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/PairingLifecycle.kt`

**Interfaces:**
- QR v2 contains offer ID, identity public keys, key version, 256-bit pairing secret, expiry and signature.
- Pairing secret expires after 10 minutes.
- Successful pairing pins peer identity before routes become usable.

- [ ] **Step 1: RED tests** for QR tamper, expiry, replay, server-key substitution, revoked peer re-pair without explicit flow.
- [ ] **Step 2: Implement v2 encode/parse/sign/verify** while retaining legacy parser only until migration cutoff.
- [ ] **Step 3: Implement accept/confirm API calls** and local fingerprint pinning.
- [ ] **Step 4: Preserve durable revoke tombstones; clear only after explicit successful re-pair.**
- [ ] **Step 5: Commit** `Secure Circle pairing with pinned relay identities` after Android regression.

---

### Task 12: Implement Android relay API, encrypted outbox and inbox processor

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayApi.kt`
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayOutbox.kt`
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayInbox.kt`
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelaySyncTransport.kt`
- Create: `app/src/test/java/com/ozkanmut/ilactakip/RelayTransportTest.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/SyncWorker.kt`

**Interfaces:**
- Production base URL is configuration, with cutover target `https://relay.field-maintenance-prod.com`.
- `RelaySyncTransport` implements Task 1 `SyncTransport`.
- ACK occurs only after existing domain persistence/guards finish.

- [ ] **Step 1: RED tests** for offline sender persistence, per-recipient encryption, duplicate delivery, crash-before-ACK behavior, rejected signature terminal ACK, and no ACK before durable apply.
- [ ] **Step 2: Implement authenticated HTTPS client** using stored install credential; never log Authorization/header/body.
- [ ] **Step 3: Outbox resolves active routes and pinned peer keys then creates one encrypted message per recipient.**
- [ ] **Step 4: Inbox decrypts/verifies first, then calls existing `IncomingEventGuard`, target/actor checks, `EventStore.appendIfAbsent`, and state application.**
- [ ] **Step 5: Send batched terminal ACKs after processing.**
- [ ] **Step 6: WorkManager retry remains bounded exponential; FCM/startup/periodic sync all call the same relay reconciliation path.**
- [ ] **Step 7: Commit** `Sync Dosefolk through encrypted relay` after full Android tests.

---

### Task 13: Add sequence-gap detection and E2EE reconciliation

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayContinuity.kt`
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayReconciliation.kt`
- Create: `app/src/test/java/com/ozkanmut/ilactakip/RelayReconciliationTest.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/CircleInitialSync.kt`

**Interfaces:**
- Track received `relay_seq` ranges locally.
- Gap/long-offline recovery uses ordinary encrypted messages with inner `reconciliation_request` / `reconciliation_snapshot` types.

- [ ] **Step 1: RED tests** for gap detection, no false gap on duplicate/out-of-order batch, coordinator peer selection and idempotent snapshot apply.
- [ ] **Step 2: Implement continuity ledger** as transport metadata only.
- [ ] **Step 3: Implement reconciliation request** to one deterministic trusted peer at a time.
- [ ] **Step 4: Snapshot is produced solely from local canonical state, signed/encrypted and sent through `/v1/messages`; server receives only ciphertext.**
- [ ] **Step 5: Commit** `Recover relay gaps with encrypted reconciliation`.

---

### Task 14: Implement key rotation, 31-day decrypt grace and device revocation

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayKeyRotation.kt`
- Create: `app/src/test/java/com/ozkanmut/ilactakip/RelayKeyRotationTest.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/PairingLifecycle.kt`
- Modify: `server/push-gateway/src/relay-routes.mjs`
- Modify: `server/push-gateway/src/server.mjs`

**Interfaces:**
- Rotation certificate is signed by prior signing key and binds install/new keys/version/time/nonce.
- Old private decrypt key expires locally after 31 days.

- [ ] **Step 1: RED tests** for unsigned rotation reject, wrong-old-key reject, rollback version reject, old queued ciphertext decrypt during grace and failure after simulated 31-day purge.
- [ ] **Step 2: Implement rotation certificate generation/verification and server public-key metadata update.**
- [ ] **Step 3: Implement revocation transaction** and client tombstone preservation.
- [ ] **Step 4: Ensure server key response can never overwrite a pinned peer without valid rotation proof.**
- [ ] **Step 5: Commit** `Add relay key rotation and revocation safety`.

---

### Task 15: Add device-transfer recovery without server escrow

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayDeviceTransfer.kt`
- Create: `app/src/test/java/com/ozkanmut/ilactakip/RelayDeviceTransferTest.kt`
- Create: `protocol-fixtures/relay-device-transfer-v1.json`

**Interfaces:**
- Old device signs a transfer certificate authorizing a newly generated install identity.
- Lost-device recovery without old device requires fresh trusted-peer pairing and encrypted reconciliation; the server cannot recover private keys.

- [ ] **Step 1: RED tests** for valid transfer, forged transfer, replayed nonce, revoked old device and fresh-peer recovery path.
- [ ] **Step 2: Implement transfer certificate and replay protection.**
- [ ] **Step 3: Add no-escrow invariant test: no API or persisted server field may contain private key material.**
- [ ] **Step 4: Commit** `Add no-escrow device transfer recovery`.

---

### Task 16: Add transport cutover gate without dual-writing real data

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/TransportMode.kt`
- Create: `app/src/test/java/com/ozkanmut/ilactakip/TransportCutoverTest.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/SyncWorker.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/MainActivity.kt`
- Modify: `.github/workflows/build-apk.yml`

**Interfaces:**
- Pre-cutover mode: `NTFY_ONLY`.
- Canary mode uses synthetic relay test identities only.
- Real cutover mode: `RELAY_ONLY`.
- There is deliberately no `DUAL_WRITE` mode.

- [ ] **Step 1: RED test** proving no code path can publish one real event to both transports.
- [ ] **Step 2: Implement explicit transport selector** and compile-time/release configuration.
- [ ] **Step 3: CI scans release source/config for forbidden accidental dual-write mode.**
- [ ] **Step 4: Exact-SHA Build APK + cross-platform gate success.**
- [ ] **Step 5: Commit** `Add atomic relay transport cutover`.

---

### Task 17: Production deploy encrypted relay canary

**Files:**
- Modify: `server/push-gateway/docker-compose.yml` or create deployment-specific compose under `server/push-gateway/deploy/relay-compose.yml`.
- Create: `docs/ENCRYPTED_RELAY_PRODUCTION.md`

**Operational target:** `/opt/dosefolk-relay`; production hostname `relay.field-maintenance-prod.com` after DNS/TLS verification.

- [ ] **Step 1: Require explicit production approval before write/deploy.**
- [ ] **Step 2: Read-only baseline:** capture current Dosefolk gateway/ntfy containers, ports, reverse proxy, disk paths, backups, exact deployed SHAs and health endpoints. Do not touch `/opt/field-maintenance/app`.
- [ ] **Step 3: Create `/opt/dosefolk-relay` with separate metadata/queue volumes and restrictive permissions.**
- [ ] **Step 4: Deploy exact CI-GREEN SHA only.**
- [ ] **Step 5: Configure reverse proxy/TLS for relay hostname; no ntfy route changes yet.**
- [ ] **Step 6: Synthetic canary:** provision two fake installs, pair, enqueue opaque ciphertext, wake, inbox, ACK, restart containers, verify persistence, simulate expiry, revoke and purge.
- [ ] **Step 7: Inspect DB schema/data directly to prove no plaintext domain fields and verify queue is excluded from long-term backup.**
- [ ] **Step 8: Record rollback commands and commit runbook changes.**

---

### Task 18: Real Android encrypted-relay E2E and cutover

**Files:** no new architecture files; only defects discovered by real test may be changed under TDD.

- [ ] **Step 1: Install exact CI artifact on two Android test identities/devices or controlled test profiles.**
- [ ] **Step 2: Verify pairing v2 fingerprints and server key-substitution rejection.**
- [ ] **Step 3: Verify real event delivery with app foreground, background, Doze/process-kill where testable, offline->online, duplicate wake, server restart and FCM delay.**
- [ ] **Step 4: Verify plaintext domain data is absent from relay DB, logs and FCM payload.**
- [ ] **Step 5: Verify ACK deletes ciphertext and unacked ciphertext survives restart.**
- [ ] **Step 6: Verify revoke immediately blocks new enqueue and purges pending ciphertext.**
- [ ] **Step 7: Verify sequence-gap reconciliation using synthetic forced expiry/gap.**
- [ ] **Step 8: Only after all gates pass, build `RELAY_ONLY` production artifact and verify exact-SHA CI success.**

---

### Task 19: Disable ntfy application traffic

**Files:**
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/SyncEngine.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/AlertOutbox.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/NtfyProvisioning.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/NtfyAccessRefresh.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/NtfyAuth.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/SyncWorker.kt`

- [ ] **Step 1: RED decommission test** searches compiled/runtime transport selection and fails while any production ntfy publish/poll/provision path remains reachable.
- [ ] **Step 2: Switch release build permanently to `RelaySyncTransport`.**
- [ ] **Step 3: Remove calls to ntfy provisioning, token refresh, ACL refresh and live sync from production lifecycle.**
- [ ] **Step 4: Keep rollback branch/tag externally; do not keep a hidden runtime ntfy fallback in release code.**
- [ ] **Step 5: Exact-SHA CI + real Android smoke on relay-only artifact.**
- [ ] **Step 6: Commit** `Cut Dosefolk over to encrypted relay`.

---

### Task 20: Remove ntfy code, server integration and runtime dependencies

**Files:**
- Delete after dependency graph proves unused: `server/push-gateway/src/ntfy-auth.mjs`, ntfy provisioning/self-test code that exists solely for ntfy.
- Delete/replace Android ntfy-only files after callers are gone.
- Modify: `server/push-gateway/Dockerfile`
- Modify: `server/push-gateway/docker-compose.yml`
- Modify: `server/push-gateway/package.json`
- Modify: `.github/workflows/build-apk.yml`
- Modify: `.github/workflows/cross-platform-protocol-gate.yml`
- Modify: `NTFY_AUTH_MIGRATION.md` to historical/deprecated notice or remove if no longer useful.

- [ ] **Step 1: RED repository invariant test** fails if release source contains ntfy host URLs, ntfy CLI/container dependency, ntfy token/ACL creation or ntfy runtime environment variables.
- [ ] **Step 2: Remove ntfy CLI from Dockerfile and ntfy service/runtime coupling from compose.**
- [ ] **Step 3: Remove dead ntfy Android/server source and tests; preserve only historical documentation if explicitly useful and clearly non-runtime.**
- [ ] **Step 4: Run repository-wide search for `ntfy`, inspect every remaining hit, and allow only historical migration documentation with no executable dependency.**
- [ ] **Step 5: Full gateway/Android/iOS protocol CI exact-SHA success.**
- [ ] **Step 6: Commit** `Remove ntfy runtime from Dosefolk`.

---

### Task 21: Production ntfy decommission and retention proof

**Operational target:** Dosefolk ntfy/gateway resources only; never `/opt/field-maintenance/app`.

- [ ] **Step 1: Require explicit production approval.**
- [ ] **Step 2: Observe a defined no-ntfy-traffic window after relay-only release and confirm clients use only relay endpoints.**
- [ ] **Step 3: Back up only non-health configuration needed for rollback; do not extend queue retention.**
- [ ] **Step 4: Stop and disable Dosefolk ntfy container/service and remove reverse-proxy runtime route once no traffic is confirmed.**
- [ ] **Step 5: Remove ntfy credentials, ACL DB/token state and obsolete Dosefolk ntfy data according to the approved deletion procedure.**
- [ ] **Step 6: Verify relay metadata backup works while relay queue is excluded from long-term backup/PITR.**
- [ ] **Step 7: Prove 30-day hard TTL using clock-controlled integration test plus production configuration inspection; GET must never return expired rows even before cleanup runs.**
- [ ] **Step 8: Final security regression:** no plaintext health data server-side, no ntfy runtime, generic-only push, revoked-route purge, credential/key/log redaction, exact-SHA CI `completed/success`.

---

## Completion Gate

The migration is complete only when all of the following are simultaneously true:

```text
[ ] encrypted-relay-migration exact SHA CI completed/success
[ ] relay-only Android real E2E passed
[ ] server never receives/stores plaintext domain payload
[ ] ACK immediately deletes ciphertext
[ ] 30-day hard TTL verified
[ ] queue backup/PITR retention <= 30 days
[ ] FCM/APNs payload generic only
[ ] pairing keys pinned from authenticated device-to-device bootstrap
[ ] key rotation old-key authorized
[ ] revoke purges pending ciphertext and blocks new enqueue
[ ] reconciliation remains E2EE opaque to server
[ ] no real dual-write path exists
[ ] no ntfy app/runtime dependency remains
[ ] Dosefolk ntfy production service is stopped/decommissioned
[ ] main remains untouched until explicit merge approval
```

## Execution order

Tasks are intentionally sequential at the trust/data-loss boundaries:

`1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9 -> 10 -> 11 -> 12 -> 13 -> 14 -> 15 -> 16 -> 17 -> 18 -> 19 -> 20 -> 21`

Server foundation is completed before mobile cutover. Production writes occur only in Tasks 17 and 21 and require explicit approval. Task 18 is the irreversible trust gate for switching real traffic; ntfy is not disabled before it passes.
