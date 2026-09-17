import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { issueInstallCredential } from '../src/install-auth.mjs';
import { openMetadataStore } from '../src/relay-metadata-store.mjs';
import {
  acceptPairingOffer,
  confirmPairingOffer,
  createPairingOffer,
  createPairingProof,
  hashPairingSecret
} from '../src/relay-pairing.mjs';

const TEN_MINUTES = 10 * 60 * 1000;

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

async function storeFixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-relay-pairing-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const metadataFile = join(dir, 'metadata.sqlite3');
  const metadataStore = openMetadataStore(metadataFile);
  t.after(() => metadataStore.close());
  return { dir, metadataFile, metadataStore };
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

test('pairing offer stores only secret hash and proof metadata with a hard ten minute lifetime', async t => {
  const { metadataFile, metadataStore } = await storeFixture(t);
  const now = 1_700_000_000_000;
  installation(metadataStore, { installId: 'relay-pair-A', now });

  const pairingSecret = 'pairing-secret-that-must-never-be-persisted-0001';
  const offerId = 'offer-A-0001';
  const secretHash = hashPairingSecret(pairingSecret);
  const creatorProof = createPairingProof(pairingSecret, {
    offerId,
    role: 'creator',
    installId: 'relay-pair-A'
  });

  const created = createPairingOffer(metadataStore, {
    offerId,
    creatorInstallId: 'relay-pair-A',
    secretHash,
    creatorProof,
    now
  });

  assert.equal(created.offerId, offerId);
  assert.equal(created.creatorInstallId, 'relay-pair-A');
  assert.equal(created.expiresAt, now + TEN_MINUTES);
  assert.equal(created.secretHash, secretHash);
  assert.equal('pairingSecret' in created, false);

  const persisted = metadataStore.getPairingOffer(offerId);
  assert.equal(persisted.secretHash, secretHash);
  assert.equal(persisted.creatorProof, creatorProof);
  assert.equal(persisted.expiresAt, now + TEN_MINUTES);
  assert.equal('pairingSecret' in persisted, false);

  const rawDatabase = await readFile(metadataFile);
  assert.equal(rawDatabase.includes(Buffer.from(pairingSecret)), false);
});

test('offer cannot confirm until both authenticated devices prove pairing-secret knowledge', async t => {
  const { metadataStore } = await storeFixture(t);
  const now = 1_700_000_000_000;
  installation(metadataStore, { installId: 'relay-pair-A', now });
  installation(metadataStore, { installId: 'relay-pair-B', now });

  const pairingSecret = 'pairing-secret-confirm-0002';
  const offerId = 'offer-A-0002';
  createPairingOffer(metadataStore, {
    offerId,
    creatorInstallId: 'relay-pair-A',
    secretHash: hashPairingSecret(pairingSecret),
    creatorProof: createPairingProof(pairingSecret, {
      offerId,
      role: 'creator',
      installId: 'relay-pair-A'
    }),
    now
  });

  assert.equal(confirmPairingOffer(metadataStore, {
    offerId,
    actorInstallId: 'relay-pair-A',
    pairingSecret,
    now: now + 1
  }), null);
  assert.deepEqual(metadataStore.listActiveRoutesForSender('relay-pair-A'), []);
  assert.deepEqual(metadataStore.listActiveRoutesForSender('relay-pair-B'), []);

  const accepted = acceptPairingOffer(metadataStore, {
    offerId,
    peerInstallId: 'relay-pair-B',
    pairingSecret,
    peerProof: createPairingProof(pairingSecret, {
      offerId,
      role: 'peer',
      installId: 'relay-pair-B'
    }),
    now: now + 2
  });
  assert.equal(accepted.peerInstallId, 'relay-pair-B');

  const confirmed = confirmPairingOffer(metadataStore, {
    offerId,
    actorInstallId: 'relay-pair-A',
    pairingSecret,
    now: now + 3
  });
  assert.ok(confirmed);
  assert.equal(confirmed.offerId, offerId);
  assert.equal(confirmed.status, 'confirmed');

  const fromA = metadataStore.listActiveRoutesForSender('relay-pair-A');
  const fromB = metadataStore.listActiveRoutesForSender('relay-pair-B');
  assert.equal(fromA.length, 1);
  assert.equal(fromA[0].recipientInstallId, 'relay-pair-B');
  assert.equal(fromB.length, 1);
  assert.equal(fromB[0].recipientInstallId, 'relay-pair-A');
});

