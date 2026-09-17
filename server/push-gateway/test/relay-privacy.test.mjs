import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { issueInstallCredential } from '../src/install-auth.mjs';
import { openMetadataStore } from '../src/relay-metadata-store.mjs';
import { openRelayQueue } from '../src/relay-queue-store.mjs';
import { relayMetrics } from '../src/relay-metrics.mjs';

const NOW = 1_800_000_000_000;
const forbiddenKeys = ['messageId', 'routeId', 'senderInstallId', 'recipientInstallId', 'ciphertext',
  'eventType', 'medication', 'dose', 'schedule', 'stock', 'note', 'credential', 'pushToken'];
function enqueue(queue, { id, recipient = 'recipient-B', bytes = 5, receivedAt = NOW - 5000, expiresAt = NOW + 5000 }) {
  queue.enqueue({ messageId: id, routeId: 'route-A-B', senderInstallId: 'sender-A',
    recipientInstallId: recipient, senderKeyVersion: 1, recipientKeyVersion: 1,
    ciphertext: Buffer.alloc(bytes, 9), receivedAt, expiresAt });
}
async function waitForServer(child) {
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('startup_timeout')), 5000);
    child.stdout.on('data', chunk => { if (chunk.toString().includes('listening')) { clearTimeout(timer); resolve(); } });
    child.once('exit', code => { clearTimeout(timer); reject(new Error(`server_exit_${code}`)); });
  });
}

test('relayMetrics exposes aggregate queue health only and ignores expired ciphertext', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-metrics-'));
  const queue = openRelayQueue(join(dir, 'queue.sqlite3'));
  t.after(async () => { queue.close(); await rm(dir, { recursive: true, force: true }); });
  enqueue(queue, { id: 'private-message-A', bytes: 5, receivedAt: NOW - 5000 });
  enqueue(queue, { id: 'private-message-B', bytes: 7, receivedAt: NOW - 2000 });
  enqueue(queue, { id: 'expired-private', bytes: 11, receivedAt: NOW - 9000, expiresAt: NOW });

  const metrics = relayMetrics(queue, NOW);
  assert.deepEqual(metrics, { pendingCount: 2, pendingBytes: 12, oldestPendingAgeMs: 5000 });
  assert.deepEqual(Object.keys(metrics).sort(), ['oldestPendingAgeMs', 'pendingBytes', 'pendingCount']);
  const serialized = JSON.stringify(metrics);
  for (const forbidden of [...forbiddenKeys, 'private-message-A', 'private-message-B']) {
    assert.equal(serialized.includes(forbidden), false);
  }
});

