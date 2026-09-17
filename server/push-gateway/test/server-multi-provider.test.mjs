import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac, generateKeyPairSync } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';

const installKey = 'test-install-hmac-key';
const internalSecret = 'test-internal-secret';

function gatewayCredential(installId) {
  return createHmac('sha256', installKey).update(installId).digest('base64url');
}

async function waitForServer(child) {
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('server_start_timeout')), 5000);
    child.stdout.on('data', chunk => {
      if (chunk.toString().includes('dosefolk-push-gateway listening')) {
        clearTimeout(timer);
        resolve();
      }
    });
    child.once('exit', code => {
      clearTimeout(timer);
      reject(new Error(`server_exited_${code}`));
    });
  });
}

async function startServer(t, port, dataFile, envOverrides = {}) {
  const child = spawn(process.execPath, ['src/server.mjs'], {
    cwd: new URL('..', import.meta.url),
    env: {
      ...process.env,
      HOST: '127.0.0.1',
      PORT: String(port),
      DATA_FILE: dataFile,
      RELAY_METADATA_DB: join(dirname(dataFile), 'relay-metadata.sqlite3'),
      RELAY_QUEUE_DB: join(dirname(dataFile), 'relay-queue.sqlite3'),
      INSTALL_HMAC_KEY: installKey,
      INTERNAL_WAKE_SECRET: internalSecret,
      APNS_TEAM_ID: '',
      APNS_KEY_ID: '',
      APNS_KEY_PATH: '',
      GOOGLE_APPLICATION_CREDENTIALS: '',
      NTFY_AUTH_FILE: '',
      ...envOverrides
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  t.after(() => child.kill('SIGTERM'));
  await waitForServer(child);
  return `http://127.0.0.1:${port}`;
}

async function writeStore(dataFile, store) {
  await writeFile(dataFile, JSON.stringify({ installs: {}, enrollments: {}, topicBindings: {}, ...store }));
}

async function fakeFirebaseCredential(dir) {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const path = join(dir, 'firebase-service-account.json');
  await writeFile(path, JSON.stringify({
    project_id: 'dosefolk-test',
    client_email: 'dosefolk-test@example.invalid',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' })
  }));
  return path;
}

async function fakeApnsKey(dir) {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const path = join(dir, 'apns.p8');
  await writeFile(path, privateKey.export({ type: 'pkcs8', format: 'pem' }));
  return path;
}

test('health is ready with provisioning and FCM even when APNs is unavailable', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-fcm-health-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dataFile = join(dir, 'registrations.json');
  const credentialPath = await fakeFirebaseCredential(dir);
  const base = await startServer(t, 26001, dataFile, { GOOGLE_APPLICATION_CREDENTIALS: credentialPath });

  const response = await fetch(`${base}/health`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    ok: true,
    ready: true,
    provisioningReady: true,
    providers: { apns: { ready: false }, fcm: { ready: true } },
    relay: { pendingCount: 0, pendingBytes: 0, oldestPendingAgeMs: 0 }
  });
});

test('android registration succeeds with FCM ready and persists the common target schema', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-fcm-register-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dataFile = join(dir, 'registrations.json');
  const credentialPath = await fakeFirebaseCredential(dir);
  const installId = 'install-android-1234';
  const base = await startServer(t, 26002, dataFile, { GOOGLE_APPLICATION_CREDENTIALS: credentialPath });

  const response = await fetch(`${base}/v1/register`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${gatewayCredential(installId)}`
    },
    body: JSON.stringify({
      installId,
      platform: 'android',
      pushToken: 'fid-android-1234',
      localTopic: 'dosefolk-local',
      subscriptions: ['dosefolk-local', 'dosefolk-peer', 'dosefolk-peer']
    })
  });
  assert.equal(response.status, 204);

  const store = JSON.parse(await readFile(dataFile, 'utf8'));
  assert.equal(store.installs[installId].platform, 'android');
  assert.equal(store.installs[installId].pushToken, 'fid-android-1234');
  assert.deepEqual(store.installs[installId].subscriptions, ['dosefolk-local', 'dosefolk-peer']);
  assert.equal(store.installs[installId].deviceToken, undefined);
});

test('registration gates only the requested provider and preserves legacy iOS payloads', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-provider-register-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dataFile = join(dir, 'registrations.json');
  const apnsKey = await fakeApnsKey(dir);
  const androidInstall = 'install-android-offline';
  const iosInstall = 'install-ios-legacy';
  const base = await startServer(t, 26003, dataFile, {
    APNS_TEAM_ID: 'TEAM123456',
    APNS_KEY_ID: 'KEY1234567',
    APNS_KEY_PATH: apnsKey
  });

  const android = await fetch(`${base}/v1/register`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${gatewayCredential(androidInstall)}` },
    body: JSON.stringify({ installId: androidInstall, platform: 'android', pushToken: 'fid-offline' })
  });
  assert.equal(android.status, 503);
  assert.deepEqual(await android.json(), { error: 'push_provider_unavailable', provider: 'fcm' });

  const ios = await fetch(`${base}/v1/register`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${gatewayCredential(iosInstall)}` },
    body: JSON.stringify({ installId: iosInstall, deviceToken: 'a'.repeat(64), environment: 'sandbox' })
  });
  assert.equal(ios.status, 204);
  const store = JSON.parse(await readFile(dataFile, 'utf8'));
  assert.equal(store.installs[iosInstall].platform, 'ios');
  assert.equal(store.installs[iosInstall].pushToken, 'a'.repeat(64));
  assert.equal(store.installs[iosInstall].environment, 'sandbox');
});

test('iOS registration fails provider-locally when only FCM is ready', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-ios-offline-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dataFile = join(dir, 'registrations.json');
  const credentialPath = await fakeFirebaseCredential(dir);
  const installId = 'install-ios-offline';
  const base = await startServer(t, 26004, dataFile, { GOOGLE_APPLICATION_CREDENTIALS: credentialPath });

  const response = await fetch(`${base}/v1/register`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${gatewayCredential(installId)}` },
    body: JSON.stringify({ installId, deviceToken: 'b'.repeat(64) })
  });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: 'push_provider_unavailable', provider: 'apns' });
});

test('internal wake reports mixed providers independently even when both are unavailable', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-mixed-wake-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dataFile = join(dir, 'registrations.json');
  await writeStore(dataFile, {
    installs: {
      ios: { platform: 'ios', pushToken: 'c'.repeat(64), subscriptions: ['dosefolk-peer'] },
      android: { platform: 'android', pushToken: 'fid-mixed', subscriptions: ['dosefolk-peer'] }
    }
  });
  const base = await startServer(t, 26005, dataFile);

  const response = await fetch(`${base}/internal/wake`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-internal-secret': internalSecret },
    body: JSON.stringify({ topics: ['dosefolk-peer'] })
  });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    targeted: 2,
    sent: 0,
    providers: {
      apns: { targeted: 1, sent: 0 },
      fcm: { targeted: 1, sent: 0 }
    }
  });
});
