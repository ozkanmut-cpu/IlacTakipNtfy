# Android FCM + Multi-Provider Push Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Android FCM wake support while keeping ntfy as the authenticated source of truth, preserving legacy iOS/APNs registrations, and allowing Android push to work when APNs is unavailable.

**Architecture:** Keep one push-gateway process. Add an `FCMClient` adapter and provider-independent `PushDispatcher`; keep topic selection, provisioning, ntfy ACLs, and install authentication shared. Android push is wake-only: `FirebaseMessagingService` schedules WorkManager reconciliation and never applies domain data directly.

**Tech Stack:** Node.js 22, `firebase-admin` 14.4.0, Android/Kotlin, Firebase Android BoM 34.19.0, Google Services Gradle plugin 4.5.0, Firebase Cloud Messaging, WorkManager 2.10.0, JUnit/Robolectric, Node test runner, Docker, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-16-android-fcm-push-gateway-design.md`

## Global Constraints

- Work only on `ios-milestone-1`; do not modify or merge `main`.
- ntfy remains the synchronization source of truth; APNs/FCM are wake-only transports.
- Push payloads contain only `wakeType=sync` and `protocolVersion=1`; never medication, dose, person, schedule, event, credential, or topic-secret data.
- APNs and FCM readiness are independent.
- Legacy request/stored records without `platform` mean iOS/APNs.
- Legacy `deviceToken` remains readable; new writes use `pushToken`.
- Firebase service-account JSON is never committed. Production uses `/run/secrets/firebase-service-account.json` through `GOOGLE_APPLICATION_CREDENTIALS`.
- Exact implementation SHA must have completed/successful required CI before being called GREEN.
- No Apple account, APNs credential acquisition, iPhone validation, TestFlight, or App Store work is part of this plan.

---

## File Structure

### Gateway

- Create `server/push-gateway/src/push-registration.mjs`: platform/token validation and legacy normalization.
- Create `server/push-gateway/src/fcm.mjs`: FCM readiness, wake send, error classification.
- Create `server/push-gateway/src/push-dispatcher.mjs`: provider routing, retries, provider-local outcomes.
- Modify `server/push-gateway/src/server.mjs`: health, provider-local registration gate, mixed wake dispatch, stale target cleanup.
- Modify `server/push-gateway/src/wake-targets.mjs`: skip records with no usable push target.
- Create `server/push-gateway/test/push-registration.test.mjs`, `fcm.test.mjs`, `push-dispatcher.test.mjs`.
- Modify `server/push-gateway/test/provisioning.test.mjs` and `wake-targets.test.mjs`.
- Modify `server/push-gateway/package.json`, create/update `package-lock.json`, modify `Dockerfile`, `docker-compose.yml`, `.gitignore`.

### Android

- Create `app/src/main/java/com/ozkanmut/ilactakip/FcmRegistration.kt`: registration policy, metadata, worker, authenticated `/v1/register` client.
- Create `app/src/main/java/com/ozkanmut/ilactakip/DosefolkFirebaseMessagingService.kt`: wake and token callbacks.
- Modify `SyncWorker.kt`, `NtfyProvisioning.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`.
- Create `FcmRegistrationPolicyTest.kt` and `FcmWakeRoutingTest.kt`.

### CI / production

- Modify `.github/workflows/build-apk.yml` to run gateway syntax checks before gateway tests.
- Production VDS changes happen only after deterministic exact-SHA CI is GREEN and Firebase credentials exist.

---

### Task 1: Registration normalization contract

**Files:**
- Create: `server/push-gateway/src/push-registration.mjs`
- Test: `server/push-gateway/test/push-registration.test.mjs`

**Interfaces:**
- `normalizePlatform(value) -> 'ios' | 'android' | null`
- `validatePushRegistration(body) -> { ok, platform, provider, pushToken, environment?, error? }`
- `readStoredPushTarget(install) -> { platform, provider, pushToken, environment? } | null`

- [ ] **Step 1: Write RED tests**

```js
import test from 'node:test';
import assert from 'node:assert/strict';
import { validatePushRegistration, readStoredPushTarget } from '../src/push-registration.mjs';

test('legacy request defaults to iOS and accepts deviceToken', () => {
  const token = 'a'.repeat(64);
  const result = validatePushRegistration({ deviceToken: token, environment: 'sandbox' });
  assert.equal(result.ok, true);
  assert.equal(result.platform, 'ios');
  assert.equal(result.provider, 'apns');
  assert.equal(result.pushToken, token);
});

