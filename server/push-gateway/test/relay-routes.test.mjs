import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { issueInstallCredential } from '../src/install-auth.mjs';
import { openMetadataStore } from '../src/relay-metadata-store.mjs';
import { openRelayQueue } from '../src/relay-queue-store.mjs';
import {
  authorizeRoute,
  listRoutesForSender,
  revokeRoute
} from '../src/relay-routes.mjs';

function installation(store, {
  installId,
  credentialHash = `${installId}-credential-hash`,
  encryptionPublicKey = `${installId}-enc-key`,
  signingPublicKey = `${installId}-sig-key`,
  keyVersion = 1,
  revokedAt = null,
  now = 1_700_000_000_000
}) {
  return store.upsertInstallation({
    installId,
    platform: 'android',
    credentialHash,
    encryptionPublicKey,
    signingPublicKey,
    keyVersion,
    revokedAt,
    now
  });
}

function route(store, {
  routeId,
  senderInstallId,
  recipientInstallId,
  status = 'active',
  createdAt = 1_700_000_000_000,
  revokedAt = null
}) {
  return store.insertRoute({
    routeId,
    senderInstallId,
    recipientInstallId,
    status,
    createdAt,
    revokedAt
  });
}

async function stores(t) {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-relay-routes-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const metadataStore = openMetadataStore(join(dir, 'metadata.sqlite3'));
  const relayQueue = openRelayQueue(join(dir, 'queue.sqlite3'));
  t.after(() => metadataStore.close());
  t.after(() => relayQueue.close());
  return { dir, metadataStore, relayQueue };
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
      RELAY_METADATA_DB: join(dir, 'metadata.sqlite3'),
      RELAY_QUEUE_DB: join(dir, 'queue.sqlite3'),
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

test('A to B route authorizes only A and exposes current recipient public-key metadata', async t => {
  const { metadataStore } = await stores(t);
  const sender = installation(metadataStore, { installId: 'relay-sender-A' });
  installation(metadataStore, {
    installId: 'relay-recipient-B',
    encryptionPublicKey: 'recipient-B-x25519-v3',
    signingPublicKey: 'recipient-B-ed25519-v3',
    keyVersion: 3
  });
  route(metadataStore, {
    routeId: 'route-A-to-B',
    senderInstallId: 'relay-sender-A',
    recipientInstallId: 'relay-recipient-B'
  });

  const authorized = authorizeRoute(metadataStore, 'route-A-to-B', sender);
  assert.equal(authorized?.routeId, 'route-A-to-B');
  assert.equal(authorized?.senderInstallId, 'relay-sender-A');
  assert.equal(authorized?.recipientInstallId, 'relay-recipient-B');

  const listed = listRoutesForSender(metadataStore, 'relay-sender-A');
  assert.deepEqual(listed, [{
    routeId: 'route-A-to-B',
    recipientInstallId: 'relay-recipient-B',
    encryptionPublicKey: 'recipient-B-x25519-v3',
    signingPublicKey: 'recipient-B-ed25519-v3',
    keyVersion: 3
  }]);
});

test('recipient cannot use the reverse direction to impersonate the sender', async t => {
  const { metadataStore } = await stores(t);
  installation(metadataStore, { installId: 'relay-sender-A' });
  const recipient = installation(metadataStore, { installId: 'relay-recipient-B' });
  route(metadataStore, {
    routeId: 'route-A-to-B',
    senderInstallId: 'relay-sender-A',
    recipientInstallId: 'relay-recipient-B'
  });

  assert.equal(authorizeRoute(metadataStore, 'route-A-to-B', recipient), null);
  assert.deepEqual(listRoutesForSender(metadataStore, 'relay-recipient-B'), []);
});

test('unknown and revoked routes fail closed', async t => {
  const { metadataStore } = await stores(t);
  const sender = installation(metadataStore, { installId: 'relay-sender-A' });
  installation(metadataStore, { installId: 'relay-recipient-B' });
  route(metadataStore, {
    routeId: 'route-revoked',
    senderInstallId: 'relay-sender-A',
    recipientInstallId: 'relay-recipient-B',
    status: 'revoked',
    revokedAt: 1_700_000_000_001
  });

  assert.equal(authorizeRoute(metadataStore, 'route-unknown', sender), null);
  assert.equal(authorizeRoute(metadataStore, 'route-revoked', sender), null);
});

test('revoked sender or recipient installation fails closed and is omitted from listing', async t => {
  const { metadataStore } = await stores(t);
  const now = 1_700_000_000_000;
  const sender = installation(metadataStore, { installId: 'relay-sender-A', now });
  installation(metadataStore, { installId: 'relay-recipient-B', now });
  route(metadataStore, {
    routeId: 'route-A-to-B',
    senderInstallId: 'relay-sender-A',
    recipientInstallId: 'relay-recipient-B',
    createdAt: now
  });

  installation(metadataStore, {
    installId: 'relay-sender-A',
    revokedAt: now + 1,
    now: now + 1
  });
  assert.equal(authorizeRoute(metadataStore, 'route-A-to-B', sender), null);
  assert.deepEqual(listRoutesForSender(metadataStore, 'relay-sender-A'), []);

  installation(metadataStore, { installId: 'relay-sender-A', revokedAt: null, now: now + 2 });
  const activeSender = metadataStore.getInstallation('relay-sender-A');
  installation(metadataStore, {
    installId: 'relay-recipient-B',
    revokedAt: now + 3,
    now: now + 3
  });
  assert.equal(authorizeRoute(metadataStore, 'route-A-to-B', activeSender), null);
  assert.deepEqual(listRoutesForSender(metadataStore, 'relay-sender-A'), []);
});

test('listing returns only active routes owned by the authenticated sender', async t => {
  const { metadataStore } = await stores(t);
  installation(metadataStore, { installId: 'relay-sender-A' });
  installation(metadataStore, { installId: 'relay-sender-C' });
  installation(metadataStore, { installId: 'relay-recipient-B' });
  installation(metadataStore, { installId: 'relay-recipient-D' });
  route(metadataStore, {
    routeId: 'route-A-to-B',
    senderInstallId: 'relay-sender-A',
    recipientInstallId: 'relay-recipient-B'
  });
  route(metadataStore, {
    routeId: 'route-C-to-D',
    senderInstallId: 'relay-sender-C',
    recipientInstallId: 'relay-recipient-D'
  });
  route(metadataStore, {
    routeId: 'route-A-to-D-revoked',
    senderInstallId: 'relay-sender-A',
    recipientInstallId: 'relay-recipient-D',
    status: 'revoked',
    revokedAt: 1_700_000_000_001
  });

  assert.deepEqual(
    listRoutesForSender(metadataStore, 'relay-sender-A').map(item => item.routeId),
    ['route-A-to-B']
  );
});

test('route revocation marks route revoked, purges pending ciphertext, and blocks future authorization', async t => {
  const { metadataStore, relayQueue } = await stores(t);
  const sender = installation(metadataStore, { installId: 'relay-sender-A' });
  installation(metadataStore, { installId: 'relay-recipient-B' });
  route(metadataStore, {
    routeId: 'route-A-to-B',
    senderInstallId: 'relay-sender-A',
    recipientInstallId: 'relay-recipient-B'
  });
  relayQueue.enqueue({
    messageId: 'message-before-revoke',
    routeId: 'route-A-to-B',
    senderInstallId: 'relay-sender-A',
    recipientInstallId: 'relay-recipient-B',
    senderKeyVersion: 1,
    recipientKeyVersion: 1,
    ciphertext: Buffer.from('opaque-ciphertext'),
    receivedAt: 1_700_000_000_000,
    expiresAt: 1_700_000_000_000 + 30 * 24 * 60 * 60 * 1000
  });

  assert.equal(revokeRoute(metadataStore, relayQueue, 'route-A-to-B', 'relay-sender-A', 1_700_000_000_100), true);
  assert.equal(metadataStore.getRoute('route-A-to-B')?.status, 'revoked');
  assert.deepEqual(relayQueue.listRecipient('relay-recipient-B'), []);
  assert.equal(authorizeRoute(metadataStore, 'route-A-to-B', sender), null);
});

test('GET /v1/routes derives sender identity from bearer credential and returns only that sender routes', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-relay-route-http-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const metadataFile = join(dir, 'metadata.sqlite3');
  const metadataStore = openMetadataStore(metadataFile);
  const credentialA = issueInstallCredential();
  const credentialB = issueInstallCredential();
  installation(metadataStore, {
    installId: 'relay-http-A',
    credentialHash: credentialA.credentialHash
  });
  installation(metadataStore, {
    installId: 'relay-http-B',
    credentialHash: credentialB.credentialHash,
    encryptionPublicKey: 'http-B-x25519-v2',
    signingPublicKey: 'http-B-ed25519-v2',
    keyVersion: 2
  });
  route(metadataStore, {
    routeId: 'route-http-A-to-B',
    senderInstallId: 'relay-http-A',
    recipientInstallId: 'relay-http-B'
  });
  metadataStore.close();

  const base = await startServer(t, 26014, dir);
  const responseA = await fetch(`${base}/v1/routes`, {
    headers: { authorization: `Bearer ${credentialA.credential}` }
  });
  assert.equal(responseA.status, 200);
  assert.deepEqual(await responseA.json(), {
    routes: [{
      routeId: 'route-http-A-to-B',
      recipientInstallId: 'relay-http-B',
      encryptionPublicKey: 'http-B-x25519-v2',
      signingPublicKey: 'http-B-ed25519-v2',
      keyVersion: 2
    }]
  });

  const responseB = await fetch(`${base}/v1/routes`, {
    headers: { authorization: `Bearer ${credentialB.credential}` }
  });
  assert.equal(responseB.status, 200);
  assert.deepEqual(await responseB.json(), { routes: [] });

  const unauthenticated = await fetch(`${base}/v1/routes`);
  assert.equal(unauthenticated.status, 401);
});
