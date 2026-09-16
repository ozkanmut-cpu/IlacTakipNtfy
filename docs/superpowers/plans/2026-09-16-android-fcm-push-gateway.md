# Android FCM + Multi-Provider Push Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Android FCM wake support to Dosefolk while keeping ntfy as the authenticated source of truth, preserving legacy iOS/APNs registrations, and allowing Android push to work when APNs is unavailable.

**Architecture:** Keep one push-gateway process. Add an `FCMClient` provider adapter and a provider-independent `PushDispatcher`; keep topic selection, provisioning, ntfy ACLs, and install authentication shared. Android receives wake-only FCM messages, schedules existing WorkManager reconciliation, and never mutates domain state directly from push payloads.

**Tech Stack:** Node.js 22, `firebase-admin` 14.4.0, Android/Kotlin, Firebase Android BoM 34.19.0, Firebase Cloud Messaging, WorkManager 2.10.0, JUnit/Robolectric, Node test runner, Docker, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-16-android-fcm-push-gateway-design.md`

## Global Constraints

- Work only on branch `ios-milestone-1`; do not modify or merge `main`.
- ntfy remains the synchronization source of truth; APNs/FCM are wake-only transports.
- Push payloads must not contain medication names, doses, patient/person names, schedule contents, event bodies, ntfy credentials, gateway credentials, topic secrets, or other health/application content.
- APNs and FCM readiness are independent.
- Legacy registration requests without `platform` and legacy stored installs without `platform` mean iOS/APNs.
- Legacy `deviceToken` remains readable; new writes use `pushToken`.
- Firebase service-account JSON is never committed; production uses a read-only VDS secret referenced by `GOOGLE_APPLICATION_CREDENTIALS`.
- Exact implementation SHA must have completed/successful required CI before being called GREEN.
- No Apple Developer account, APNs credential acquisition, iPhone validation, TestFlight, or App Store work is part of this plan.

---

## File Structure

### Gateway files

- Create `server/push-gateway/src/fcm.mjs`: FCM readiness, wake send, error classification, no application-data payload.
- Create `server/push-gateway/src/push-dispatcher.mjs`: normalize legacy/new install records, select provider, dispatch wakes independently, return structured outcomes.
- Create `server/push-gateway/src/push-registration.mjs`: platform/token normalization and validation for `/v1/register`.
- Modify `server/push-gateway/src/server.mjs`: health contract, provider-local registration readiness, mixed-provider wake routing, stale-target cleanup.
- Modify `server/push-gateway/package.json` and create/update `server/push-gateway/package-lock.json`: add `firebase-admin@14.4.0` and include new files in syntax checks.
- Modify `server/push-gateway/Dockerfile`: install production Node dependencies before runtime syntax checks.
- Modify `server/push-gateway/docker-compose.yml`: expose `GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json`; reuse existing read-only secrets mount.
- Modify `server/push-gateway/.gitignore`: ignore common Firebase service-account credential filenames in addition to the existing `secrets/` rule.
- Create `server/push-gateway/test/fcm.test.mjs`, `push-dispatcher.test.mjs`, and `push-registration.test.mjs`.
- Modify `server/push-gateway/test/provisioning.test.mjs`: regression coverage for legacy iOS registration plus Android provider-local readiness and mixed wake response.

### Android files

- Create `app/src/main/java/com/ozkanmut/ilactakip/FcmRegistration.kt`: registration policy, WorkManager scheduling, current-token retrieval, authenticated gateway registration, freshness metadata.
- Create `app/src/main/java/com/ozkanmut/ilactakip/DosefolkFirebaseMessagingService.kt`: `onNewToken`, `onMessageReceived`, `onDeletedMessages`; enqueue work only.
- Modify `app/src/main/java/com/ozkanmut/ilactakip/SyncWorker.kt`: expose a dedicated full-reconciliation kick name used by FCM deletion recovery while preserving idempotency.
- Modify `app/src/main/java/com/ozkanmut/ilactakip/NtfyProvisioning.kt`: schedule FCM registration after successful provisioning without coupling provisioning success to Firebase availability.
- Modify `app/src/main/java/com/ozkanmut/ilactakip/MainActivity.kt`: best-effort startup FCM registration refresh.
- Modify `app/src/main/AndroidManifest.xml`: register the FCM messaging service for `com.google.firebase.MESSAGING_EVENT`.
- Modify `app/build.gradle.kts`: add Firebase Android BoM 34.19.0 and `firebase-messaging`; do not require `google-services.json` until the real Firebase project is connected.
- Create `app/src/test/java/com/ozkanmut/ilactakip/FcmRegistrationPolicyTest.kt` and `FcmWakeRoutingTest.kt`.

### CI/release files

- Modify `.github/workflows/build-apk.yml`: run gateway syntax checks before tests and keep gateway tests + Android unit tests + APK build as the required implementation verification path.
- Production VDS changes are performed only after deterministic code/CI is GREEN and a Firebase project/service-account credential exists.

---

### Task 1: Provider-independent install normalization and registration contract

**Files:**
- Create: `server/push-gateway/src/push-registration.mjs`
- Create: `server/push-gateway/test/push-registration.test.mjs`

**Interfaces:**
- Produces: `normalizePlatform(value) -> 'ios' | 'android' | null`
- Produces: `readPushToken(body) -> string`
- Produces: `validatePushRegistration(body) -> { ok, platform, pushToken, provider, error }`
- Produces: `readStoredPushTarget(install) -> { platform, provider, pushToken, environment } | null`
- Legacy rule: absent request/stored `platform` means `ios`; stored `pushToken` takes precedence over `deviceToken`.

- [ ] **Step 1: Write failing contract tests**

```js
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  validatePushRegistration,
  readStoredPushTarget
} from '../src/push-registration.mjs';

