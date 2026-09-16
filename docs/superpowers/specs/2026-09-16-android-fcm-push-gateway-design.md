# Android FCM + Multi-Provider Push Gateway Design

Date: 2026-09-16
Branch: `ios-milestone-1`
Status: Approved design, pending implementation plan

## Goal

Add Android Firebase Cloud Messaging (FCM) wake support without requiring Apple hardware or an Apple Developer account, while preserving the existing ntfy-based synchronization model and all current iOS/APNs behavior.

The push layer is wake-only. ntfy remains the authenticated source of truth for synchronization. Push payloads must not contain medication names, doses, patient-identifying data, event contents, or other sensitive application state.

## Constraints

- Do not modify or merge `main` as part of this work.
- Existing APNs behavior and existing iOS registrations must remain backward compatible.
- APNs and FCM readiness must be independent.
- If APNs is unavailable but FCM is ready, Android registration and wake delivery must continue to work.
- If FCM is unavailable but APNs is ready, iOS registration and wake delivery must continue to work.
- ntfy authentication, topic binding, reprovisioning, and ACL rules remain authoritative and unchanged unless a change is explicitly required by this design.
- Provider credentials must never be committed to GitHub.
- Firebase service-account JSON must be stored only as a read-only VDS secret and referenced through `GOOGLE_APPLICATION_CREDENTIALS` inside the gateway container.

## Chosen Architecture

Use one push gateway process with provider adapters and a dispatcher.

### Components

1. `APNsClient`
   - Keep the existing APNs implementation and readiness semantics.
   - Expose provider-local `ready()` and `wake(...)` behavior.

2. `FCMClient`
   - New provider adapter backed by Firebase Admin SDK.
   - Expose `ready()` and `wake(token)` behavior.
   - Initialize from Application Default Credentials / `GOOGLE_APPLICATION_CREDENTIALS`.
   - Treat missing or malformed credentials as provider-not-ready rather than crashing the gateway.

3. `PushDispatcher`
   - New provider-independent routing layer.
   - Select provider from install record platform/provider metadata.
   - Route iOS targets to APNs and Android targets to FCM.
   - One provider failure must not prevent delivery attempts to the other provider.

4. Existing topic target selection
   - Keep topic/subscription filtering provider-agnostic.
   - Target selection identifies installs; dispatcher performs provider routing afterward.

## Readiness and Health Contract

`GET /health` must distinguish process health, provisioning readiness, and provider readiness.

Required semantics:

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

Rules:

- `ok:true` means the gateway process is alive.
- `provisioningReady` means gateway authentication/provisioning secrets are available.
- `providers.apns.ready` and `providers.fcm.ready` are independent.
- Top-level `ready` is true when provisioning is ready and at least one push provider is ready.
- A provider being unavailable must not globally disable a ready provider.

## Registration API

Keep one backward-compatible endpoint: `POST /v1/register`.

### Existing iOS compatibility

Legacy requests that omit `platform` continue to mean iOS/APNs.

Legacy stored install records that omit `platform` are also interpreted as iOS/APNs.

### New registration form

New iOS clients may send:

```json
{
  "installId": "...",
  "platform": "ios",
  "pushToken": "...",
  "environment": "production",
  "localTopic": "...",
  "subscriptions": []
}
```

Android clients send:

```json
{
  "installId": "...",
  "platform": "android",
  "pushToken": "...",
  "localTopic": "...",
  "subscriptions": []
}
```

### Validation

All existing install authentication, topic binding, ntfy ACL synchronization, and reprovisioning checks run before provider-specific registration succeeds.

Provider-specific validation:

- iOS uses the existing APNs token validation rules.
- Android accepts an FCM registration token as an opaque non-empty bounded string; do not apply the APNs hexadecimal token regex.
- Unsupported platform values are rejected with HTTP 400.
- If the requested provider is not ready, return HTTP 503 with:

```json
{
  "error": "push_provider_unavailable",
  "provider": "fcm"
}
```

or `provider:"apns"` as appropriate.

## Install Store Schema and Migration

New install records use a common representation:

```json
{
  "platform": "android",
  "pushToken": "...",
  "subscriptions": [],
  "updatedAt": 0
}
```

For iOS, `environment` and `bundleId` remain where applicable.

Backward compatibility rules:

- Read `pushToken` first.
- If `pushToken` is absent, accept existing `deviceToken`.
- Records without `platform` are treated as iOS.
- New writes use `pushToken` and explicit `platform`.
- No bulk production data migration is required.
- Re-registering an install atomically replaces its prior push target.
- One `installId` has one active push provider at a time.

## Wake Flow

`POST /internal/wake` remains the internal trigger.

Flow:

1. Authenticate internal request as today.
2. Select subscribed installs using existing topic logic.
3. Route each target through `PushDispatcher`.
4. Send Android targets through FCM and iOS targets through APNs.
5. Collect per-provider outcomes without allowing one provider's failure to abort the other provider's work.

Response must retain aggregate counts and add provider-level visibility, for example:

```json
{
  "targeted": 3,
  "sent": 2,
  "providers": {
    "apns": { "targeted": 1, "sent": 0 },
    "fcm": { "targeted": 2, "sent": 2 }
  }
}
```

Exact additional diagnostic fields may be added if they do not expose credentials or push tokens.

## FCM Message Contract

FCM is a wake signal, not a data transport.

Allowed data is intentionally minimal, such as:

```json
{
  "wakeType": "sync",
  "protocolVersion": "1"
}
```

