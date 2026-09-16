import test from 'node:test';
import assert from 'node:assert/strict';
import { FCMClient, classifyFcmError } from '../src/fcm.mjs';

test('ready is false when credential path is absent', async () => {
  const client = new FCMClient({ credentialPath: '' });
  assert.equal(await client.ready(), false);
});

test('wake sends only the minimal wake contract to a Firebase Installation ID', async () => {
  let message;
  const client = new FCMClient({
    credentialPath: '/fake/firebase-service-account.json',
    credentialProbe: async () => true,
    sender: async value => {
      message = value;
      return 'projects/dosefolk/messages/message-1';
    }
  });

  const result = await client.wake('fid-1');

  assert.deepEqual(message, {
    fid: 'fid-1',
    data: { wakeType: 'sync', protocolVersion: '1' },
    android: { priority: 'normal' }
  });
  assert.deepEqual(result, { messageId: 'projects/dosefolk/messages/message-1' });
});

test('classifyFcmError distinguishes invalid targets, transient failures, and permanent errors', () => {
  assert.equal(classifyFcmError({ code: 'messaging/registration-token-not-registered' }), 'invalid_target');
  assert.equal(classifyFcmError({ code: 'messaging/invalid-registration-token' }), 'invalid_target');
  assert.equal(classifyFcmError({ code: 'messaging/invalid-recipient' }), 'invalid_target');
  assert.equal(classifyFcmError({ code: 'messaging/server-unavailable' }), 'transient');
  assert.equal(classifyFcmError({ code: 'messaging/internal-error' }), 'transient');
  assert.equal(classifyFcmError({ code: 'messaging/message-rate-exceeded' }), 'transient');
  assert.equal(classifyFcmError({ code: 'messaging/device-message-rate-exceeded' }), 'transient');
  assert.equal(classifyFcmError({ code: 'messaging/authentication-error' }), 'permanent');
  assert.equal(classifyFcmError(new Error('network failure')), 'transient');
});
