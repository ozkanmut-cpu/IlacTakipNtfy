#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
PROJECT="$ROOT/ios/Dosefolk/project.yml"
ENTITLEMENTS="$ROOT/ios/Dosefolk/Dosefolk/Dosefolk.entitlements"
PRIVACY="$ROOT/ios/Dosefolk/Dosefolk/PrivacyInfo.xcprivacy"
RUNBOOK="$ROOT/docs/APPLE_RELEASE_SETUP.md"
GATEWAY_IGNORE="$ROOT/server/push-gateway/.gitignore"

fail() {
  printf 'APPLE_RELEASE_PREFLIGHT=FAIL reason=%s\n' "$1" >&2
  exit 1
}

require_file() {
  [[ -f "$1" ]] || fail "missing:$1"
}

require_text() {
  local file="$1"
  local needle="$2"
  grep -Fq -- "$needle" "$file" || fail "missing-metadata:${needle}"
}

for file in "$PROJECT" "$ENTITLEMENTS" "$PRIVACY" "$RUNBOOK" "$GATEWAY_IGNORE"; do
  require_file "$file"
done

# Canonical release identity and repository-side signing scaffold.
require_text "$PROJECT" 'PRODUCT_BUNDLE_IDENTIFIER: com.ozkanmut.dosefolk'
require_text "$PROJECT" 'deploymentTarget: "17.0"'
require_text "$PROJECT" 'MARKETING_VERSION: "1.0.0"'
require_text "$PROJECT" 'CURRENT_PROJECT_VERSION: "1"'
require_text "$PROJECT" 'CODE_SIGN_STYLE: Automatic'
require_text "$PROJECT" 'path: Dosefolk/Dosefolk.entitlements'
require_text "$PROJECT" 'aps-environment: development'
require_text "$PROJECT" 'CFBundleURLName: com.ozkanmut.dosefolk.enrollment'
require_text "$PROJECT" '- dosefolk'
require_text "$PROJECT" 'com.ozkanmut.dosefolk.refresh'
require_text "$PROJECT" '- fetch'
require_text "$PROJECT" '- remote-notification'
require_text "$PROJECT" 'ITSAppUsesNonExemptEncryption: false'

# Entitlements and privacy manifest must remain syntactically valid.
/usr/bin/plutil -lint "$ENTITLEMENTS" >/dev/null
/usr/bin/plutil -lint "$PRIVACY" >/dev/null
APS_ENV="$(/usr/libexec/PlistBuddy -c 'Print :aps-environment' "$ENTITLEMENTS")"
case "$APS_ENV" in
  development|production) ;;
  *) fail "invalid-aps-environment" ;;
esac
require_text "$PRIVACY" 'NSPrivacyAccessedAPICategoryUserDefaults'
require_text "$PRIVACY" 'CA92.1'

# Release runbook must document the external gates that CI cannot prove.
require_text "$RUNBOOK" 'com.ozkanmut.dosefolk'
require_text "$RUNBOOK" 'APNs authentication key (`.p8`)'
require_text "$RUNBOOK" 'Upload the first signed archive to TestFlight.'
require_text "$RUNBOOK" 'at least two physical devices'
require_text "$RUNBOOK" 'must never be committed to this public repository'

# Gateway secret material must stay ignored and outside version control.
require_text "$GATEWAY_IGNORE" 'secrets/'
require_text "$GATEWAY_IGNORE" '.env'
require_text "$GATEWAY_IGNORE" '*.p8'

for pattern in '*.p8' '*.p12' '*.mobileprovision' '*.cer' '*.key' '*.pem'; do
  if git -C "$ROOT" ls-files "$pattern" | grep -q .; then
    fail "tracked-sensitive-artifact:${pattern}"
  fi
done

if git -C "$ROOT" ls-files | grep -Eq '(^|/)\.env$'; then
  fail 'tracked-sensitive-artifact:.env'
fi

if git -C "$ROOT" ls-files 'server/push-gateway/secrets/**' | grep -q .; then
  fail 'tracked-gateway-secret-directory'
fi

PRIVATE_KEY_MARKER='-----BEGIN PRIVATE'' KEY-----'
if git -C "$ROOT" grep -Il -- "$PRIVATE_KEY_MARKER" -- . >/dev/null 2>&1; then
  fail 'tracked-private-key-material'
fi

printf 'APPLE_RELEASE_PREFLIGHT=PASS\n'
printf 'APPLE_ACCOUNT_CREDENTIALS=NOT_CHECKED\n'
printf 'APPLE_PHYSICAL_DEVICE_QA=NOT_CHECKED\n'
