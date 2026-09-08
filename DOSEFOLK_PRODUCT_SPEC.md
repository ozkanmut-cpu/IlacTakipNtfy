# Dosefolk product direction

## Product promise
Dosefolk should require less attention than competing medication apps. The default experience is silent. When action is needed, the user gets one clear next action. Complexity lives behind the scenes.

Core UX law:
- Normal state: zero interaction.
- Dose time: one interaction whenever possible.
- Exceptional state: minimum necessary interaction.
- Technical reliability: checked silently in the background.
- Medication, dose and schedule changes: never inferred silently.

## Primary navigation
Only three primary destinations:
1. Today — next best action, not a dashboard.
2. Circle — shared care coordination, problems first.
3. Assistant — natural-language control by speech or text.

Medication management, history, stock, devices, diagnostics, permissions, reports and backups are secondary surfaces and should not occupy permanent primary navigation.

## Differentiators

### 1. Care Baton
A dose can have a temporary operational owner. A caregiver taps “I’m handling this”; escalation to everyone else pauses. Ownership expires automatically when the dose is resolved or the claim times out. A caregiver can explicitly hand the baton to another Circle member.

Why it matters: family medication management is collaborative, but responsibilities often shift between primary and secondary caregivers. Dosefolk models the handoff instead of merely broadcasting alerts.

### 2. Quiet Success / Attention Budget
Success should be silent. Dosefolk maintains an internal notification budget and avoids sending informational noise to caregivers when everything is on track. Escalation is reserved for unresolved events.

Goal metric: minimize total weekly attention minutes, not maximize engagement.

### 3. Adaptive Operational Escalation
Escalation can adapt to operational behavior such as response time and who usually handles a particular time of day. It may change who is notified first or suppress redundant retries. It must never change medication, dose, schedule or clinical instructions.

### 4. Dosefolk Check
The app continuously verifies its own reliability: notification permission, precise alarm capability, next scheduled alarm, boot/timezone recovery, pending offline events and sync freshness. No diagnostic UI is shown when healthy. A single actionable repair card appears only when something is wrong.

### 5. Dose Confidence Graph
Internally distinguish states such as user-confirmed taken, caregiver-recorded taken, unresolved, offline-queued, conflicting and superseded. Never force photo or barcode proof. Confidence is used for conflict resolution, audit and reliability diagnostics rather than burdening the user.

### 6. Handover Digest
When responsibility changes between caregivers, show only what the incoming caregiver needs: unresolved doses, recent program changes, low stock and active temporary exceptions. Avoid a long generic activity feed.

### 7. Regimen Drift Detector
Detect operational drift without changing the prescription: timezone change, device clock jump, removed alarm permission, program edits that leave stale alarms, duplicate time groups, or an offline device that has not acknowledged revisions. Surface the problem, not a technical log.

### 8. Routine Anchors
Allow labels such as “after breakfast”, “before leaving home” or “bedtime” as human context while keeping the actual scheduled time explicit and deterministic. Reminder wording can use the anchor. The app must not infer a new clinical time from routine behavior.

### 9. Ambiguity Resolver
If two devices/caregivers submit conflicting states for the same dose, keep both events and show one small resolution card rather than silently overwriting history. Revision/event IDs make resolution auditable.

### 10. One-tap New Box
Stock is calculated from dose events. After package size is known once, “New box” reuses the previous quantity by default. User can correct it, but is not asked to count tablets repeatedly.

### 11. Smart Import With Missing-Only Questions
Photo/PDF/CSV/XLSX import creates a draft. AI extracts only explicit facts and asks only for missing critical fields. It never guesses a medication schedule. One concise confirmation activates the draft.

### 12. Natural-language operations
The Assistant is not a chatbot. It maps language to structured app commands, performs authorization locally and executes deterministic operations. Examples: status query, snooze, stock update, temporary caregiver handoff, report generation, person permissions and explicitly requested schedule changes.

### 13. Temporary Care Window
A caregiver can be granted responsibility or editing rights for a defined period, such as a weekend. Access expires automatically. Useful for relatives, nurses, babysitters or temporary travel arrangements.

### 14. Travel Guard
Timezone changes trigger a review only when they could change medication timing. Dosefolk never silently alters clinically relevant timing. It explains what changed and offers the smallest possible decision.

### 15. Offline Trust Receipt
Local alarms and dose actions work without internet. Outgoing events are queued. The UI can indicate that a dose was recorded locally and later synchronized, without requiring the user to retry.

## AI architecture
Speech/text -> intent extraction -> structured ChatCommand -> local permission/safety validation -> deterministic action -> audit event -> concise response.

Never store an OpenAI API key in the APK. Use a secure server-side gateway. Send minimum context. Prefer store:false for API calls unless product requirements explicitly need retention.

AI may learn operational preferences but must never infer or silently alter medication name, dose, schedule, maximum dose, minimum interval, start/end date or clinical instructions.

## Alarm reliability priorities
1. Exact local alarm where supported.
2. Graceful fallback when exact alarm permission is unavailable.
3. Reschedule after reboot, timezone/time changes and permission restoration.
4. Snooze is relative to now, never to the original scheduled time.
5. Group medications by time into one alarm/session.
6. Local event persistence before network delivery.
7. Offline outgoing queue and deduplication.
8. Inbound revision-aware sync.
9. Escalation after unresolved local reminders.
10. Reliability self-test and visible repair only on failure.

## Features deliberately excluded from routine flow
- Per-dose photo proof.
- Per-dose barcode verification.
- Mandatory missed-dose questionnaires.
- Streaks, badges or gamification as a primary mechanism.
- Repeated confirmation dialogs for normal actions.
- Large health-journal dashboards unrelated to medication coordination.

## Success metrics
- Median taps to resolve a scheduled time group: <= 1.
- Percentage of healthy days with no caregiver notification: maximize.
- Unresolved dose rate after escalation: minimize.
- False/redundant escalation rate: minimize.
- Alarm self-check pass rate: maximize.
- Median time to add a simple medication: minimize.
- Weekly user attention minutes: minimize.
- Percentage of AI requests completed without opening a form: maximize.