test('legacy iOS request accepts deviceToken and defaults platform to ios', () => {
  const token = 'a'.repeat(64);
  const result = validatePushRegistration({ deviceToken: token, environment: 'sandbox' });
  assert.equal(result.ok, true);
  assert.equal(result.platform, 'ios');
  assert.equal(result.provider, 'apns');
  assert.equal(result.pushToken, token);
});

test('android accepts opaque bounded FCM token and rejects blank token', () => {
  const good = validatePushRegistration({ platform: 'android', pushToken: 'fcm-token:abc_123-XYZ' });
  assert.equal(good.ok, true);
  assert.equal(good.provider, 'fcm');
  assert.equal(validatePushRegistration({ platform: 'android', pushToken: '   ' }).ok, false);
});

test('legacy stored install reads deviceToken as ios target', () => {
  const target = readStoredPushTarget({ deviceToken: 'b'.repeat(64), environment: 'production' });
  assert.deepEqual(target, {
    platform: 'ios', provider: 'apns', pushToken: 'b'.repeat(64), environment: 'production'
  });
});
```

Android FCM token validation rule: trim, non-empty, maximum 4096 UTF-16 code units. iOS keeps the existing `/^[0-9a-f]{64,256}$/i` rule.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `cd server/push-gateway && node --test test/push-registration.test.mjs`

Expected: FAIL because `src/push-registration.mjs` does not exist.

- [ ] **Step 3: Implement the minimal normalization/validation module**

Implement exact legacy behavior above and return `error:'unsupported_platform'` or `error:'invalid_push_token'` for invalid inputs. Keep the module pure; it must not read environment variables or files.

- [ ] **Step 4: Run focused and full gateway tests**

Run:

```bash
cd server/push-gateway
node --test test/push-registration.test.mjs
npm test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/push-gateway/src/push-registration.mjs server/push-gateway/test/push-registration.test.mjs
git commit -m "Add multi-platform push registration contract"
```

---

### Task 2: FCM provider adapter with fail-closed readiness

**Files:**
- Create: `server/push-gateway/src/fcm.mjs`
- Create: `server/push-gateway/test/fcm.test.mjs`
- Modify: `server/push-gateway/package.json`
- Create/update: `server/push-gateway/package-lock.json`
- Modify: `server/push-gateway/Dockerfile`

**Interfaces:**
- Produces: `FCMClient.ready() -> Promise<boolean>`
- Produces: `FCMClient.wake(pushToken) -> Promise<{ messageId: string }>`
- Produces: `classifyFcmError(error) -> 'invalid_target' | 'transient' | 'permanent'`
- FCM payload is data-only and exactly limited to `wakeType:'sync'` and `protocolVersion:'1'`.

- [ ] **Step 1: Write failing FCM adapter tests**

Test injected fakes so CI requires no Google credential:

```js
test('ready is false when credential path is absent', async () => {
  const client = new FCMClient({ credentialPath: '' });
  assert.equal(await client.ready(), false);
});

