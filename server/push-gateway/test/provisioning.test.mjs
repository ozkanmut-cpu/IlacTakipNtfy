import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { spawn } from 'node:child_process';

const port = 25991;
const installKey = 'test-install-hmac-key';
const internalSecret = 'test-internal-secret';
const base = `http://127.0.0.1:${port}`;

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

test('internal provisioning is guarded and deterministic before APNs readiness', async t => {
  const child = spawn(process.execPath, ['src/server.mjs'], {
    cwd: new URL('..', import.meta.url),
    env: {
      ...process.env,
      HOST: '127.0.0.1',
      PORT: String(port),
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

  const unauthorized = await fetch(`${base}/internal/provision`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ installId: 'install-1234' })
  });
  assert.equal(unauthorized.status, 401);

  const invalid = await fetch(`${base}/internal/provision`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-internal-secret': internalSecret
    },
    body: JSON.stringify({ installId: 'bad' })
  });
  assert.equal(invalid.status, 400);

  const valid = await fetch(`${base}/internal/provision`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-internal-secret': internalSecret
    },
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