test('android accepts opaque bounded target', () => {
  const result = validatePushRegistration({ platform: 'android', pushToken: 'fcm-token:abc_123-XYZ' });
  assert.equal(result.ok, true);
  assert.equal(result.provider, 'fcm');
});

test('legacy stored deviceToken remains readable', () => {
  assert.equal(readStoredPushTarget({ deviceToken: 'b'.repeat(64) }).platform, 'ios');
});
```

Validation: Android target is trimmed, non-empty, at most 4096 UTF-16 code units. iOS retains `/^[0-9a-f]{64,256}$/i`.

- [ ] **Step 2: Verify RED**

Run: `cd server/push-gateway && node --test test/push-registration.test.mjs`

Expected: module-not-found failure.

- [ ] **Step 3: Implement minimal pure module**

Return `unsupported_platform` or `invalid_push_token` without reading env/files.

- [ ] **Step 4: Verify GREEN**

```bash
cd server/push-gateway
node --test test/push-registration.test.mjs
npm test
```

- [ ] **Step 5: Commit**

```bash
git add server/push-gateway/src/push-registration.mjs server/push-gateway/test/push-registration.test.mjs
git commit -m "Add multi-platform push registration contract"
```

---

### Task 2: FCM provider adapter

**Files:**
- Create: `server/push-gateway/src/fcm.mjs`
- Test: `server/push-gateway/test/fcm.test.mjs`
- Modify: `server/push-gateway/package.json`
- Create/update: `server/push-gateway/package-lock.json`
- Modify: `server/push-gateway/Dockerfile`

**Interfaces:**
- `FCMClient.ready() -> Promise<boolean>`
- `FCMClient.wake(pushToken) -> Promise<{ messageId:string }>`
- `classifyFcmError(error) -> 'invalid_target' | 'transient' | 'permanent'`

- [ ] **Step 1: Write RED tests with injected sender/probe**

```js
test('ready false without credential path', async () => {
  assert.equal(await new FCMClient({ credentialPath: '' }).ready(), false);
});