test('wrong secret, expired offer, and replayed confirmation all fail closed', async t => {
  const { metadataStore } = await storeFixture(t);
  const now = 1_700_000_000_000;
  installation(metadataStore, { installId: 'relay-pair-A', now });
  installation(metadataStore, { installId: 'relay-pair-B', now });

  const expiredSecret = 'pairing-secret-expired-0003';
  const expiredOffer = 'offer-expired-0003';
  createPairingOffer(metadataStore, {
    offerId: expiredOffer,
    creatorInstallId: 'relay-pair-A',
    secretHash: hashPairingSecret(expiredSecret),
    creatorProof: createPairingProof(expiredSecret, {
      offerId: expiredOffer,
      role: 'creator',
      installId: 'relay-pair-A'
    }),
    now
  });

  assert.equal(acceptPairingOffer(metadataStore, {
    offerId: expiredOffer,
    peerInstallId: 'relay-pair-B',
    pairingSecret: expiredSecret,
    peerProof: createPairingProof(expiredSecret, {
      offerId: expiredOffer,
      role: 'peer',
      installId: 'relay-pair-B'
    }),
    now: now + TEN_MINUTES + 1
  }), null);
  assert.equal(metadataStore.getPairingOffer(expiredOffer), null);

  const activeSecret = 'pairing-secret-active-0004';
  const activeOffer = 'offer-active-0004';
  createPairingOffer(metadataStore, {
    offerId: activeOffer,
    creatorInstallId: 'relay-pair-A',
    secretHash: hashPairingSecret(activeSecret),
    creatorProof: createPairingProof(activeSecret, {
      offerId: activeOffer,
      role: 'creator',
      installId: 'relay-pair-A'
    }),
    now
  });

  assert.equal(acceptPairingOffer(metadataStore, {
    offerId: activeOffer,
    peerInstallId: 'relay-pair-B',
    pairingSecret: 'wrong-secret',
    peerProof: 'wrong-proof',
    now: now + 1
  }), null);

  acceptPairingOffer(metadataStore, {
    offerId: activeOffer,
    peerInstallId: 'relay-pair-B',
    pairingSecret: activeSecret,
    peerProof: createPairingProof(activeSecret, {
      offerId: activeOffer,
      role: 'peer',
      installId: 'relay-pair-B'
    }),
    now: now + 2
  });
  assert.ok(confirmPairingOffer(metadataStore, {
    offerId: activeOffer,
    actorInstallId: 'relay-pair-A',
    pairingSecret: activeSecret,
    now: now + 3
  }));
  assert.equal(confirmPairingOffer(metadataStore, {
    offerId: activeOffer,
    actorInstallId: 'relay-pair-A',
    pairingSecret: activeSecret,
    now: now + 4
  }), null);
});