test('wake sends only the minimal wake contract', async () => {
  let message;
  const client = new FCMClient({
    credentialPath: '/fake/service-account.json',
    credentialProbe: async () => true,
    sender: async value => { message = value; return 'message-1'; }
  });
  await client.wake('token-1');
  assert.deepEqual(message, {
    token: 'token-1',
    data: { wakeType: 'sync', protocolVersion: '1' },
    android: { priority: 'normal' }
  });
});
```

Also test Firebase Messaging error codes for unregistered token and retryable quota/server errors.

- [ ] **Step 2: Run focused test and verify RED**

Run: `cd server/push-gateway && node --test test/fcm.test.mjs`

Expected: FAIL because `src/fcm.mjs` does not exist.

- [ ] **Step 3: Add Firebase Admin dependency and install path**

Set exact dependency:

```json
"dependencies": {
  "firebase-admin": "14.4.0"
}
```

Generate lock file with Node 22/npm and change Dockerfile from copying source directly to installing production dependencies before runtime:

```dockerfile
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
```

Keep the existing ntfy CLI stage and Node 22 base.

- [ ] **Step 4: Implement `FCMClient`**

Use Firebase Admin modular imports. Default production initialization reads Application Default Credentials through `GOOGLE_APPLICATION_CREDENTIALS`; injected `credentialProbe`/`sender` keep tests deterministic. Missing/unreadable/malformed credential configuration returns `ready:false` rather than throwing during server startup. Never log token or credential contents.

- [ ] **Step 5: Run focused/full tests and container build**

Run:

```bash
cd server/push-gateway
node --test test/fcm.test.mjs
npm test
docker build --tag dosefolk-push-gateway:fcm-adapter .
docker run --rm --entrypoint ntfy dosefolk-push-gateway:fcm-adapter --version
```

Expected: all tests PASS; image builds; ntfy remains version 2.28.0.

- [ ] **Step 6: Commit**

```bash
git add server/push-gateway/package.json server/push-gateway/package-lock.json server/push-gateway/Dockerfile server/push-gateway/src/fcm.mjs server/push-gateway/test/fcm.test.mjs
git commit -m "Add fail-closed FCM provider adapter"
```

---

### Task 3: Push dispatcher and independent provider outcomes

**Files:**
- Create: `server/push-gateway/src/push-dispatcher.mjs`
- Create: `server/push-gateway/test/push-dispatcher.test.mjs`

**Interfaces:**
- Consumes: `readStoredPushTarget(install)` from Task 1.
- Consumes: `apns.ready()/wake()` and `fcm.ready()/wake()`.
- Produces: `PushDispatcher.readiness() -> Promise<{ apns:boolean, fcm:boolean }>`
- Produces: `PushDispatcher.dispatch(install) -> Promise<{ provider, status }>` where status is `sent | unavailable | invalid_target | transient_error | permanent_error | missing_target`.

- [ ] **Step 1: Write failing dispatcher tests**

Cover:

```js
test('legacy install routes to APNs', async () => { /* deviceToken, no platform */ });
test('android install routes to FCM', async () => { /* platform android, pushToken */ });
test('FCM failure does not prevent APNs dispatch in independent calls', async () => { /* fake providers */ });
test('provider readiness is independent', async () => { /* apns false, fcm true */ });
```

Assert that dispatcher never includes push tokens in returned result objects.

- [ ] **Step 2: Run and verify RED**

Run: `cd server/push-gateway && node --test test/push-dispatcher.test.mjs`

Expected: FAIL because dispatcher is absent.

- [ ] **Step 3: Implement dispatcher**

Keep retry policy provider-local and bounded: initial attempt plus at most two transient retries, exponential delays with jitter supplied through an injectable sleeper/random source for deterministic unit tests. Permanent invalid-target failures are never retried.

- [ ] **Step 4: Run gateway suite**

Run: `cd server/push-gateway && npm test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/push-gateway/src/push-dispatcher.mjs server/push-gateway/test/push-dispatcher.test.mjs
git commit -m "Route push wakes through independent providers"
```

---

### Task 4: Health, registration, mixed wake routing, and stale-target cleanup

**Files:**
- Modify: `server/push-gateway/src/server.mjs`
- Modify: `server/push-gateway/test/provisioning.test.mjs`
- Modify: `server/push-gateway/src/wake-targets.mjs`
- Modify: `server/push-gateway/test/wake-targets.test.mjs`

**Interfaces:**
- Health response exposes `ok`, `ready`, `provisioningReady`, `providers.apns.ready`, `providers.fcm.ready`.
- `/v1/register` chooses provider from validated platform and gates only that provider.
- `/internal/wake` returns aggregate plus per-provider counts.
- `invalid_target` removes only push-target fields from the stored install record; subscriptions/install identity remain.

- [ ] **Step 1: Extend tests first**

Add explicit RED cases for:

```js
// health: provisioning true + APNs false + FCM true => ready true
// legacy iOS register still accepts deviceToken
// android register persists { platform:'android', pushToken:'...' }
// android register when FCM false => 503 push_provider_unavailable/fcm
// iOS register when APNs false => 503 push_provider_unavailable/apns
// internal wake with one ios + one android returns separate provider counts
// invalid FCM target clears pushToken but keeps install record + subscriptions
```

Update wake target selection to skip installs with no readable push target so stale records are not repeatedly dispatched.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cd server/push-gateway
node --test test/provisioning.test.mjs test/wake-targets.test.mjs
```