test('wake sends only minimal wake data', async () => {
  let sent;
  const client = new FCMClient({
    credentialPath: '/fake/firebase-service-account.json',
    credentialProbe: async () => true,
    sender: async message => { sent = message; return 'm1'; }
  });
  await client.wake('target-1');
  assert.deepEqual(sent, {
    token: 'target-1',
    data: { wakeType: 'sync', protocolVersion: '1' },
    android: { priority: 'normal' }
  });
});
```

Also test invalid-target codes and transient quota/5xx/network classification.

- [ ] **Step 2: Verify RED**

Run: `cd server/push-gateway && node --test test/fcm.test.mjs`

- [ ] **Step 3: Add exact dependency and Docker install**

```json
"dependencies": { "firebase-admin": "14.4.0" }
```

Dockerfile must include:

```dockerfile
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
```

Keep Node 22 and bundled ntfy 2.28.0.

- [ ] **Step 4: Implement adapter**

Use Firebase Admin modular imports and Application Default Credentials. Missing/unreadable/malformed credential configuration returns `ready:false`, not process crash. No token/credential logging.

- [ ] **Step 5: Verify GREEN and image**

```bash
cd server/push-gateway
node --test test/fcm.test.mjs
npm test
docker build -t dosefolk-push-gateway:fcm-adapter .
docker run --rm --entrypoint ntfy dosefolk-push-gateway:fcm-adapter --version
```

- [ ] **Step 6: Commit**

```bash
git add server/push-gateway/package.json server/push-gateway/package-lock.json server/push-gateway/Dockerfile server/push-gateway/src/fcm.mjs server/push-gateway/test/fcm.test.mjs
git commit -m "Add fail-closed FCM provider adapter"
```

---

### Task 3: Provider dispatcher

**Files:**
- Create: `server/push-gateway/src/push-dispatcher.mjs`
- Test: `server/push-gateway/test/push-dispatcher.test.mjs`

**Interfaces:**
- `PushDispatcher.readiness() -> Promise<{apns:boolean,fcm:boolean}>`
- `PushDispatcher.dispatch(install) -> Promise<{provider,status}>`
- Status: `sent | unavailable | invalid_target | transient_error | permanent_error | missing_target`.

- [ ] **Step 1: Write RED tests**

Cover legacy APNs routing, Android FCM routing, APNs false/FCM true readiness, FCM failure not blocking independent APNs dispatch, and no target leakage in returned results.

- [ ] **Step 2: Verify RED**

Run: `cd server/push-gateway && node --test test/push-dispatcher.test.mjs`

- [ ] **Step 3: Implement dispatcher**

Transient policy: initial attempt + at most two retries; exponential delay + injectable jitter/sleeper. Invalid target is not retried.

- [ ] **Step 4: Verify GREEN**

Run: `cd server/push-gateway && npm test`

- [ ] **Step 5: Commit**

```bash
git add server/push-gateway/src/push-dispatcher.mjs server/push-gateway/test/push-dispatcher.test.mjs
git commit -m "Route push wakes through independent providers"
```

---

### Task 4: Gateway health, registration, wake routing, stale cleanup

**Files:**
- Modify: `server/push-gateway/src/server.mjs`
- Modify: `server/push-gateway/src/wake-targets.mjs`
- Modify: `server/push-gateway/test/provisioning.test.mjs`
- Modify: `server/push-gateway/test/wake-targets.test.mjs`

**Interfaces:**
- Health: `ok`, `ready`, `provisioningReady`, `providers.apns.ready`, `providers.fcm.ready`.
- `/v1/register`: provider-local readiness only.
- `/internal/wake`: aggregate + per-provider counts.
- Invalid target clears only push-target fields; install identity/subscriptions remain.

- [ ] **Step 1: Add RED regression cases**

```text
provisioning=true, APNs=false, FCM=true => health.ready=true
legacy iOS deviceToken registration still works
Android registration persists platform=android + pushToken
Android register with FCM=false => 503 push_provider_unavailable/fcm
iOS register with APNs=false => 503 push_provider_unavailable/apns
mixed wake returns APNs and FCM provider counts
invalid FCM target removes only push target
```

- [ ] **Step 2: Verify RED**

```bash
cd server/push-gateway
node --test test/provisioning.test.mjs test/wake-targets.test.mjs
```

- [ ] **Step 3: Implement minimal server refactor**

Remove the current global APNs-only gate. Provisioning routes depend only on provisioning readiness. Register validates the requested provider. Wake dispatches all targets independently.

New records:

```js
store.installs[installId] = {
  platform,
  pushToken,
  ...(platform === 'ios' ? { environment, bundleId: cfg.bundleId } : {}),
  subscriptions,
  updatedAt: Date.now()
};
```

Wake target selection skips records with no readable target.

- [ ] **Step 4: Verify GREEN**

```bash
cd server/push-gateway
npm test
npm run check
docker build -t dosefolk-push-gateway:multi-provider .
```

- [ ] **Step 5: Commit**

```bash
git add server/push-gateway/src/server.mjs server/push-gateway/src/wake-targets.mjs server/push-gateway/test/provisioning.test.mjs server/push-gateway/test/wake-targets.test.mjs
git commit -m "Make push gateway provider independent"
```

---

### Task 5: Firebase secret/config hardening and CI gate

**Files:**
- Modify: `server/push-gateway/docker-compose.yml`
- Modify: `server/push-gateway/.gitignore`
- Modify: `server/push-gateway/package.json`
- Modify: `.github/workflows/build-apk.yml`

- [ ] **Step 1: Extend FCM test for configured-but-missing secret**

Expected: `ready:false`, no secret/token returned or logged.

- [ ] **Step 2: Add compose env**

```yaml
GOOGLE_APPLICATION_CREDENTIALS: /run/secrets/firebase-service-account.json
```

Reuse existing `./secrets:/run/secrets:ro` mount.

- [ ] **Step 3: Harden ignore/check rules**

Keep `secrets/`, `.env`, `*.p8`; add `firebase-service-account.json` and `*service-account*.json`. Extend `npm run check` to `fcm.mjs`, `push-dispatcher.mjs`, `push-registration.mjs`. Add `npm run check` before `npm test` in Build APK CI.

- [ ] **Step 4: Verify**

```bash
cd server/push-gateway
npm run check
npm test
docker compose config >/tmp/dosefolk-compose-config.txt
```

- [ ] **Step 5: Commit**

```bash
git add server/push-gateway/docker-compose.yml server/push-gateway/.gitignore server/push-gateway/package.json .github/workflows/build-apk.yml
git commit -m "Harden Firebase push gateway configuration"
```

---

### Task 6: Android authenticated FCM registration

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/FcmRegistration.kt`
- Test: `app/src/test/java/com/ozkanmut/ilactakip/FcmRegistrationPolicyTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- `FcmRegistrationPolicy.shouldRegister(isProvisioned, hasInstallId, hasGatewayCredential, tokenPresent, tokenHashChanged, stale) -> Boolean`
- `FcmRegistrationScheduler.ensure(context)` / `refresh(context)`.
- Registration endpoint: `https://ntfy.field-maintenance-prod.com/dosefolk-push/v1/register`.
- Persist raw-token SHA-256 + successful registration timestamp only; never raw token in app-owned prefs/logs.

