import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import Database from 'better-sqlite3';
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

test('create retry returns only the same unexpired pending transcript', async t => {
  const { metadataStore } = await storeFixture(t);
  const now = 1_700_000_000_000;
  installation(metadataStore, { installId: 'relay-create-A', now });
  installation(metadataStore, { installId: 'relay-create-B', now });
  const pairingSecret = 'pairing-secret-create-retry-0008';
  const offerId = 'offer-create-retry-0008';
  const secretHash = hashPairingSecret(pairingSecret);
  const creatorProof = createPairingProof(pairingSecret, {
    offerId,
    role: 'creator',
    installId: 'relay-create-A'
  });
  const exact = { offerId, creatorInstallId: 'relay-create-A', secretHash, creatorProof };

  const created = createPairingOffer(metadataStore, { ...exact, now });
  const retried = createPairingOffer(metadataStore, { ...exact, now: now + 1 });

  assert.deepEqual(retried, created);
  assert.equal(createPairingOffer(metadataStore, {
    ...exact,
    creatorInstallId: 'relay-create-B',
    now: now + 2
  }), null);
  assert.equal(createPairingOffer(metadataStore, {
    ...exact,
    secretHash: hashPairingSecret('pairing-secret-create-retry-mismatch'),
    now: now + 3
  }), null);
  assert.equal(createPairingOffer(metadataStore, {
    ...exact,
    creatorProof: `${creatorProof.slice(0, -1)}${creatorProof.endsWith('A') ? 'B' : 'A'}`,
    now: now + 4
  }), null);

  assert.ok(acceptPairingOffer(metadataStore, {
    offerId,
    peerInstallId: 'relay-create-B',
    pairingSecret,
    peerProof: createPairingProof(pairingSecret, {
      offerId,
      role: 'peer',
      installId: 'relay-create-B'
    }),
    now: now + 5
  }));
  assert.equal(createPairingOffer(metadataStore, { ...exact, now: now + 6 }), null);
});

test('accept retry returns accepted or confirmed only for the exact authenticated peer transcript', async t => {
  const { metadataStore } = await storeFixture(t);
  const now = 1_700_000_000_000;
  installation(metadataStore, { installId: 'relay-accept-A', now });
  installation(metadataStore, { installId: 'relay-accept-B', now });
  installation(metadataStore, { installId: 'relay-accept-C', now });
  const pairingSecret = 'pairing-secret-accept-retry-0009';
  const offerId = 'offer-accept-retry-0009';
  const creatorProof = createPairingProof(pairingSecret, {
    offerId,
    role: 'creator',
    installId: 'relay-accept-A'
  });
  const peerProof = createPairingProof(pairingSecret, {
    offerId,
    role: 'peer',
    installId: 'relay-accept-B'
  });
  createPairingOffer(metadataStore, {
    offerId,
    creatorInstallId: 'relay-accept-A',
    secretHash: hashPairingSecret(pairingSecret),
    creatorProof,
    now
  });
  const exact = { offerId, peerInstallId: 'relay-accept-B', pairingSecret, peerProof };

  const accepted = acceptPairingOffer(metadataStore, { ...exact, now: now + 1 });
  const acceptedRetry = acceptPairingOffer(metadataStore, { ...exact, now: now + 2 });
  assert.deepEqual(acceptedRetry, accepted);

  assert.equal(acceptPairingOffer(metadataStore, {
    ...exact,
    peerInstallId: 'relay-accept-C',
    peerProof: createPairingProof(pairingSecret, {
      offerId,
      role: 'peer',
      installId: 'relay-accept-C'
    }),
    now: now + 3
  }), null);
  assert.equal(acceptPairingOffer(metadataStore, {
    ...exact,
    pairingSecret: 'pairing-secret-accept-retry-wrong',
    now: now + 4
  }), null);
  assert.equal(acceptPairingOffer(metadataStore, {
    ...exact,
    peerProof: `${peerProof.slice(0, -1)}${peerProof.endsWith('A') ? 'B' : 'A'}`,
    now: now + 5
  }), null);

  assert.ok(confirmPairingOffer(metadataStore, {
    offerId,
    actorInstallId: 'relay-accept-A',
    pairingSecret,
    now: now + 6
  }));
  const confirmedRetry = acceptPairingOffer(metadataStore, { ...exact, now: now + 7 });
  assert.equal(confirmedRetry.status, 'confirmed');
  assert.equal(confirmedRetry.peerInstallId, 'relay-accept-B');
});