Expected: failures against current global APNs readiness gate and APNs-only register/wake behavior.

- [ ] **Step 3: Refactor `server.mjs` minimally**

Instantiate both providers and `PushDispatcher`. Replace the current `isReady()` APNs-only behavior with independent readiness. Remove the global provider gate before all routes; provisioning endpoints remain governed by provisioning readiness, register is provider-local, and internal wake dispatches every selected target independently.

New writes use:

```js
store.installs[installId] = {
  platform,
  pushToken,
  ...(platform === 'ios' ? { environment, bundleId: cfg.bundleId } : {}),
  subscriptions,
  updatedAt: Date.now()
};
```

- [ ] **Step 4: Run all gateway tests and container build**

Run:

```bash
cd server/push-gateway
npm test
npm run check
docker build --tag dosefolk-push-gateway:multi-provider .
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/push-gateway/src/server.mjs server/push-gateway/src/wake-targets.mjs server/push-gateway/test/provisioning.test.mjs server/push-gateway/test/wake-targets.test.mjs
git commit -m "Make push gateway provider independent"
```

---

### Task 5: Gateway secret/config hardening for Firebase

**Files:**
- Modify: `server/push-gateway/docker-compose.yml`
- Modify: `server/push-gateway/.gitignore`
- Modify: `server/push-gateway/package.json`
- Modify: `.github/workflows/build-apk.yml`

**Interfaces:**
- Container receives `GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json`.
- Missing file keeps only FCM provider unready.
- CI runs syntax check, gateway tests, container build, Android tests, and APK build.

- [ ] **Step 1: Write/extend a gateway contract test for secret-path behavior**

In `test/fcm.test.mjs`, assert that default credential path handling never reads credential content into logs/returned objects and missing configured file means `ready:false`.

- [ ] **Step 2: Verify the contract test passes before config edits**

Run: `cd server/push-gateway && node --test test/fcm.test.mjs`

Expected: PASS; this protects runtime behavior before compose changes.

- [ ] **Step 3: Add production-safe config**

Add to gateway environment:

```yaml
GOOGLE_APPLICATION_CREDENTIALS: /run/secrets/firebase-service-account.json
```

The existing `./secrets:/run/secrets:ro` mount remains unchanged. Extend `.gitignore` with patterns such as `*service-account*.json` and `firebase-service-account.json`; retain `secrets/`, `.env`, and `*.p8`.

Update `npm run check` to syntax-check `fcm.mjs`, `push-dispatcher.mjs`, and `push-registration.mjs`. Add `npm run check` before `npm test` in Build APK CI.

- [ ] **Step 4: Run local deterministic verification**

Run:

```bash
cd server/push-gateway
npm run check
npm test
docker compose config >/tmp/dosefolk-compose-config.txt
```