- [ ] **Step 1: Add dependencies and RED policy tests**

```kotlin
implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
implementation("com.google.firebase:firebase-messaging")
```

Do not apply Google Services plugin until Task 10 has the real Firebase config.

Tests: unprovisioned, missing install ID, missing gateway credential, first token, changed token, unchanged fresh token, stale registration.

- [ ] **Step 2: Verify RED**

Run: `gradle testDebugUnitTest --tests '*FcmRegistrationPolicyTest'`

- [ ] **Step 3: Implement worker/client**

Use unique work `dosefolk-fcm-registration`, network constraint, `ExistingWorkPolicy.REPLACE`, bounded backoff. Obtain current Firebase target on worker thread; Firebase initialization/token retrieval failure returns retry/no-op without changing ntfy provisioning state.

Request:

```json
{
  "installId": "...",
  "platform": "android",
  "pushToken": "...",
  "localTopic": "...",
  "subscriptions": []
}
```

Header: `Authorization: Bearer <gateway credential>`. HTTP 204 means success. HTTP 409 follows existing Ntfy reprovision-required behavior. Do not log token/header values.

- [ ] **Step 4: Verify GREEN**

Run: `gradle testDebugUnitTest`

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/ozkanmut/ilactakip/FcmRegistration.kt app/src/test/java/com/ozkanmut/ilactakip/FcmRegistrationPolicyTest.kt
git commit -m "Add authenticated Android FCM registration"
```

---

### Task 7: FirebaseMessagingService -> WorkManager wake routing

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/DosefolkFirebaseMessagingService.kt`
- Test: `app/src/test/java/com/ozkanmut/ilactakip/FcmWakeRoutingTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/SyncWorker.kt`

**Interfaces:**
- Pure helper: `FcmWakeRouter.action(data, deletedMessages) -> KICK_SYNC | FULL_RECONCILIATION | IGNORE`.
- `onNewToken()` separately calls `FcmRegistrationScheduler.refresh(applicationContext)`; it is not part of `FcmWakeRouter.action`.
- Valid wake requires exactly `wakeType=sync` and `protocolVersion=1`.

- [ ] **Step 1: Write RED tests**

Test valid wake, wrong protocol, unrelated data, deleted-message recovery. Assert router ignores medication/event fields.

- [ ] **Step 2: Verify RED**

Run: `gradle testDebugUnitTest --tests '*FcmWakeRoutingTest'`

- [ ] **Step 3: Implement service + manifest**

```xml
<service
    android:name=".DosefolkFirebaseMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

Valid wake calls `DosefolkSyncScheduler.kick()`. Deleted messages call `DosefolkSyncScheduler.fullReconciliation()` using a dedicated unique work name and `ExistingWorkPolicy.REPLACE`.

- [ ] **Step 4: Verify GREEN**

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/ozkanmut/ilactakip/DosefolkFirebaseMessagingService.kt app/src/main/java/com/ozkanmut/ilactakip/SyncWorker.kt app/src/test/java/com/ozkanmut/ilactakip/FcmWakeRoutingTest.kt
git commit -m "Wake Android sync from FCM safely"
```

---

### Task 8: Registration lifecycle integration

**Files:**
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/NtfyProvisioning.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/MainActivity.kt`
- Extend: `app/src/test/java/com/ozkanmut/ilactakip/FcmRegistrationPolicyTest.kt`

- [ ] **Step 1: Add RED lifecycle tests**

Prove FCM registration scheduling needs ntfy/install/gateway prerequisites and Firebase unavailability never rolls back provisioning.

- [ ] **Step 2: Verify RED**

Run: `gradle testDebugUnitTest --tests '*FcmRegistrationPolicyTest'`

- [ ] **Step 3: Integrate**

After successful ntfy provisioning, call `FcmRegistrationScheduler.refresh(context)`. In `MainActivity.onCreate`, call `FcmRegistrationScheduler.ensure(this)` after current ntfy retry/pull startup calls. Never await Firebase on UI thread.

- [ ] **Step 4: Verify**

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

Expected: PASS without `google-services.json`; Firebase runtime unavailability is handled fail-soft until Task 10.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ozkanmut/ilactakip/NtfyProvisioning.kt app/src/main/java/com/ozkanmut/ilactakip/MainActivity.kt app/src/test/java/com/ozkanmut/ilactakip/FcmRegistrationPolicyTest.kt
git commit -m "Integrate FCM registration lifecycle"
```

---

### Task 9: Exact-SHA deterministic CI verification

