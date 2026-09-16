import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { openMetadataStore } from '../src/relay-metadata-store.mjs';
import { openRelayQueue } from '../src/relay-queue-store.mjs';

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'dosefolk-relay-storage-'));
  return {
    root,
    metadataPath: join(root, 'metadata', 'relay-metadata.sqlite3'),
    queuePath: join(root, 'queue', 'relay-queue.sqlite3'),
    cleanup() { rmSync(root, { recursive: true, force: true }); }
  };
}

function installation(overrides = {}) {
  return {
    installId: 'install-a',
    platform: 'android',
    credentialHash: 'hash-a',
    encryptionPublicKey: 'enc-a',
    signingPublicKey: 'sign-a',
    keyVersion: 1,
    now: 1_700_000_000_000,
    ...overrides
  };
}

function message(messageId, recipientInstallId = 'install-b', overrides = {}) {
  return {
    messageId,
    routeId: 'route-a-b',
    senderInstallId: 'install-a',
    recipientInstallId,
    senderKeyVersion: 1,
    recipientKeyVersion: 1,
    ciphertext: Buffer.from(`cipher:${messageId}`),
    receivedAt: 1_700_000_000_000,
    expiresAt: 1_702_592_000_000,
    ...overrides
  };
}

test('metadata and ciphertext queue use physically separate sqlite files', () => {
  const f = fixture();
  let metadata;
  let queue;
  try {
    metadata = openMetadataStore(f.metadataPath);
    queue = openRelayQueue(f.queuePath);
    metadata.upsertInstallation(installation());
    queue.enqueue(message('msg-1'));

    assert.notEqual(metadata.path, queue.path);
    assert.equal(existsSync(f.metadataPath), true);
    assert.equal(existsSync(f.queuePath), true);
    assert.equal(metadata.getInstallation('install-a').platform, 'android');
    assert.equal(queue.listRecipient('install-b').length, 1);
  } finally {
    metadata?.close();
    queue?.close();
    f.cleanup();
  }
});

test('metadata schema rejects unsupported platforms', () => {
  const f = fixture();
  let metadata;
  try {
    metadata = openMetadataStore(f.metadataPath);
    assert.throws(
      () => metadata.upsertInstallation(installation({ platform: 'desktop' })),
      /CHECK constraint failed/i
    );
  } finally {
    metadata?.close();
    f.cleanup();
  }
});

test('duplicate message id is idempotent and keeps the original recipient sequence', () => {
  const f = fixture();
  let queue;
  try {
    queue = openRelayQueue(f.queuePath);
    const first = queue.enqueue(message('msg-1'));
    const duplicate = queue.enqueue(message('msg-1'));

    assert.deepEqual(first, { inserted: true, relaySeq: 1 });
    assert.deepEqual(duplicate, { inserted: false, relaySeq: 1 });
    assert.equal(queue.listRecipient('install-b').length, 1);
  } finally {
    queue?.close();
    f.cleanup();
  }
});

test('recipient sequence is monotonic, independent per recipient, and durable across restart', () => {
  const f = fixture();
  let queue;
  try {
    queue = openRelayQueue(f.queuePath);
    assert.equal(queue.enqueue(message('msg-b1')).relaySeq, 1);
    assert.equal(queue.enqueue(message('msg-b2')).relaySeq, 2);
    assert.equal(queue.enqueue(message('msg-c1', 'install-c')).relaySeq, 1);
    queue.deleteMessage('install-b', 'msg-b1');
    queue.deleteMessage('install-b', 'msg-b2');
    queue.close();
    queue = null;

    queue = openRelayQueue(f.queuePath);
    assert.equal(queue.enqueue(message('msg-b3')).relaySeq, 3);
    assert.equal(queue.enqueue(message('msg-c2', 'install-c')).relaySeq, 2);
  } finally {
    queue?.close();
    f.cleanup();
  }
});

test('metadata survives restart', () => {
  const f = fixture();
  let metadata;
  try {
    metadata = openMetadataStore(f.metadataPath);
    metadata.upsertInstallation(installation());
    metadata.insertRoute({
      routeId: 'route-a-b',
      senderInstallId: 'install-a',
      recipientInstallId: 'install-b',
      status: 'active',
      createdAt: 1_700_000_000_000
    });
    metadata.close();
    metadata = null;

    metadata = openMetadataStore(f.metadataPath);
    assert.equal(metadata.getInstallation('install-a').signingPublicKey, 'sign-a');
    assert.deepEqual(metadata.getRoute('route-a-b'), {
      routeId: 'route-a-b',
      senderInstallId: 'install-a',
      recipientInstallId: 'install-b',
      status: 'active',
      createdAt: 1_700_000_000_000,
      revokedAt: null
    });
  } finally {
    metadata?.close();
    f.cleanup();
  }
});
