import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const installKey = 'test-install-hmac-key';
const internalSecret = 'test-internal-secret';

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

async function startServer(t, port, dataFile) {
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
      APNS_KEY_PATH: ''
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  t.after(() => child.kill('SIGTERM'));
  await waitForServer(child);
  return `http://127.0.0.1:${port}`;
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
  const expected = createHmac('sha256', installKey).update('install-1234').digest('base64url');
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
  const expected = createHmac('sha256', installKey).update(installId).digest('base64url');
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