**Files:**
- `.github/workflows/build-apk.yml` only if functional correction is required.

- [ ] **Step 1: Read exact branch HEAD**

Record full SHA.

- [ ] **Step 2: Confirm required pipeline**

```text
npm run check
npm test
docker build
ntfy version check
gradle testDebugUnitTest
gradle assembleDebug
```

- [ ] **Step 3: Verify exact HEAD workflow reaches `completed/success`**

Queued/in-progress is not GREEN. On failure inspect first failing step/log, fix root cause, and repeat on the new exact SHA.

- [ ] **Step 4: Do not create empty CI commits**

Commit only if workflow behavior actually changes.

---

### Task 10: Connect real Firebase Android project without server secrets

**Files:**
- Create: `app/google-services.json` from Firebase console for package `com.ozkanmut.ilactakip`.
- Modify: root `build.gradle.kts`.
- Modify: `app/build.gradle.kts`.

**Precondition:** Real Firebase project and downloaded Android config exist. Do not invent identifiers.

- [ ] **Step 1: Inspect config**

Require `package_name == "com.ozkanmut.ilactakip"`. Reject file containing `private_key` or `private_key_id`.

- [ ] **Step 2: Add exact Google Services plugin**

Root:

```kotlin
plugins {
    id("com.google.gms.google-services") version "4.5.0" apply false
}
```

App:

```kotlin
plugins {
    id("com.google.gms.google-services")
}
```

- [ ] **Step 3: Verify Android build**

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add app/google-services.json build.gradle.kts app/build.gradle.kts
git commit -m "Connect Dosefolk Android to Firebase"
```

- [ ] **Step 5: Verify exact commit CI `completed/success`**

---

### Task 11: Production gateway FCM credential canary

**Host/files:**
- `/opt/dosefolk-push-gateway`
- Secret: `/opt/dosefolk-push-gateway/secrets/firebase-service-account.json`
- Never touch `/opt/field-maintenance/app`.

**Precondition:** Dosefolk Firebase service-account JSON exists. Never paste private-key contents into chat, GitHub, shell history, or logs.

- [ ] **Step 1: Read-only production baseline**

Capture `/health`, container state, registration count, and current APNs readiness. Confirm intended release source before writes.

- [ ] **Step 2: Back up changed Dosefolk gateway files only**

Use timestamped backup names inside `/opt/dosefolk-push-gateway`.

- [ ] **Step 3: Install secret with restrictive permissions**

Use existing read-only `secrets` mount; never print file content.

- [ ] **Step 4: Deploy exact CI-GREEN gateway release only**

Rebuild/restart only `dosefolk-push-gateway`; preserve production-only compose additions.

- [ ] **Step 5: Verify independent readiness**

Expected while Apple credentials remain absent:

```json
{
  "ok": true,
  "ready": true,
  "provisioningReady": true,
  "providers": {
    "apns": { "ready": false },
    "fcm": { "ready": true }
  }
}
```

If FCM is false, stop canary and diagnose without weakening checks.

- [ ] **Step 6: Re-run ntfy security baseline**

Anonymous blocked; authenticated own topic works; unrelated topics denied; install bearer still required for register.

---

### Task 12: Real Android end-to-end/background canary

**Precondition:** Task 10 Firebase Android config is in the APK, Task 11 FCM provider is ready, and a real Android device can install the exact CI-verified build.

- [ ] **Step 1: Provision one Android canary**

Verify ntfy provisioning first; gateway store then has `platform:'android'` and a push target. Do not print raw target.

- [ ] **Step 2: Trigger internal wake**

Expected chain:

```text
/internal/wake -> FCM -> FirebaseMessagingService -> WorkManager -> authenticated ntfy reconciliation
```

Require FCM `targeted:1`, `sent:1`; APNs remains independently unready.

- [ ] **Step 3: Verify actual state reconciliation**

Create a harmless authorized sync change and confirm Android reaches ntfy-authoritative state after wake. Push receipt alone is insufficient.

- [ ] **Step 4: Run reliability matrix**

```text
screen locked
long background
Doze/device idle
process killed by system
network offline -> online
duplicate wake
```

Expected: no duplicate domain events, no direct FCM domain mutation, eventual reconciliation.

- [ ] **Step 5: Verify invalid-target cleanup**

On a real provider invalid-target response, only push target is cleared/disabled; install identity, subscriptions, ntfy ACL, pairing remain.

- [ ] **Step 6: Record final exact SHA and evidence**

Android/FCM phase is complete only after exact shipped SHA CI is `completed/success` and the real-device matrix passes. Apple-blocked iOS physical/TestFlight items remain open.