test('confirm retry returns the exact confirmed transcript without duplicate routes', async t => {
  const { metadataStore } = await storeFixture(t);
  const now = 1_700_000_000_000;
  installation(metadataStore, { installId: 'relay-confirm-A', now });
  installation(metadataStore, { installId: 'relay-confirm-B', now });
  installation(metadataStore, { installId: 'relay-confirm-C', now });
  const pairingSecret = 'pairing-secret-confirm-retry-0010';
  const offerId = 'offer-confirm-retry-0010';
  createPairingOffer(metadataStore, {
    offerId,
    creatorInstallId: 'relay-confirm-A',
    secretHash: hashPairingSecret(pairingSecret),
    creatorProof: createPairingProof(pairingSecret, {
      offerId,
      role: 'creator',
      installId: 'relay-confirm-A'
    }),
    now
  });
  acceptPairingOffer(metadataStore, {
    offerId,
    peerInstallId: 'relay-confirm-B',
    pairingSecret,
    peerProof: createPairingProof(pairingSecret, {
      offerId,
      role: 'peer',
      installId: 'relay-confirm-B'
    }),
    now: now + 1
  });
  const exact = { offerId, actorInstallId: 'relay-confirm-A', pairingSecret };

  const confirmed = confirmPairingOffer(metadataStore, { ...exact, now: now + 2 });
  const retried = confirmPairingOffer(metadataStore, { ...exact, now: now + 3 });

  assert.deepEqual(retried, confirmed);
  assert.equal(metadataStore.listActiveRoutesForSender('relay-confirm-A').length, 1);
  assert.equal(metadataStore.listActiveRoutesForSender('relay-confirm-B').length, 1);
  assert.equal(confirmPairingOffer(metadataStore, {
    ...exact,
    actorInstallId: 'relay-confirm-C',
    now: now + 4
  }), null);
  assert.equal(confirmPairingOffer(metadataStore, {
    ...exact,
    pairingSecret: 'pairing-secret-confirm-retry-wrong',
    now: now + 5
  }), null);
});

test('confirmed accept and confirm retry proofs expire at the exact offer boundary without changing routes', async t => {
  const { metadataStore } = await storeFixture(t);
  const now = 1_700_000_000_000;
  const expiresAt = now + TEN_MINUTES;
  installation(metadataStore, { installId: 'relay-expiry-A', now });
  installation(metadataStore, { installId: 'relay-expiry-B', now });
  const pairingSecret = 'pairing-secret-confirmed-expiry-0011';
  const offerId = 'offer-confirmed-expiry-0011';
  createPairingOffer(metadataStore, {
    offerId,
    creatorInstallId: 'relay-expiry-A',
    secretHash: hashPairingSecret(pairingSecret),
    creatorProof: createPairingProof(pairingSecret, {
      offerId,
      role: 'creator',
      installId: 'relay-expiry-A'
    }),
    now
  });
  const acceptRetry = {
    offerId,
    peerInstallId: 'relay-expiry-B',
    pairingSecret,
    peerProof: createPairingProof(pairingSecret, {
      offerId,
      role: 'peer',
      installId: 'relay-expiry-B'
    })
  };
  const confirmRetry = { offerId, actorInstallId: 'relay-expiry-A', pairingSecret };
  assert.ok(acceptPairingOffer(metadataStore, { ...acceptRetry, now: now + 1 }));
  assert.ok(confirmPairingOffer(metadataStore, { ...confirmRetry, now: now + 2 }));
  const routesFromA = metadataStore.listActiveRoutesForSender('relay-expiry-A');
  const routesFromB = metadataStore.listActiveRoutesForSender('relay-expiry-B');
  assert.equal(routesFromA.length, 1);
  assert.equal(routesFromB.length, 1);

  assert.equal(acceptPairingOffer(metadataStore, {
    ...acceptRetry,
    now: expiresAt - 1
  }).status, 'confirmed');
  assert.equal(confirmPairingOffer(metadataStore, {
    ...confirmRetry,
    now: expiresAt - 1
  }).status, 'confirmed');
  assert.deepEqual(metadataStore.listActiveRoutesForSender('relay-expiry-A'), routesFromA);
  assert.deepEqual(metadataStore.listActiveRoutesForSender('relay-expiry-B'), routesFromB);

  for (const retryAt of [expiresAt, expiresAt + 1]) {
    assert.equal(acceptPairingOffer(metadataStore, { ...acceptRetry, now: retryAt }), null);
    assert.equal(confirmPairingOffer(metadataStore, { ...confirmRetry, now: retryAt }), null);
    assert.deepEqual(metadataStore.listActiveRoutesForSender('relay-expiry-A'), routesFromA);
    assert.deepEqual(metadataStore.listActiveRoutesForSender('relay-expiry-B'), routesFromB);
    assert.equal(metadataStore.getPairingOffer(offerId).status, 'confirmed');
  }
});

