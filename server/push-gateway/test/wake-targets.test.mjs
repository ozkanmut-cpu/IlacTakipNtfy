import test from 'node:test';
import assert from 'node:assert/strict';
import { selectWakeTargets } from '../src/wake-targets.mjs';

const tokenA = 'a'.repeat(64);
const tokenB = 'b'.repeat(64);
const tokenC = 'c'.repeat(64);

test('selectWakeTargets returns only registered installs subscribed to requested topics', () => {
  const installs = {
    alpha: {
      deviceToken: tokenA,
      environment: 'production',
      subscriptions: ['dosefolk-local-a', 'dosefolk-peer-x']
    },
    beta: {
      deviceToken: tokenB,
      environment: 'sandbox',
      subscriptions: ['dosefolk-local-b']
    },
    gamma: {
      platform: 'android',
      pushToken: 'fid-gamma',
      subscriptions: ['dosefolk-peer-x', 'dosefolk-peer-y']
    },
    stale: {
      platform: 'android',
      subscriptions: ['dosefolk-peer-x']
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

test('selectWakeTargets tolerates malformed stored subscriptions and targets', () => {
  const installs = {
    valid: { deviceToken: tokenC, subscriptions: ['dosefolk-peer-x'] },
    missingSubscriptions: { deviceToken: tokenA },
    invalidSubscriptions: { deviceToken: tokenB, subscriptions: 'dosefolk-peer-x' },
    missingTarget: { subscriptions: ['dosefolk-peer-x'] }
  };

  assert.deepEqual(selectWakeTargets(installs, ['dosefolk-peer-x']), [installs.valid]);
});