Prohibited in push payloads:

- medication names
- doses
- patient/person names
- schedule contents
- dose-event bodies
- ntfy credentials
- gateway credentials
- topic secrets
- any other user health/application content

Android must fetch authoritative state through the existing authenticated ntfy synchronization path after waking.

## Android Client Flow

Add Firebase Messaging to the Android app and implement a `FirebaseMessagingService`.

### Token lifecycle

- On first available FCM token, register it through authenticated `/v1/register`.
- On `onNewToken`, re-register the new token.
- Persist only enough local metadata to determine whether gateway registration should be refreshed.
- Re-registration must be idempotent.

### Message handling

`onMessageReceived()` must not directly mutate domain state.

Required flow:

`FCM wake -> WorkManager -> authenticated ntfy reconciliation -> existing protocol/inbound guards -> state update`

If FCM signals message deletion/backlog loss, trigger a full reconciliation rather than attempting to reconstruct missing application events from push data.

Duplicate, delayed, or missing FCM wake signals must not affect correctness; the existing event and synchronization semantics remain authoritative.

## Error Handling and Stale Targets

Classify provider errors into permanent and transient categories.

### Permanent invalid target

When FCM reports an unregistered/invalid registration token:

- remove only the stored push target for that install, or mark it unavailable for re-registration;
- preserve install identity, ntfy credentials, pairing, ACL state, and user data;
- do not delete an install's broader synchronization state.

Equivalent APNs stale-token handling may be aligned later, but is not required to block the FCM rollout unless needed by shared dispatcher behavior.

### Transient provider failure

For throttling, provider 5xx responses, or network failures:

- use bounded exponential backoff with jitter;
- do not create unbounded retry queues;
- do not retry permanent invalid-token errors;
- do not let failures from one provider block the other provider.

## Security

- Continue using the existing gateway install bearer credential for `/v1/register`.
- Continue enforcing ntfy topic binding and ACL synchronization before accepting push registration.
- Do not expose service-account JSON, APNs private keys, push tokens, gateway secrets, or ntfy tokens in logs or API responses.
- Firebase service-account JSON is a read-only VDS secret outside the repository.
- Repository `.gitignore` / preflight protections must cover Firebase credential files and common service-account key naming patterns.
- FCM payloads contain no sensitive user/application state.

## Production Credential Model

The gateway container receives a read-only mounted Firebase service-account JSON file, for example under `/run/secrets/`.

`GOOGLE_APPLICATION_CREDENTIALS` points to that mounted file.

The JSON contents must not be stored in:

- GitHub
- tracked compose files
- committed environment files
- logs
- test fixtures

Production FCM readiness is false until a valid credential file is present.

## Testing Strategy

Implementation uses TDD.

Required gateway tests:

1. APNs ready / FCM not ready.
2. FCM ready / APNs not ready.
3. Neither provider ready.
4. Both providers ready.
5. Legacy iOS registration payload remains valid.
6. Legacy stored `deviceToken` remains readable.
7. Android registration succeeds with FCM ready.
8. Android registration fails provider-locally with FCM unavailable.
9. Unsupported platform is rejected.
10. Mixed Android+iOS wake routing.
11. FCM failure does not block APNs delivery.
12. APNs failure does not block FCM delivery.
13. Invalid/stale FCM token cleanup preserves non-push install state.
14. No secret/token material is returned or logged by tested paths.

Required Android tests:

1. Token refresh triggers authenticated registration.
2. Duplicate wake schedules safe/idempotent reconciliation.
3. FCM receive path uses WorkManager rather than direct domain mutation.
4. Offline-to-online reconciliation remains correct.
5. Process death / restart does not corrupt token-registration state.
6. Deleted-message/backlog-loss callback triggers full reconciliation.

## CI

Add a push/FCM CI gate or extend existing CI so changes to any of these areas run the relevant suites:

- Android Firebase/FCM integration
- push gateway provider code
- gateway registration routing
- wake dispatcher
- relevant protocol/reconciliation code

A change must not be called GREEN until the exact commit SHA has completed/successful required CI.

## Rollout

1. Complete provider abstraction and deterministic tests without production credentials.
2. Add Android Firebase Messaging client flow and tests.
3. Add Firebase project configuration without committing secrets.
4. Mount production service-account credential on the Dosefolk gateway VDS only.
5. Verify health shows FCM ready independently of APNs.
6. Register one Android canary install.
7. Run an internal wake and verify FCM wake -> ntfy reconciliation end to end.
8. Test locked screen, long background, Doze, process death, and network recovery on a real Android device.
9. Expand rollout only after canary behavior is verified.

## Explicit Non-Goals for This Phase

- No Apple Developer account setup.
- No APNs credential acquisition.
- No physical iPhone validation.
- No TestFlight/App Store work.
- No replacement of ntfy as synchronization source of truth.
- No sensitive application data transported through FCM.
- No creation of a separate Android push service/container unless later evidence shows the single-gateway adapter model is insufficient.

## Success Criteria

The phase is successful when:

- Android FCM registration and wake delivery work with APNs unavailable.
- iOS/APNs behavior remains backward compatible.
- provider readiness is independently observable.
- one provider failure does not globally disable push delivery.
- FCM wake causes safe authenticated ntfy reconciliation rather than carrying application state itself.
- existing security/reprovision/ACL guarantees remain intact.
- all deterministic gateway/Android tests and required CI are GREEN for the exact implementation SHA.
- final production canary is completed on a real Android device once Firebase credentials are available.
