import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { spawn } from 'node:child_process';
import { chmod, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const installKey = 'test-install-hmac-key';
const internalSecret = 'test-internal-secret';
const fakeNtfyToken = 'tk_12345678901234567890123456789';

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
      INSTALL_HMAC_KEY: installKey,
      INTERNAL_WAKE_SECRET: internalSecret,
      APNS_TEAM_ID: '',
      APNS_KEY_ID: '',
      APNS_KEY_PATH: '',
      NTFY_AUTH_FILE: '',
      ...envOverrides
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  t.after(() => child.kill('SIGTERM'));
  await waitForServer(child);
  return `http://127.0.0.1:${port}`;
}

function gatewayCredential(installId) {
  return createHmac('sha256', installKey).update(installId).digest('base64url');
}

async function writeStore(dataFile, store) {
  await writeFile(dataFile, JSON.stringify({ installs: {}, enrollments: {}, topicBindings: {}, ...store }));
}

async function makeFakeNtfyCli(dir, { token = false } = {}) {
  const path = join(dir, 'fake-ntfy.sh');
  const script = token
    ? `#!/bin/sh\nif [ "$1" = "token" ] && [ "$2" = "add" ]; then\n  echo "${fakeNtfyToken}"\nelif [ "$1" = "token" ] && [ "$2" = "list" ]; then\n  echo "${fakeNtfyToken}"\nfi\nexit 0\n`
    : '#!/bin/sh\nexit 0\n';
  await writeFile(path, script);
  await chmod(path, 0o755);
  return path;
}

test('internal provisioning is guarded and deterministic before APNs readiness', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-gateway-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const base = await startServer(t, 25991, join(dir, 'registrations.json'));

  const unauthorized = await fetch(`${base}/internal/provision`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ installId: 'install-1234' })
  });
  assert.equal(unauthorized.status, 401);

  const invalid = await fetch(`${base}/internal/provision`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-internal-secret': internalSecret },
    body: JSON.stringify({ installId: 'bad' })
  });
  assert.equal(invalid.status, 400);

  const valid = await fetch(`${base}/internal/provision`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-internal-secret': internalSecret },
    body: JSON.stringify({ installId: 'install-1234' })
  });
  assert.equal(valid.status, 200);
  const body = await valid.json();
  const expected = gatewayCredential('install-1234');
  assert.deepEqual(body, { installId: 'install-1234', credential: expected });

  const health = await fetch(`${base}/health`);
  const healthBody = await health.json();
  assert.equal(health.status, 200);
  assert.equal(healthBody.ready, false);
});

test('enrollment ticket is install-bound and single-use', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-enroll-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const base = await startServer(t, 25992, join(dir, 'registrations.json'));
  const installId = 'install-enroll-1234';

  const issue = await fetch(`${base}/internal/enrollment`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-internal-secret': internalSecret },
    body: JSON.stringify({ installId })
  });
  assert.equal(issue.status, 200);
  const issued = await issue.json();
  assert.equal(issued.installId, installId);
  assert.equal(typeof issued.ticket, 'string');
  assert.ok(issued.ticket.length >= 40);
  assert.ok(issued.expiresAt > Date.now());
  const enrollmentURL = new URL(issued.enrollmentURL);
  assert.equal(enrollmentURL.protocol, 'dosefolk:');
  assert.equal(enrollmentURL.hostname, 'enroll');
  assert.equal(enrollmentURL.searchParams.get('installId'), installId);
  assert.equal(enrollmentURL.searchParams.get('ticket'), issued.ticket);

  const wrongInstall = await fetch(`${base}/v1/provision`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ installId: 'install-other-1234', ticket: issued.ticket })
  });
  assert.equal(wrongInstall.status, 401);

  const issueAgain = await fetch(`${base}/internal/enrollment`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-internal-secret': internalSecret },
    body: JSON.stringify({ installId })
  });
  assert.equal(issueAgain.status, 200);
  const second = await issueAgain.json();

  const redeem = await fetch(`${base}/v1/provision`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ installId, ticket: second.ticket })
  });
  assert.equal(redeem.status, 200);
  const redeemed = await redeem.json();
  const expected = gatewayCredential(installId);
  assert.deepEqual(redeemed, { installId, credential: expected });

  const replay = await fetch(`${base}/v1/provision`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ installId, ticket: second.ticket })
  });
  assert.equal(replay.status, 401);

  const bogus = await fetch(`${base}/v1/provision`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ installId, ticket: 'not-a-real-ticket' })
  });
  assert.equal(bogus.status, 401);
});

test('secure provisioning failure does not consume enrollment ticket', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-secure-retry-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dataFile = join(dir, 'registrations.json');
  const base = await startServer(t, 25993, dataFile);
  const installId = 'install-secure-1234';

  const issue = await fetch(`${base}/internal/enrollment`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-internal-secret': internalSecret },
    body: JSON.stringify({ installId })
  });
  assert.equal(issue.status, 200);
  const issued = await issue.json();

  const secureAttempt = await fetch(`${base}/v1/provision`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      installId,
      ticket: issued.ticket,
      requireNtfyToken: true,
      localTopic: 'dosefolk-local',
      subscriptions: ['dosefolk-local', 'dosefolk-peer']
    })
  });
  assert.equal(secureAttempt.status, 503);
  assert.deepEqual(await secureAttempt.json(), { error: 'ntfy_auth_unavailable' });
  const failedStore = JSON.parse(await readFile(dataFile, 'utf8'));
  assert.equal(failedStore.topicBindings[installId], undefined);

  const legacyRetry = await fetch(`${base}/v1/provision`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ installId, ticket: issued.ticket })
  });
  assert.equal(legacyRetry.status, 200);
  const expected = gatewayCredential(installId);
  assert.deepEqual(await legacyRetry.json(), { installId, credential: expected });

  const replay = await fetch(`${base}/v1/provision`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ installId, ticket: issued.ticket })
  });
  assert.equal(replay.status, 401);
});

