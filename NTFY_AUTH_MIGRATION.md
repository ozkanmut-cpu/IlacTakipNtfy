# ntfy credential migration and rollback

This runbook is the production gate for removing anonymous ntfy access.

## Invariants

- Gateway credentials and native ntfy `tk_...` tokens are different credentials.
- Native tokens are stored only in iOS Keychain / Android Keystore and the ntfy auth database.
- Each install gets a deterministic hashed ntfy username; raw install IDs are not usernames.
- Own topic access is read-write; peer topics are read-only.
- Production secrets and token values must never be committed or logged.
- Do not enable `auth-default-access: deny-all` until credential coverage and authenticated publish/subscribe are verified on both platforms.

## Stage 0 — application readiness

Required before production auth changes:

- Server can provision a native ntfy token separately from the gateway credential.
- Secure provisioning persists the trusted local-topic binding.
- `/v1/access` synchronizes ACLs from that trusted binding.
- iOS and Android persist native ntfy credentials in platform secure storage.
- 409/reprovision state is persistent and cannot be silently downgraded.
- Legacy provisioning remains compatible during migration.

Rollback: application-only changes may be reverted without changing ntfy access policy.

## Stage 1 — auth database, permissive default

Production state during migration:

- Configure a persistent ntfy auth database with `auth-file`.
- Share only that auth database with the push gateway; never expose the Docker socket.
- Keep `auth-default-access: read-write`.
- Provision native tokens for upgraded clients and synchronize their topic ACLs.
- Keep legacy clients operational while credential coverage grows.

Verification:

1. ntfy and push gateway are healthy after restart.
2. The auth database persists across container recreation.
3. Legacy provisioning still succeeds.
4. Anonymous publish still succeeds in Stage 1; this is intentional compatibility, not the final security state.
5. A newly provisioned native token can authenticate to ntfy and publish to its own topic.
6. The same token can read allowed peer topics.
7. The token cannot gain permissions outside its synchronized ACL when tested under deny-by-default in an isolated/pre-cutover validation environment.

Rollback:

1. Keep a dated backup of the auth database and ntfy configuration before each change.
2. Restore the previous ntfy configuration if auth-file causes service instability.
3. Restart ntfy and verify legacy anonymous publish plus gateway health.
4. Preserve the auth database backup for investigation; do not delete client credentials during rollback.

## Stage 2 — credential coverage gate

Before deny-all, establish evidence that supported production clients no longer depend on anonymous ntfy access.

Minimum evidence:

- Current Android build provisions/loads a native token and uses it for ntfy requests.
- Current iOS build provisions/loads a native token and uses it for ntfy requests.
- Re-pair/revoke paths force ACL refresh where security requires it.
- 409 routes both platforms into explicit reprovision UX.
- Successful reprovision clears the persistent reprovision flag.
- Real authenticated publish and subscribe smoke tests pass without exposing token values in logs.

If any item is unknown or fails, remain at Stage 1.

## Stage 3 — minimum topic ACL cutover

For every migrated install:

- local topic: read-write
- active peer topics: read-only
- revoked/removed peer topics: no access
- no wildcard/global grants

Take an auth DB backup immediately before cutover and record the deployed application/server revisions.

## Stage 4 — deny anonymous access

Only after Stages 2 and 3 are verified:

1. Set `auth-default-access: deny-all`.
2. Restart ntfy in a controlled maintenance window.
3. Verify anonymous publish is rejected.
4. Verify authorized own-topic publish succeeds.
5. Verify authorized allowed-topic subscribe succeeds.
6. Verify unauthorized topic access is rejected.
7. Verify Android and iOS Circle event synchronization end-to-end.
8. Verify revoke/re-pair updates ACLs and does not restore stale access.

Do not call the migration complete until all checks pass.

## Emergency rollback after deny-all

If production clients lose required messaging access:

1. Restore `auth-default-access: read-write` first; this is the fastest compatibility rollback.
2. Restart ntfy and verify service health plus legacy publish.
3. If needed, restore the pre-cutover ntfy configuration and auth DB backup.
4. Keep gateway/native credential separation intact; never substitute the gateway HMAC as a native ntfy Bearer token.
5. Investigate credential coverage or ACL synchronization before attempting deny-all again.

## Completion evidence

Record, without secrets:

- application/server commit SHAs
- ntfy version
- auth DB backup timestamp
- Android CI result
- iOS CI result
- authenticated own-topic publish result
- authenticated allowed-topic subscribe result
- anonymous rejection result after deny-all
- unauthorized-topic rejection result
- Android ↔ iOS end-to-end result
- rollback drill/result or verified rollback commands
