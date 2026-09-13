# iOS Required-Reason API Audit

Date: 2026-09-13
Branch audited: `ios-milestone-1`

This note records the current Dosefolk iOS required-reason API review for App Store preparation.

## Apple rule checked

Apple requires every required-reason API category used directly by app code to be declared in the app's `PrivacyInfo.xcprivacy` with an approved reason. Apple's current documentation lists categories including UserDefaults, file timestamps, system boot time, disk space and active keyboards.

## Current direct usage found

### UserDefaults

`AppSettings.swift` uses `UserDefaults.standard` for app-local settings such as display name, language, local topic, install ID and sync checkpoint values.

The privacy manifest already declares:

- `NSPrivacyAccessedAPICategoryUserDefaults`
- reason `CA92.1`

`CA92.1` is appropriate because Dosefolk reads and writes values accessible only to the app itself. No App Group-shared defaults are used in the audited code.

## File-system usage reviewed

`LocalStore.swift` reads/writes files in the app's Application Support directory using `FileManager`, `Data(contentsOf:)`, atomic writes and file existence checks.

`DosefolkQaLog.swift` writes/rotates the app's private QA log and reads file size through `FileManager.attributesOfItem` for local log rotation.

The audited code does not read file creation/modification/access timestamps, system free/total disk capacity, system boot time, or active keyboard lists. The current file-size check is used only for the app's own QA-log rotation and does not access file timestamps or system disk-space capacity APIs.

## Current manifest conclusion

At the time of this audit, the only required-reason API category identified in the reviewed Dosefolk iOS app code is `UserDefaults`, already declared with `CA92.1`.

No additional manifest category is being added by this audit.

## Recheck triggers

Repeat this audit before App Store submission and whenever code or SDKs add any of the following:

- file creation/modification timestamp access;
- available/total disk-space queries;
- `ProcessInfo.systemUptime` or `mach_absolute_time`;
- active keyboard inspection;
- App Group-shared `UserDefaults`;
- third-party SDKs that use required-reason APIs.

Apple can update the required-reason API list and approved reasons, so the production archive must be rechecked against the then-current Apple documentation before submission.
