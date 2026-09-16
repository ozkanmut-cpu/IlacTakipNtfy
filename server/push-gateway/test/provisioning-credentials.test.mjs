import assert from 'node:assert/strict';
import test from 'node:test';
import { buildProvisioningCredentials } from '../src/provisioning-credentials.mjs';

const gatewayCredential = 'gateway-hmac-credential';
const nativeToken = `tk_${'n'.repeat(29)}`;

test('legacy provisioning response is unchanged when ntfy auth is disabled', async () => {
  const result = await buildProvisioningCredentials({
    installId: 'install-1234',
    gatewayCredential,
    ntfyAuth: { ready: false }
  });
  assert.deepEqual(result, { installId: 'install-1234', credential: gatewayCredential });
});

test('legacy provisioning without topic metadata survives auth migration', async () => {
  let called = false;
  const result = await buildProvisioningCredentials({
    installId: 'install-1234',
    gatewayCredential,
    ntfyAuth: { ready: true, provision: async () => { called = true; } },
    requireNtfyToken: false
  });
  assert.deepEqual(result, { installId: 'install-1234', credential: gatewayCredential });
  assert.equal(called, false);
});

test('native ntfy token is returned separately from gateway credential', async () => {
  const calls = [];
  const ntfyAuth = {
    ready: true,
    provision: async (...args) => {
      calls.push(args);
      return { credential: nativeToken };
    }
  };
  const result = await buildProvisioningCredentials({
    installId: 'install-1234',
    gatewayCredential,
    localTopic: 'dosefolk-local',
    subscriptions: ['dosefolk-peer', 'dosefolk-local'],
    ntfyAuth
  });

  assert.deepEqual(result, {
    installId: 'install-1234',
    credential: gatewayCredential,
    ntfyToken: nativeToken
  });
  assert.deepEqual(calls, [[
    'install-1234',
    'dosefolk-local',
    ['dosefolk-local', 'dosefolk-peer']
  ]]);
});

test('native provisioning rejects invalid topic access before issuing token', async () => {
  let called = false;
  await assert.rejects(
    buildProvisioningCredentials({
      installId: 'install-1234',
      gatewayCredential,
      localTopic: 'bad topic',
      subscriptions: [],
      ntfyAuth: { ready: true, provision: async () => { called = true; } }
    }),
    /invalid_local_topic/
  );
  assert.equal(called, false);
});

test('required native token fails closed when ntfy auth is disabled', async () => {
  await assert.rejects(
    buildProvisioningCredentials({
      installId: 'install-1234',
      gatewayCredential: 'gateway-secret',
      localTopic: 'dosefolk-local',
      subscriptions: [],
      ntfyAuth: { ready: false },
      requireNtfyToken: true
    }),
    /ntfy_auth_unavailable/
  );
});
