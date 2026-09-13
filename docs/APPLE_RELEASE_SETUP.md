# Dosefolk Apple Release Setup

This checklist captures the remaining Apple-account-side work required before the first TestFlight build. It intentionally separates repository configuration from actions that require access to Apple Developer / App Store Connect.

## Identifiers already defined in the repository

- App name: `Dosefolk`
- Bundle identifier: `com.ozkanmut.dosefolk`
- Minimum iOS version: `17.0`
- Marketing version: `1.0.0`
- Build number: `1`
- Background task identifier: `com.ozkanmut.dosefolk.refresh`
- URL scheme: `dosefolk`
- Push entitlement: `aps-environment`
- Background modes: `fetch`, `remote-notification`

The iOS CI validates that the Release archive contains `CFBundleShortVersionString = 1.0.0` and `CFBundleVersion = 1`. Increment `CURRENT_PROJECT_VERSION` before each subsequent App Store Connect/TestFlight upload; keep `MARKETING_VERSION` aligned with the intended public release version.

## Apple Developer portal

1. Confirm the Apple Developer Program membership that will own Dosefolk.
2. Create or verify the explicit App ID `com.ozkanmut.dosefolk`.
3. Enable Push Notifications for that App ID.
4. Enable any Background Modes capability Apple requires for the final signed target; the Xcode project already declares `fetch` and `remote-notification`.
5. Create an APNs authentication key (`.p8`) or use an approved existing key with APNs access.
6. Record the APNs Key ID and Team ID in the secure deployment environment only. Never commit the `.p8` key, Key ID, Team ID secrets/configuration, certificates, or provisioning profiles to the public repository.

## Signing

1. Create or verify an Apple Distribution certificate for the owning team.
2. Create an App Store provisioning profile for `com.ozkanmut.dosefolk`, or use Xcode automatic signing with the correct team.
3. Confirm the signed app contains the push entitlement and expected background modes.
4. Archive with Release configuration and verify signing before upload.

## App Store Connect

1. Create the Dosefolk app record using bundle ID `com.ozkanmut.dosefolk`.
2. Configure the required app metadata and public Privacy Policy URL.
3. Use `docs/APP_STORE_PRIVACY_DISCLOSURE.md` as the working source for App Privacy answers, then recheck the production build and current Apple taxonomy before publishing the answers.
4. Reconcile the App Privacy answers with `docs/CIRCLE_DATA_DISCLOSURE.md` and `ios/Dosefolk/Dosefolk/PrivacyInfo.xcprivacy`.
5. Upload the first signed archive to TestFlight.

## APNs / Dosefolk backend

The existing iOS app registers an APNs device token and the Dosefolk push gateway uses APNs as the wake-up path while ntfy remains the protocol source of truth.

Before production TestFlight QA:

1. Install the APNs `.p8` key only in the secure server/runtime environment used by the push gateway.
2. Configure the matching Key ID, Team ID and bundle/topic `com.ozkanmut.dosefolk` in that secure environment.
3. Confirm development vs production APNs environment matches the signed build/provisioning profile.
4. Verify token registration succeeds from a real iPhone.
5. Verify a Circle event can cause the intended APNs wake-up and subsequent ntfy reconciliation.

## TestFlight readiness gate

Do not mark the first TestFlight build complete until all of the following are true:

- explicit App ID exists;
- Push Notifications are enabled for the App ID;
- distribution signing/provisioning is valid;
- APNs key is installed securely outside the repository;
- App Store Connect app record exists;
- privacy metadata has been reviewed against the production build;
- a Release archive signs successfully;
- the signed build uploads to TestFlight;
- at least two physical devices are available for final Android ↔ iPhone ↔ iPhone QA.

## Repository safety rule

Apple private keys, certificates, provisioning profiles, signing passwords, API keys and other production secrets must never be committed to this public repository. Only non-secret identifiers and setup documentation belong here.