test('malformed confirmed expiry metadata rejects retry proofs without changing metadata or routes', async t => {
  const { metadataFile, metadataStore } = await storeFixture(t);
  const now = 1_700_000_000_000;
  installation(metadataStore, { installId: 'relay-malformed-A', now });
  installation(metadataStore, { installId: 'relay-malformed-B', now });
  const pairingSecret = 'pairing-secret-malformed-expiry-0012';
  const offerId = 'offer-malformed-expiry-0012';
  createPairingOffer(metadataStore, {
    offerId,
    creatorInstallId: 'relay-malformed-A',
    secretHash: hashPairingSecret(pairingSecret),
    creatorProof: createPairingProof(pairingSecret, {
      offerId,
      role: 'creator',
      installId: 'relay-malformed-A'
    }),
    now
  });
  const peerProof = createPairingProof(pairingSecret, {
    offerId,
    role: 'peer',
    installId: 'relay-malformed-B'
  });
  assert.ok(acceptPairingOffer(metadataStore, {
    offerId,
    peerInstallId: 'relay-malformed-B',
    pairingSecret,
    peerProof,
    now: now + 1
  }));
  assert.ok(confirmPairingOffer(metadataStore, {
    offerId,
    actorInstallId: 'relay-malformed-A',
    pairingSecret,
    now: now + 2
  }));
  const routesFromA = metadataStore.listActiveRoutesForSender('relay-malformed-A');
  const routesFromB = metadataStore.listActiveRoutesForSender('relay-malformed-B');
  const corruptionDb = new Database(metadataFile);
  t.after(() => corruptionDb.close());
  const malformedExpiries = [
    { sql: '1e999', matches: value => value === Infinity },
    { sql: "'1e309'", matches: value => value === Infinity || value === '1e309' },
    { sql: '8640000000000001', matches: value => value === 8_640_000_000_000_001 }
  ];

  for (const malformed of malformedExpiries) {
    corruptionDb.prepare(
      `UPDATE pairing_offers SET expires_at = ${malformed.sql} WHERE offer_id = ?`
    ).run(offerId);
    const corruptedOffer = metadataStore.getPairingOffer(offerId);
    assert.ok(malformed.matches(corruptedOffer.expiresAt));

    assert.equal(acceptPairingOffer(metadataStore, {
      offerId,
      peerInstallId: 'relay-malformed-B',
      pairingSecret,
      peerProof,
      now: now + 3
    }), null);
    assert.equal(confirmPairingOffer(metadataStore, {
      offerId,
      actorInstallId: 'relay-malformed-A',
      pairingSecret,
      now: now + 3
    }), null);
    assert.deepEqual(metadataStore.getPairingOffer(offerId), corruptedOffer);
    assert.deepEqual(metadataStore.listActiveRoutesForSender('relay-malformed-A'), routesFromA);
    assert.deepEqual(metadataStore.listActiveRoutesForSender('relay-malformed-B'), routesFromB);
  }
});

test('malformed current timestamps fail before accepted metadata or routes can change', async t => {
  const { metadataStore } = await storeFixture(t);
  const createdAt = 1_700_000_000_000;
  installation(metadataStore, { installId: 'relay-now-A', now: createdAt });
  installation(metadataStore, { installId: 'relay-now-B', now: createdAt });
  const malformedTimes = [
    ['positive-infinity', Infinity],
    ['negative-infinity', -Infinity],
    ['coercible-overflow', '1e309'],
    ['negative-time', -1],
    ['unsafe-integer', Number.MAX_SAFE_INTEGER + 1],
    ['outside-date-range', 8_640_000_000_000_001]
  ];

  for (const [label, malformedNow] of malformedTimes) {
    const offerId = `offer-invalid-now-${label}`;
    const pairingSecret = `pairing-secret-invalid-now-${label}`;
    createPairingOffer(metadataStore, {
      offerId,
      creatorInstallId: 'relay-now-A',
      secretHash: hashPairingSecret(pairingSecret),
      creatorProof: createPairingProof(pairingSecret, {
        offerId,
        role: 'creator',
        installId: 'relay-now-A'
      }),
      now: createdAt
    });
    const peerProof = createPairingProof(pairingSecret, {
      offerId,
      role: 'peer',
      installId: 'relay-now-B'
    });
    assert.ok(acceptPairingOffer(metadataStore, {
      offerId,
      peerInstallId: 'relay-now-B',
      pairingSecret,
      peerProof,
      now: createdAt + 1
    }));
    const acceptedOffer = metadataStore.getPairingOffer(offerId);

    assert.equal(acceptPairingOffer(metadataStore, {
      offerId,
      peerInstallId: 'relay-now-B',
      pairingSecret,
      peerProof,
      now: malformedNow
    }), null);
    assert.equal(confirmPairingOffer(metadataStore, {
      offerId,
      actorInstallId: 'relay-now-A',
      pairingSecret,
      now: malformedNow
    }), null);
    assert.deepEqual(metadataStore.getPairingOffer(offerId), acceptedOffer);
    assert.deepEqual(metadataStore.listActiveRoutesForSender('relay-now-A'), []);
    assert.deepEqual(metadataStore.listActiveRoutesForSender('relay-now-B'), []);
  }
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

test('wrong secret and expired offer fail closed while exact confirmation retry recovers', async t => {
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
  }).status, 'confirmed');
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