Expected: PASS; compose resolves without requiring the credential file to exist on the developer machine.

- [ ] **Step 5: Commit**

```bash
git add server/push-gateway/docker-compose.yml server/push-gateway/.gitignore server/push-gateway/package.json .github/workflows/build-apk.yml
git commit -m "Harden Firebase push gateway configuration"
```

---

### Task 6: Android FCM registration policy and authenticated gateway client

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/FcmRegistration.kt`
- Create: `app/src/test/java/com/ozkanmut/ilactakip/FcmRegistrationPolicyTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `FcmRegistrationPolicy.shouldRegister(isProvisioned, hasInstallId, hasGatewayCredential, tokenPresent, tokenHashChanged, stale) -> Boolean`
- Produces: `FcmRegistrationScheduler.ensure(context)` and `refresh(context)`.
- Worker registers to `https://ntfy.field-maintenance-prod.com/dosefolk-push/v1/register` with gateway bearer credential and `{installId, platform:'android', pushToken, localTopic, subscriptions}`.
- Persist only token SHA-256 + successful registration timestamp; do not persist/log the raw FCM token in app-owned preferences.

- [ ] **Step 1: Add Firebase compile dependency and failing pure policy tests**

Use:

```kotlin
implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
implementation("com.google.firebase:firebase-messaging")
```

Do not apply the Google Services Gradle plugin yet; deterministic CI must remain independent of a real Firebase project.

Tests cover unprovisioned app, missing gateway credential, first token, unchanged fresh token, changed token, and stale registration.

- [ ] **Step 2: Run focused test and verify RED**

Run: `gradle testDebugUnitTest --tests '*FcmRegistrationPolicyTest'`

Expected: FAIL because `FcmRegistrationPolicy` is absent.

- [ ] **Step 3: Implement policy, metadata store, and WorkManager worker**

Use a unique work name `dosefolk-fcm-registration`, network-connected constraint, `ExistingWorkPolicy.REPLACE`, and bounded WorkManager backoff. In the worker obtain the current Firebase token on a background thread, then call the gateway only when ntfy provisioning/install/gateway credential prerequisites exist.

HTTP headers/body:

```text
Authorization: Bearer <gateway credential>
Content-Type: application/json
```

```json
{
  "installId": "...",
  "platform": "android",
  "pushToken": "...",
  "localTopic": "...",
  "subscriptions": []
}
```

Treat HTTP 204 as success. For 409 reprovision responses, set the existing Ntfy reprovision-required flag using the same policy as current gateway access refresh. Never print the raw token or Authorization value.

- [ ] **Step 4: Run Android unit suite**

Run: `gradle testDebugUnitTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/ozkanmut/ilactakip/FcmRegistration.kt app/src/test/java/com/ozkanmut/ilactakip/FcmRegistrationPolicyTest.kt
git commit -m "Add authenticated Android FCM registration"
```

---

### Task 7: FirebaseMessagingService wake-to-WorkManager routing

**Files:**
- Create: `app/src/main/java/com/ozkanmut/ilactakip/DosefolkFirebaseMessagingService.kt`
- Create: `app/src/test/java/com/ozkanmut/ilactakip/FcmWakeRoutingTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/SyncWorker.kt`

**Interfaces:**
- Produces pure helper `FcmWakeRouter.action(data, deletedMessages) -> REGISTER_TOKEN | KICK_SYNC | FULL_RECONCILIATION | IGNORE` for testability.
- `onNewToken()` schedules FCM registration; it does not perform network I/O directly.
- Valid wake requires `wakeType=sync` and `protocolVersion=1`.
- `onDeletedMessages()` enqueues full reconciliation.

- [ ] **Step 1: Write failing routing tests**

Cover valid wake, wrong protocol, arbitrary data payload, token-refresh action, and deleted-message full reconciliation. Assert no medication/event fields are consumed by the router.

- [ ] **Step 2: Run focused test and verify RED**

Run: `gradle testDebugUnitTest --tests '*FcmWakeRoutingTest'`

Expected: FAIL because router/service is absent.

- [ ] **Step 3: Implement service and manifest registration**

Manifest entry:

