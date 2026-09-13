import test from 'node:test';
import assert from 'node:assert/strict';
import { selectWakeTargets } from '../src/wake-targets.mjs';

test('selectWakeTargets returns only installs subscribed to requested topics', () => {
  const installs = {
    alpha: {
      deviceToken: 'aa',
      environment: 'production',
      subscriptions: ['dosefolk-local-a', 'dosefolk-peer-x']
    },
    beta: {
      deviceToken: 'bb',
      environment: 'sandbox',
      subscriptions: ['dosefolk-local-b']
    },
    gamma: {
      deviceToken: 'cc',
      environment: 'production',
      subscriptions: ['dosefolk-peer-x', 'dosefolk-peer-y']
    }
  };

  assert.deepEqual(
    selectWakeTargets(installs, ['dosefolk-peer-x']),
    [installs.alpha, installs.gamma]
  );
  assert.deepEqual(selectWakeTargets(installs, ['dosefolk-local-b']), [installs.beta]);
  assert.deepEqual(selectWakeTargets(installs, ['dosefolk-missing']), []);
  assert.deepEqual(selectWakeTargets(installs, []), []);
});

test('selectWakeTargets tolerates malformed stored subscriptions', () => {
  const installs = {
    valid: { subscriptions: ['dosefolk-peer-x'] },
    missing: {},
    invalid: { subscriptions: 'dosefolk-peer-x' }
  };

  assert.deepEqual(selectWakeTargets(installs, ['dosefolk-peer-x']), [installs.valid]);
});