test('successful secure provisioning persists trusted topic binding', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-secure-binding-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dataFile = join(dir, 'registrations.json');
  const fakeCli = await makeFakeNtfyCli(dir, { token: true });
  const installId = 'install-secure-binding-1234';
  const localTopic = 'dosefolk-trusted-local';
  const base = await startServer(t, 25997, dataFile, {
    NTFY_AUTH_FILE: join(dir, 'auth.db'),
    NTFY_CLI_PATH: fakeCli
  });

  const issue = await fetch(`${base}/internal/enrollment`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-internal-secret': internalSecret },
    body: JSON.stringify({ installId })
  });
  assert.equal(issue.status, 200);
  const issued = await issue.json();

  const provision = await fetch(`${base}/v1/provision`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      installId,
      ticket: issued.ticket,
      requireNtfyToken: true,
      localTopic,
      subscriptions: [localTopic, 'dosefolk-peer']
    })
  });
  assert.equal(provision.status, 200);
  assert.deepEqual(await provision.json(), {
    installId,
    credential: gatewayCredential(installId),
    ntfyToken: fakeNtfyToken
  });

  const persisted = JSON.parse(await readFile(dataFile, 'utf8'));
  assert.equal(persisted.topicBindings[installId], localTopic);
});

test('bound access refresh requires gateway auth and an existing trusted binding', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-access-binding-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dataFile = join(dir, 'registrations.json');
  const fakeCli = await makeFakeNtfyCli(dir);
  const installId = 'install-access-1234';
  const base = await startServer(t, 25994, dataFile, { NTFY_AUTH_FILE: join(dir, 'auth.db'), NTFY_CLI_PATH: fakeCli });

  const unauthorized = await fetch(`${base}/v1/access`, {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer wrong' },
    body: JSON.stringify({ installId, subscriptions: ['dosefolk-peer'] })
  });
  assert.equal(unauthorized.status, 401);

  const missingBinding = await fetch(`${base}/v1/access`, {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${gatewayCredential(installId)}` },
    body: JSON.stringify({ installId, localTopic: 'dosefolk-attacker', subscriptions: ['dosefolk-peer'] })
  });
  assert.equal(missingBinding.status, 409);
  assert.deepEqual(await missingBinding.json(), { error: 'ntfy_reprovision_required' });
});

test('bound access refresh ignores caller localTopic and validates subscriptions against stored binding', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-access-refresh-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dataFile = join(dir, 'registrations.json');
  const fakeCli = await makeFakeNtfyCli(dir);
  const installId = 'install-refresh-1234';
  await writeStore(dataFile, { topicBindings: { [installId]: 'dosefolk-trusted-local' } });
  const base = await startServer(t, 25995, dataFile, { NTFY_AUTH_FILE: join(dir, 'auth.db'), NTFY_CLI_PATH: fakeCli });
  const headers = { 'content-type': 'application/json', authorization: `Bearer ${gatewayCredential(installId)}` };

  const invalid = await fetch(`${base}/v1/access`, {
    method: 'POST', headers,
    body: JSON.stringify({ installId, subscriptions: ['not-a-dosefolk-topic'] })
  });
  assert.equal(invalid.status, 400);
  assert.deepEqual(await invalid.json(), { error: 'invalid_topic_access' });

  const ok = await fetch(`${base}/v1/access`, {
    method: 'POST', headers,
    body: JSON.stringify({
      installId,
      localTopic: 'dosefolk-attacker-controlled',
      subscriptions: ['dosefolk-trusted-local', 'dosefolk-peer-one', 'dosefolk-peer-one']
    })
  });
  assert.equal(ok.status, 204);
  assert.equal(await ok.text(), '');

  const persisted = JSON.parse(await readFile(dataFile, 'utf8'));
  assert.equal(persisted.topicBindings[installId], 'dosefolk-trusted-local');
});

test('access refresh reports auth unavailable without weakening legacy provisioning', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-access-unavailable-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dataFile = join(dir, 'registrations.json');
  const installId = 'install-unavailable-1234';
  await writeStore(dataFile, { topicBindings: { [installId]: 'dosefolk-local' } });
  const base = await startServer(t, 25996, dataFile);

  const access = await fetch(`${base}/v1/access`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${gatewayCredential(installId)}` },
    body: JSON.stringify({ installId, subscriptions: ['dosefolk-local'] })
  });
  assert.equal(access.status, 503);
  assert.deepEqual(await access.json(), { error: 'ntfy_auth_unavailable' });

  const legacy = await fetch(`${base}/internal/provision`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-internal-secret': internalSecret },
    body: JSON.stringify({ installId })
  });
  assert.equal(legacy.status, 200);
  assert.deepEqual(await legacy.json(), { installId, credential: gatewayCredential(installId) });
});