```xml
<service
    android:name=".DosefolkFirebaseMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

For a valid wake call `DosefolkSyncScheduler.kick(applicationContext)`. For deleted messages call a dedicated `DosefolkSyncScheduler.fullReconciliation(applicationContext)` unique work entry that runs the same authoritative sync worker but uses `ExistingWorkPolicy.REPLACE` so a backlog-loss recovery cannot be suppressed by a pre-existing ordinary kick.

- [ ] **Step 4: Run Android tests and debug build**

Run:

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/ozkanmut/ilactakip/DosefolkFirebaseMessagingService.kt app/src/main/java/com/ozkanmut/ilactakip/SyncWorker.kt app/src/test/java/com/ozkanmut/ilactakip/FcmWakeRoutingTest.kt
git commit -m "Wake Android sync from FCM safely"
```

---

### Task 8: Registration lifecycle integration without coupling provisioning to Firebase

**Files:**
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/NtfyProvisioning.kt`
- Modify: `app/src/main/java/com/ozkanmut/ilactakip/MainActivity.kt`
- Extend: `app/src/test/java/com/ozkanmut/ilactakip/FcmRegistrationPolicyTest.kt`

**Interfaces:**
- Successful ntfy provisioning schedules `FcmRegistrationScheduler.refresh(context)`.
- App startup schedules `FcmRegistrationScheduler.ensure(context)` best-effort.
- Failure to initialize Firebase or obtain an FCM token never rolls back ntfy provisioning and never blocks app startup.

- [ ] **Step 1: Add failing lifecycle contract tests**

Add pure tests proving registration scheduling is allowed only when ntfy/install/gateway prerequisites are present and that Firebase-unavailable state returns a retry/no-op result rather than changing provisioning state.

- [ ] **Step 2: Run focused test and verify RED**

Run: `gradle testDebugUnitTest --tests '*FcmRegistrationPolicyTest'`

Expected: at least one new lifecycle assertion FAILS before integration helpers exist.

- [ ] **Step 3: Wire successful provisioning and startup**

After the existing successful provisioning block schedules ntfy live sync/current sync, also schedule FCM refresh. In `MainActivity.onCreate`, schedule `ensure(this)` after existing ntfy retry/pull calls. Do not await Firebase on the UI thread.

- [ ] **Step 4: Run Android suite and APK build**

Run:

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

Expected: PASS even without `google-services.json` because project-specific Firebase activation has not been enabled yet.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ozkanmut/ilactakip/NtfyProvisioning.kt app/src/main/java/com/ozkanmut/ilactakip/MainActivity.kt app/src/test/java/com/ozkanmut/ilactakip/FcmRegistrationPolicyTest.kt
git commit -m "Integrate FCM registration lifecycle"
```

---

### Task 9: Exact-SHA deterministic CI gate

**Files:**
- Modify if needed: `.github/workflows/build-apk.yml`
- No production deployment in this task.

**Interfaces:**
- Exact branch SHA must complete `npm run check`, gateway tests, gateway container build, Android unit tests, and debug APK build.

- [ ] **Step 1: Push the current branch commits and identify exact HEAD SHA**

Run/read branch state and record the full SHA.

- [ ] **Step 2: Verify Build APK workflow includes every deterministic gate**

Required order includes:

```text
npm run check
npm test
docker build
ntfy version check
gradle testDebugUnitTest
gradle assembleDebug
```

If the workflow already matches after Task 5, make no cosmetic change.

- [ ] **Step 3: Wait for the workflow associated with the exact HEAD to reach `completed/success`**

Do not treat queued or in-progress as GREEN. On failure, inspect the first failing job/step/log and fix the root cause using systematic debugging, then verify the new exact SHA.

- [ ] **Step 4: Commit only if CI workflow needed a functional correction**

If no change was necessary, do not create an empty commit.

---

### Task 10: Connect the real Firebase Android project without adding secrets

**Files:**
- Create when available: `app/google-services.json`
- Modify when available: root/app Gradle plugin configuration required by the downloaded Firebase config.
- Add/extend tests only if activation changes deterministic behavior.

**Precondition:** A Firebase project exists with Android app package `com.ozkanmut.ilactakip`, and its `google-services.json` has been downloaded. This file contains project identifiers/configuration, not the server service-account private key; inspect it before commit to confirm no private key material exists.

- [ ] **Step 1: Inspect the received `google-services.json`**