test('HTTP relay rate limiting is per authenticated installation and health exposes only aggregates', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-privacy-http-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const metadata = openMetadataStore(join(dir, 'metadata.sqlite3'));
  const queue = openRelayQueue(join(dir, 'queue.sqlite3'));
  const credentials = {};
  for (const installId of ['recipient-B', 'recipient-C']) {
    const issued = issueInstallCredential(); credentials[installId] = issued.credential;
    metadata.upsertInstallation({ installId, platform: 'android', credentialHash: issued.credentialHash,
      encryptionPublicKey: 'synthetic-encryption-key', signingPublicKey: 'synthetic-signing-key', keyVersion: 1, now: NOW });
  }
  enqueue(queue, { id: 'private-message-A' });
  metadata.close(); queue.close();

  let stdout = '', stderr = '';
  const child = spawn(process.execPath, ['src/server.mjs'], { cwd: new URL('..', import.meta.url), env: {
    ...process.env, HOST: '127.0.0.1', PORT: '26027', DATA_FILE: join(dir, 'legacy.json'),
    RELAY_METADATA_DB: join(dir, 'metadata.sqlite3'), RELAY_QUEUE_DB: join(dir, 'queue.sqlite3'),
    INSTALL_HMAC_KEY: 'legacy-hmac-secret', INTERNAL_WAKE_SECRET: 'internal-wake-secret',
    APNS_TEAM_ID: '', APNS_KEY_ID: '', APNS_KEY_PATH: '', NTFY_AUTH_FILE: ''
  }, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.on('data', chunk => { stdout += chunk; });
  child.stderr.on('data', chunk => { stderr += chunk; });
  t.after(async () => { if (child.exitCode === null) { child.kill(); await once(child, 'exit'); } });
  await waitForServer(child);
  const getInbox = installId => fetch('http://127.0.0.1:26027/v1/inbox', {
    headers: { authorization: `Bearer ${credentials[installId]}` }
  });
  for (let i = 0; i < 120; i++) assert.equal((await getInbox('recipient-B')).status, 200);
  const limited = await getInbox('recipient-B');
  assert.equal(limited.status, 429);
  const retryAfter = Number(limited.headers.get('retry-after'));
  assert.ok(retryAfter >= 1 && retryAfter <= 60);
  assert.deepEqual(await limited.json(), { error: 'rate_limited' });
  const limitedRoute = await fetch('http://127.0.0.1:26027/v1/routes', {
    headers: { authorization: `Bearer ${credentials['recipient-B']}` }
  });
  assert.equal(limitedRoute.status, 429);
  const malformedWhileLimited = await fetch('http://127.0.0.1:26027/v1/messages', {
    method: 'POST', headers: { authorization: `Bearer ${credentials['recipient-B']}`,
      'content-type': 'application/json' }, body: '{INVALID_PRIVATE_BODY'
  });
  assert.equal(malformedWhileLimited.status, 429);
  assert.equal((await getInbox('recipient-C')).status, 200);

  const health = await (await fetch('http://127.0.0.1:26027/health')).json();
  assert.deepEqual(health.relay, { pendingCount: 1, pendingBytes: 5, oldestPendingAgeMs: 0 });
  const serialized = JSON.stringify(health);
  for (const forbidden of [...forbiddenKeys, 'private-message-A']) assert.equal(serialized.includes(forbidden), false);
  assert.equal(stdout.includes(credentials['recipient-B']), false);
  assert.equal(stderr.includes(credentials['recipient-B']), false);
});

test('malformed relay requests never log bearer credential, ciphertext or domain data', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-private-logs-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const metadata = openMetadataStore(join(dir, 'metadata.sqlite3'));
  const issued = issueInstallCredential();
  metadata.upsertInstallation({ installId: 'sender-private', platform: 'android', credentialHash: issued.credentialHash,
    encryptionPublicKey: 'synthetic-encryption-key', signingPublicKey: 'synthetic-signing-key', keyVersion: 1, now: NOW });
  metadata.close();
  let stdout = '', stderr = '';
  const child = spawn(process.execPath, ['src/server.mjs'], { cwd: new URL('..', import.meta.url), env: {
    ...process.env, HOST: '127.0.0.1', PORT: '26028', DATA_FILE: join(dir, 'legacy.json'),
    RELAY_METADATA_DB: join(dir, 'metadata.sqlite3'), RELAY_QUEUE_DB: join(dir, 'queue.sqlite3'),
    INSTALL_HMAC_KEY: 'legacy-hmac-secret', INTERNAL_WAKE_SECRET: 'internal-wake-secret',
    APNS_TEAM_ID: '', APNS_KEY_ID: '', APNS_KEY_PATH: '', NTFY_AUTH_FILE: ''
  }, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.on('data', chunk => { stdout += chunk; }); child.stderr.on('data', chunk => { stderr += chunk; });
  await waitForServer(child);
  const privateValues = ['CIPHERTEXT_PRIVATE_7781', 'MEDICATION_PRIVATE_7782', 'NOTE_PRIVATE_7783', 'PAIRING_SE', issued.credential];
  await fetch('http://127.0.0.1:26028/v1/messages', { method: 'POST', headers: {
    authorization: `Bearer ${issued.credential}`, 'content-type': 'application/json'
  }, body: JSON.stringify({ messages: [{ ciphertext: privateValues[0], medication: privateValues[1], note: privateValues[2] }] }) });
  await fetch('http://127.0.0.1:26028/v1/pairing/offers', { method: 'POST', headers: {
    authorization: `Bearer ${issued.credential}`, 'content-type': 'application/json'
  }, body: `{"pairingSecret":${privateValues[3]}}` });
  child.kill(); await once(child, 'exit');
  const logs = stdout + stderr;
  for (const value of privateValues) assert.equal(logs.includes(value), false);
});
