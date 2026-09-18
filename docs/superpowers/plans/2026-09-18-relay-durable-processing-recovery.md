# Relay Durable Processing Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Task 12's durable inbox/outbox recovery semantics without activating relay runtime traffic.

**Architecture:** A strict relay journal gates ACK eligibility, a checked domain-effect adapter gates the journal's EFFECTS phase, and a source-level outbox snapshot prevents recipient expansion or stale-route delivery. ACK recovery is idempotent and chunked at the server's 100-item bound.

**Tech Stack:** Kotlin, Android SharedPreferences, Robolectric/JUnit, existing Tink relay crypto, GitHub Actions Android/iOS gates.

**Spec:** `docs/superpowers/specs/2026-09-18-relay-durable-processing-recovery-design.md`

## Global Constraints

- Work only on `encrypted-relay-migration`; never modify `main`, production, or field-maintenance VDS paths.
- Keep `SyncTransportRuntime.current` on ntfy through Task 16; never dual-write real domain traffic.
- Never log or persist relay credential, ciphertext, keys, pairing secret, or domain payload outside existing encrypted/event storage.
- ACK outcomes are only `processed`, `duplicate`, `rejected`; ACK batch size is at most 100.
- Every task follows RED -> expected GitHub CI failure -> minimal GREEN -> full regression -> exact SHA `completed/success`.

---

### Task 1: Harden journal replay and bounded ACK recovery

**Files:**
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/RelayInboxJournal.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/RelayInbox.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/IncomingEventGuard.kt`
- Modify: `app/src/test/java/com/ozkanmut/ilactakip/RelayTransportTest.kt`

**Interfaces:**
- `RelayInboxJournal` decodes `JSONObject.NULL` explicitly and validates phase/outcome combinations.
- `RelayInbox.reconcile()` calls `RelayApi.acknowledge()` only with chunks of `1..100` entries.
- Relay receipt compaction accepts the exact event IDs from one successful ACK chunk.

- [ ] **Step 1: Write failing tests** for null round trips in `STARTED`, `APPENDED`, `EFFECTS`; 101 terminal records; mixed old pending ACK plus full inbox; lost response on a later ACK chunk; and unrelated receipt preservation.
- [ ] **Step 2: Publish the RED-only commit** and confirm the first Android reliability failure is the new relay recovery expectation.
- [ ] **Step 3: Implement minimal journal decoding, chunking, exact receipt compaction, and per-chunk journal removal.**
- [ ] **Step 4: Publish GREEN and verify full Android tests, Build APK, and Cross-Platform Gate for its exact SHA.**

### Task 2: Make inbound domain effects checked and resumable

**Files:**
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/RelayInbox.kt`
- Create: `app/src/main/java/com/ozkanmut/ilactakip/RelayDomainEffects.kt`
- Modify: `OwnerScope.kt`, `MedicationForm.kt`, `PrnEngine.kt`, `StockEngine.kt`, `SyncEngine.kt`
- Modify relevant program/rule/capability/circle/revoke stores reached by `SyncEngine.applyRemoteState`
- Modify: `app/src/test/java/com/ozkanmut/ilactakip/RelayTransportTest.kt`

**Interfaces:**
- `RelayDomainEffects.apply(event)` returns only after all required persistence commits have succeeded or throws before `EFFECTS`.
- Replay of `APPENDED` is semantically idempotent; replay of `EFFECTS` performs no effects and only advances the terminal journal.

- [ ] **Step 1: Write failing failure-injection tests** for direct effect persistence failure, program/rule/capability ordering failures, and revoke cleanup failure; each asserts no `EFFECTS`, terminal journal, or ACK.
- [ ] **Step 2: Publish RED-only commit** and confirm Android reliability failure identifies the added durability contract.
- [ ] **Step 3: Add narrow checked-result APIs at every relay-reachable synchronous persistence boundary and route RelayInbox through one checked bundle.**
- [ ] **Step 4: Publish GREEN and verify exact-SHA full regression.**

### Task 3: Snapshot recipients and retire stale routes safely

**Files:**
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/RelayOutbox.kt`
- Modify: `app/src/test/java/com/ozkanmut/ilactakip/RelayTransportTest.kt`
- Modify backup rule tests/resources if durable snapshot storage changes.

**Interfaces:**
- A source event receives one durable recipient snapshot after first successful route resolution.
- `flush()` validates each pending record against current authenticated route ID and pinned identity before enqueue.
- A successful route read can retire stale ciphertext but a route-read failure cannot alter local outbox state.

- [ ] **Step 1: Write failing tests** for newly paired recipient after partial retry, revoked route, replaced route ID/key version, accepted record after peer revoke, and routes API failure retaining evidence.
- [ ] **Step 2: Publish RED-only commit** and verify expected Android failure.
- [ ] **Step 3: Implement snapshot, active-route reconciliation, fail-closed retirement, and stable completion cleanup.**
- [ ] **Step 4: Publish GREEN and verify exact-SHA full regression.**

### Task 4: Task 12 final integration review

**Files:**
- Modify: `app/src/test/java/com/ozkanmut/ilactakip/RelayTransportTest.kt` only if integration evidence needs a focused regression.

- [ ] **Step 1: Run complete exact-SHA CI and inspect jobs/logs.**
- [ ] **Step 2: Dispatch a fresh read-only reviewer against the final Task 12 range.**
- [ ] **Step 3: Resolve any Critical/Important findings with a new RED/GREEN cycle, then re-review.**
- [ ] **Step 4: Record Task 12 complete only after final HEAD check-runs are `completed/success`; do not begin Task 13.**
