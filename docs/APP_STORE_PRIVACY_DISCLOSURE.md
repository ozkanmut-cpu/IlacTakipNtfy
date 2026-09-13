# Dosefolk App Store Privacy Disclosure Draft

This document is a working source of truth for completing App Store Connect privacy questions for the iOS build. It must be rechecked against the production build, enabled features, backend retention behavior, and Apple's then-current App Store Connect taxonomy before submission.

## Apple submission requirements

For iOS App Store distribution, App Store Connect requires a Privacy Policy URL and requires the developer to describe the app's data-handling practices. Responses are provided at the app level and must include relevant practices of third-party partners integrated into the app.

## Current Dosefolk disclosure draft

Dosefolk Circle synchronizes medication-related data between installations through the self-hosted ntfy transport and supporting Dosefolk backend services. This means the App Store Connect answer should not use "No, we do not collect data from this app" for a production build with Circle enabled.

### Data types expected to be declared

The following Apple privacy categories are expected to apply based on the current protocol and feature set:

- **Health & Fitness / Health**: medication schedules, medication names/dose text, dose-event state such as Taken, Snoozed or Missed, medication program/rule information, and medication stock state when synchronized through Circle.
- **Identifiers / User ID**: Dosefolk installation/owner identifiers and Circle peer identifiers used to route and reconcile synchronized state. These are application-level identifiers, not advertising identifiers.
- **Other Data**: protocol and synchronization metadata such as event IDs, timestamps, revisions, synchronization state, publisher topics and targeted routing metadata when these values are retained server-side or otherwise meet Apple's definition of collection.

The exact App Store Connect selections should be validated against Apple's current category definitions immediately before submission, especially whether individual protocol-routing fields fit Apple's "User ID", "Device ID", or "Other Data" subtypes.

### Purpose

For the data above, the expected purpose is **App Functionality**. The data is used to provide user-requested medication scheduling, Circle pairing/synchronization, dose-state synchronization, program/rule synchronization, stock synchronization, and deterministic reconciliation across devices.

No current Dosefolk feature is intended to use this data for third-party advertising, developer advertising/marketing, advertising measurement, or cross-company tracking.

### Tracking

Dosefolk does not intentionally perform tracking as defined for advertising or cross-company profiling. The current iOS privacy manifest declares `NSPrivacyTracking` as false and does not declare tracking domains.

### Linked to the user

Medication and Circle synchronization data can be associated with a Dosefolk installation, owner, or Circle peer identity for the application to function. Unless the production architecture is changed so the relevant collected data cannot be linked to a user or account identity, the conservative App Store Connect position is to treat applicable synchronized data as **linked to the user**.

### Data not intentionally collected for App Store disclosure

The current application is not designed to collect the following through normal Dosefolk functionality:

- payment or banking information;
- precise or coarse location;
- contacts/address-book contents;
- browsing history;
- search history;
- advertising identifiers;
- photos, videos, microphone recordings, or other user media as part of Circle synchronization;
- passwords or Apple/Google account credentials.

QR camera access is used locally for Circle pairing. Camera frames are not intended to be uploaded or retained as collected user content.

### QA logs

Dosefolk QA logs are separate from normal Circle synchronization. The logger is designed to avoid medication names, dose text, notification bodies, raw protocol payloads, topics, tokens and secrets. Export occurs through an explicit user Share Sheet action rather than automatic analytics upload.

If production behavior later uploads QA logs automatically, the App Store Connect answers must be reassessed.

## Production checklist before publishing answers

Before publishing privacy responses in App Store Connect:

1. Verify the production iOS build and all enabled features.
2. Verify ntfy and Dosefolk backend retention/logging behavior.
3. Verify whether publisher topics, event IDs, IP addresses, push tokens, or other server logs are retained and for how long.
4. Review every bundled third-party SDK/package for data collection.
5. Confirm that no analytics, crash-reporting, advertising, or attribution SDK has been added without updating this document.
6. Reconcile this document with `docs/CIRCLE_DATA_DISCLOSURE.md` and `PrivacyInfo.xcprivacy`.
7. Supply a public Privacy Policy URL in App Store Connect and make the privacy policy accessible from within the app.
8. Recheck Apple's current App Store privacy taxonomy immediately before submission and update selections if definitions changed.

## Current expected App Store Connect posture

- Data collected: **Yes** for a production build with Circle enabled.
- Tracking: **No**.
- Primary purpose: **App Functionality**.
- Health/medication synchronization data: expected to be declared and treated conservatively as linked to the user.
- App-level Circle/installation identifiers: expected to be declared when they meet Apple's identifier definitions.
- No advertising or marketing use is intended.

This is a submission-preparation document, not a legal privacy policy and not a substitute for reviewing actual production behavior before App Store release.
