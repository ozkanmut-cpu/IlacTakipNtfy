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

test('android accepts opaque bounded FCM token and rejects blank or oversized token', () => {
  const good = validatePushRegistration({ platform: 'android', pushToken: 'fcm-token:abc_123-XYZ' });
  assert.equal(good.ok, true);
  assert.equal(good.provider, 'fcm');
  assert.equal(validatePushRegistration({ platform: 'android', pushToken: '   ' }).ok, false);
  assert.equal(validatePushRegistration({ platform: 'android', pushToken: 'x'.repeat(4097) }).ok, false);
});

test('unsupported platform is rejected', () => {
  const result = validatePushRegistration({ platform: 'windows', pushToken: 'token' });
  assert.deepEqual(result, {
    ok: false,
    platform: null,
    pushToken: '',
    provider: null,
    error: 'unsupported_platform'
  });
});

test('legacy stored install reads deviceToken as ios target and new pushToken takes precedence', () => {
  const legacy = readStoredPushTarget({ deviceToken: 'b'.repeat(64), environment: 'production' });
  assert.deepEqual(legacy, {
    platform: 'ios', provider: 'apns', pushToken: 'b'.repeat(64), environment: 'production'
  });

  const migrated = readStoredPushTarget({
    platform: 'android',
    pushToken: 'new-token',
    deviceToken: 'c'.repeat(64),
    environment: 'sandbox'
  });
  assert.deepEqual(migrated, {
    platform: 'android', provider: 'fcm', pushToken: 'new-token', environment: undefined
  });
});
