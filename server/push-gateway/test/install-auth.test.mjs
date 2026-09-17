import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { authenticateInstall, issueInstallCredential } from '../src/install-auth.mjs';
import { openMetadataStore } from '../src/relay-metadata-store.mjs';

function sha256(value) {
  return createHash('sha256').update(value).digest('base64url');
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

async function startServer(t, port, dir) {
  const child = spawn(process.execPath, ['src/server.mjs'], {
    cwd: new URL('..', import.meta.url),
    env: {
      ...process.env,
      HOST: '127.0.0.1',
      PORT: String(port),
      DATA_FILE: join(dir, 'registrations.json'),
      RELAY_METADATA_DB: join(dir, 'relay-metadata.sqlite3'),
      INSTALL_HMAC_KEY: 'legacy-test-install-hmac-key',
      INTERNAL_WAKE_SECRET: 'legacy-test-internal-secret',
      APNS_TEAM_ID: '',
      APNS_KEY_ID: '',
      APNS_KEY_PATH: '',
      NTFY_AUTH_FILE: ''
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  t.after(() => child.kill('SIGTERM'));
  await waitForServer(child);
  return `http://127.0.0.1:${port}`;
}

test('install credential is random and represented server-side only by its sha256 hash', () => {
  const first = issueInstallCredential();
  const second = issueInstallCredential();

  assert.equal(typeof first.credential, 'string');
  assert.ok(first.credential.length >= 40);
  assert.equal(first.credentialHash, sha256(first.credential));
  assert.notEqual(first.credentialHash, first.credential);
  assert.notEqual(second.credential, first.credential);
});

test('install authentication accepts only the active installation credential', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-install-auth-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const store = openMetadataStore(join(dir, 'metadata.sqlite3'));
  t.after(() => store.close());
  const issued = issueInstallCredential();
  const installId = 'relay-install-auth-1';
  const now = Date.now();

  store.upsertInstallation({
    installId,
    platform: 'android',
    credentialHash: issued.credentialHash,
    encryptionPublicKey: 'enc-public-key',
    signingPublicKey: 'sig-public-key',
    keyVersion: 1,
    now
  });

  assert.equal(authenticateInstall('', store), null);
  assert.equal(authenticateInstall('Bearer random-wrong-token', store), null);
  assert.equal(authenticateInstall(`Bearer ${issued.credential}`, store)?.installId, installId);

  store.upsertInstallation({
    installId,
    platform: 'android',
    credentialHash: issued.credentialHash,
    encryptionPublicKey: 'enc-public-key',
    signingPublicKey: 'sig-public-key',
    keyVersion: 1,
    revokedAt: now + 1,
    now: now + 1
  });
  assert.equal(authenticateInstall(`Bearer ${issued.credential}`, store), null);
});

test('native provision returns credential once and persists only its hash with public identity metadata', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-native-provision-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const metadataFile = join(dir, 'relay-metadata.sqlite3');
  const base = await startServer(t, 26013, dir);

  const response = await fetch(`${base}/v1/provision`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      platform: 'android',
      encryptionPublicKey: 'x25519-public-key-v1',
      signingPublicKey: 'ed25519-public-key-v1',
      keyVersion: 1
    })
  });

  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(typeof body.installId, 'string');
  assert.ok(body.installId.length >= 16);
  assert.equal(typeof body.credential, 'string');
  assert.ok(body.credential.length >= 40);
  assert.equal('ntfyToken' in body, false);

  const store = openMetadataStore(metadataFile);
  const persisted = store.getInstallation(body.installId);
  store.close();
  assert.equal(persisted.platform, 'android');
  assert.equal(persisted.encryptionPublicKey, 'x25519-public-key-v1');
  assert.equal(persisted.signingPublicKey, 'ed25519-public-key-v1');
  assert.equal(persisted.keyVersion, 1);
  assert.equal(persisted.credentialHash, sha256(body.credential));
  assert.notEqual(persisted.credentialHash, body.credential);

  const rawDatabase = await readFile(metadataFile);
  assert.equal(rawDatabase.includes(Buffer.from(body.credential)), false);
});