test('revoked creator or peer installation cannot silently re-pair', async t => {
  const { metadataStore } = await storeFixture(t);
  const now = 1_700_000_000_000;
  installation(metadataStore, { installId: 'relay-pair-A', now, revokedAt: now + 1 });
  installation(metadataStore, { installId: 'relay-pair-B', now });

  const secretA = 'pairing-secret-revoked-A-0005';
  assert.equal(createPairingOffer(metadataStore, {
    offerId: 'offer-revoked-A-0005',
    creatorInstallId: 'relay-pair-A',
    secretHash: hashPairingSecret(secretA),
    creatorProof: createPairingProof(secretA, {
      offerId: 'offer-revoked-A-0005',
      role: 'creator',
      installId: 'relay-pair-A'
    }),
    now: now + 2
  }), null);

  installation(metadataStore, { installId: 'relay-pair-A', now: now + 3, revokedAt: null });
  installation(metadataStore, { installId: 'relay-pair-B', now: now + 3, revokedAt: now + 4 });
  const secretB = 'pairing-secret-revoked-B-0006';
  createPairingOffer(metadataStore, {
    offerId: 'offer-revoked-B-0006',
    creatorInstallId: 'relay-pair-A',
    secretHash: hashPairingSecret(secretB),
    creatorProof: createPairingProof(secretB, {
      offerId: 'offer-revoked-B-0006',
      role: 'creator',
      installId: 'relay-pair-A'
    }),
    now: now + 3
  });
  assert.equal(acceptPairingOffer(metadataStore, {
    offerId: 'offer-revoked-B-0006',
    peerInstallId: 'relay-pair-B',
    pairingSecret: secretB,
    peerProof: createPairingProof(secretB, {
      offerId: 'offer-revoked-B-0006',
      role: 'peer',
      installId: 'relay-pair-B'
    }),
    now: now + 5
  }), null);
});

test('pairing HTTP flow derives both actors from bearer credentials and activates two directional routes', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-relay-pairing-http-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const metadataStore = openMetadataStore(join(dir, 'metadata.sqlite3'));
  const credentialA = issueInstallCredential();
  const credentialB = issueInstallCredential();
  const now = Date.now();
  installation(metadataStore, {
    installId: 'relay-http-pair-A',
    credentialHash: credentialA.credentialHash,
    now
  });
  installation(metadataStore, {
    installId: 'relay-http-pair-B',
    credentialHash: credentialB.credentialHash,
    now
  });
  metadataStore.close();

  const base = await startServer(t, 26015, dir);
  const pairingSecret = 'pairing-secret-http-0007';
  const offerId = 'offer-http-0007';

  const createResponse = await fetch(`${base}/v1/pairing/offers`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${credentialA.credential}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify({
      offerId,
      creatorInstallId: 'relay-http-pair-B',
      secretHash: hashPairingSecret(pairingSecret),
      creatorProof: createPairingProof(pairingSecret, {
        offerId,
        role: 'creator',
        installId: 'relay-http-pair-A'
      })
    })
  });
  assert.equal(createResponse.status, 201);
  const created = await createResponse.json();
  assert.equal(created.creatorInstallId, 'relay-http-pair-A');
  assert.equal('pairingSecret' in created, false);

  const acceptResponse = await fetch(`${base}/v1/pairing/offers/${offerId}/accept`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${credentialB.credential}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify({
      peerInstallId: 'relay-http-pair-A',
      pairingSecret,
      peerProof: createPairingProof(pairingSecret, {
        offerId,
        role: 'peer',
        installId: 'relay-http-pair-B'
      })
    })
  });
  assert.equal(acceptResponse.status, 200);
  const accepted = await acceptResponse.json();
  assert.equal(accepted.peerInstallId, 'relay-http-pair-B');

  const confirmResponse = await fetch(`${base}/v1/pairing/offers/${offerId}/confirm`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${credentialA.credential}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify({ pairingSecret })
  });
  assert.equal(confirmResponse.status, 200);

  const routesA = await fetch(`${base}/v1/routes`, {
    headers: { authorization: `Bearer ${credentialA.credential}` }
  });
  const routesB = await fetch(`${base}/v1/routes`, {
    headers: { authorization: `Bearer ${credentialB.credential}` }
  });
  assert.equal(routesA.status, 200);
  assert.equal(routesB.status, 200);
  assert.equal((await routesA.json()).routes.length, 1);
  assert.equal((await routesB.json()).routes.length, 1);

  const unauthenticated = await fetch(`${base}/v1/pairing/offers`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ offerId: 'offer-no-auth' })
  });
  assert.equal(unauthenticated.status, 401);
});