Confirm `package_name` exactly equals `com.ozkanmut.ilactakip`. Reject any file containing `private_key`, `private_key_id`, or service-account credential material.

- [ ] **Step 2: Add Google Services Gradle plugin and config**

Apply the current supported Google Services plugin only after the real config exists; do not manufacture placeholder Firebase identifiers.

- [ ] **Step 3: Run deterministic Android verification**

Run:

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

Expected: PASS with Firebase resource generation active.

- [ ] **Step 4: Commit non-secret Firebase Android config**

```bash
git add app/google-services.json build.gradle.kts app/build.gradle.kts
git commit -m "Connect Dosefolk Android to Firebase"
```

- [ ] **Step 5: Verify exact commit CI completed/success**

Use the same exact-SHA rule as Task 9.

---

### Task 11: Production gateway FCM credential canary

**Files/Host:**
- VDS project only: `/opt/dosefolk-push-gateway`
- Secret target: `/opt/dosefolk-push-gateway/secrets/firebase-service-account.json` (container path `/run/secrets/firebase-service-account.json`)
- Do not touch `/opt/field-maintenance/app`.

**Precondition:** A Firebase service-account JSON credential for the Dosefolk Firebase project is available. The private key must never be pasted into chat, GitHub, shell history, logs, or tracked files.

- [ ] **Step 1: Read-only production baseline**

Capture current gateway `/health`, container state, registrations count, and existing APNs `ready:false` state. Confirm production source matches the intended branch release artifact before changing anything.

- [ ] **Step 2: Back up only files that will be changed**

Create timestamped backups inside the Dosefolk gateway project, not the field-maintenance project.

- [ ] **Step 3: Install the service-account secret with restrictive permissions**

Place the credential under the existing `secrets/` directory, readable by the container via the existing read-only mount. Do not print its content.

- [ ] **Step 4: Deploy the already-CI-GREEN gateway source/config**

Rebuild/restart only `dosefolk-push-gateway`; do not replace unrelated production compose additions wholesale.

- [ ] **Step 5: Verify independent readiness**

Expected `/health` shape:

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

If FCM is false, inspect configuration without exposing the credential and stop the canary; do not weaken readiness checks.

- [ ] **Step 6: Confirm baseline ntfy security remains intact**

Anonymous publish/subscribe remains blocked, authenticated own-topic access remains valid, unrelated topics remain denied, and registration still requires the install bearer credential.

---

### Task 12: Real Android end-to-end canary and background reliability

**Precondition:** Task 10 Firebase Android config is installed in the APK, Task 11 production FCM provider is ready, and a real Android device can install the CI-verified build.

- [ ] **Step 1: Provision one canary Android install through the existing enrollment flow**

Verify ntfy provisioning succeeds first, then confirm the gateway store contains one Android install with `platform:'android'` and a push target. Do not print the raw token.

- [ ] **Step 2: Trigger one internal wake for the canary subscription**

Expected chain:

```text
/internal/wake -> FCM sent -> FirebaseMessagingService -> WorkManager -> authenticated ntfy reconciliation
```

Confirm gateway response reports FCM `targeted:1` and `sent:1` and APNs remains unaffected/unready.

- [ ] **Step 3: Verify functional reconciliation, not just push receipt**

Create a harmless synchronization state change from the paired/authorized flow and confirm the Android device reaches the authoritative ntfy state after wake. A push receipt alone is insufficient evidence.

- [ ] **Step 4: Run reliability matrix**

Test individually:

```text
screen locked
app backgrounded for a long interval
Doze/device idle
app process killed by the system
network offline then restored
duplicate wake
```

Expected: no duplicate domain events, no direct FCM state mutation, and eventual ntfy reconciliation after network availability.

- [ ] **Step 5: Verify stale-token behavior on an invalidated canary target**

After producing an actual provider invalid-target response, confirm only push-target fields are cleared/disabled while install identity, ntfy ACL state, subscriptions, and pairing remain intact.

- [ ] **Step 6: Record final exact-SHA + production evidence**

The Android/FCM phase is complete only when deterministic CI is `completed/success` for the exact shipped SHA and the real-device canary/background matrix above passes. Apple-blocked iOS physical/TestFlight items remain open and are not reclassified as complete.
