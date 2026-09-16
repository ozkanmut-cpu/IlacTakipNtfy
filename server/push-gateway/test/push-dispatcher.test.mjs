import test from 'node:test';
import assert from 'node:assert/strict';
import { PushDispatcher } from '../src/push-dispatcher.mjs';

const apnsToken = 'a'.repeat(64);

test('legacy install routes to APNs without exposing the target in the result', async () => {
  let wakeArgs;
  const dispatcher = new PushDispatcher({
    apns: {
      ready: async () => true,
      wake: async (...args) => { wakeArgs = args; }
    },
    fcm: { ready: async () => true, wake: async () => {} }
  });

  const result = await dispatcher.dispatch({ deviceToken: apnsToken, environment: 'sandbox' });
  assert.deepEqual(wakeArgs, [apnsToken, 'sandbox']);
  assert.deepEqual(result, { provider: 'apns', status: 'sent' });
});

test('android install routes to FCM by Firebase Installation ID', async () => {
  let target;
  const dispatcher = new PushDispatcher({
    apns: { ready: async () => true, wake: async () => {} },
    fcm: {
      ready: async () => true,
      wake: async value => { target = value; }
    }
  });

  const result = await dispatcher.dispatch({ platform: 'android', pushToken: 'fid-123' });
  assert.equal(target, 'fid-123');
  assert.deepEqual(result, { provider: 'fcm', status: 'sent' });
});

test('provider readiness is independent and fail-closed', async () => {
  const dispatcher = new PushDispatcher({
    apns: { ready: async () => false, wake: async () => {} },
    fcm: { ready: async () => true, wake: async () => {} }
  });

  assert.deepEqual(await dispatcher.readiness(), { apns: false, fcm: true });
  assert.deepEqual(
    await dispatcher.dispatch({ deviceToken: apnsToken }),
    { provider: 'apns', status: 'unavailable' }
  );
});

test('transient FCM failures retry at most twice before succeeding', async () => {
  let calls = 0;
  const sleeps = [];
  const dispatcher = new PushDispatcher({
    apns: { ready: async () => true, wake: async () => {} },
    fcm: {
      ready: async () => true,
      wake: async () => {
        calls += 1;
        if (calls < 3) throw Object.assign(new Error('temporary'), { code: 'messaging/server-unavailable' });
      }
    },
    sleeper: async delay => { sleeps.push(delay); },
    random: () => 0
  });

  const result = await dispatcher.dispatch({ platform: 'android', pushToken: 'fid-retry' });
  assert.deepEqual(result, { provider: 'fcm', status: 'sent' });
  assert.equal(calls, 3);
  assert.equal(sleeps.length, 2);
});

test('invalid FCM target is not retried and missing targets are skipped', async () => {
  let calls = 0;
  const dispatcher = new PushDispatcher({
    apns: { ready: async () => true, wake: async () => {} },
    fcm: {
      ready: async () => true,
      wake: async () => {
        calls += 1;
        throw Object.assign(new Error('gone'), { code: 'messaging/registration-token-not-registered' });
      }
    },
    sleeper: async () => { throw new Error('must not retry'); }
  });

  assert.deepEqual(
    await dispatcher.dispatch({ platform: 'android', pushToken: 'fid-gone' }),
    { provider: 'fcm', status: 'invalid_target' }
  );
  assert.equal(calls, 1);
  assert.deepEqual(await dispatcher.dispatch({ subscriptions: ['topic'] }), {
    provider: null,
    status: 'missing_target'
  });
});
