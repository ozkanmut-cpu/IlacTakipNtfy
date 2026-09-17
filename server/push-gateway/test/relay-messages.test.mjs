import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, rename, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import Database from 'better-sqlite3';
import { issueInstallCredential } from '../src/install-auth.mjs';
import { openMetadataStore } from '../src/relay-metadata-store.mjs';
import { openRelayQueue } from '../src/relay-queue-store.mjs';
import { enqueueMessages, readInbox, acknowledgeMessages } from '../src/relay-messages.mjs';

const NOW = 1_800_000_000_000;
const TTL = 2_592_000_000;
const A = { installId: 'sender-A' }, B = { installId: 'recipient-B' }, C = { installId: 'outsider-C' };
const message = (messageId = 'message-1', extra = {}) => ({
  messageId, routeId: 'route-A-B', recipientInstallId: B.installId,
  senderKeyVersion: 1, recipientKeyVersion: 1,
  ciphertext: Buffer.from('synthetic encrypted envelope').toString('base64'), ...extra
});
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-messages-'));
  const metadata = openMetadataStore(join(dir, 'metadata.db'));
  let queue = openRelayQueue(join(dir, 'queue.db'));
  const credentials = {};
  for (const actor of [A, B, C]) {
    const issued = issueInstallCredential();
    credentials[actor.installId] = issued.credential;
    metadata.upsertInstallation({ ...actor, platform: 'android', credentialHash: issued.credentialHash,
      encryptionPublicKey: 'synthetic-public-encryption', signingPublicKey: 'synthetic-public-signing', keyVersion: 1, now: NOW });
  }
  for (const recipient of [B, C]) metadata.insertRoute({ routeId: recipient === B ? 'route-A-B' : 'route-A-C',
    senderInstallId: A.installId, recipientInstallId: recipient.installId, status: 'active', createdAt: NOW });
  t.after(async () => { queue.close(); metadata.close(); await rm(dir, { recursive: true, force: true }); });
  return { dir, metadata, credentials, get queue() { return queue; },
    restart() { queue.close(); queue = openRelayQueue(join(dir, 'queue.db')); },
    send(messages, actor = A, now = NOW) { return enqueueMessages(metadata, queue, actor, { messages }, now); },
    inbox(actor = B, limit = 100, now = NOW) { return readInbox(metadata, queue, actor, limit, now); },
    ack(acks, actor = B) { return acknowledgeMessages(metadata, queue, actor, { acks }); }
  };
}
const fails = (fn, status) => assert.throws(fn, error => error.status === status);

test('enqueue persists ciphertext with server TTL and durable monotonic sequence; identical retry is idempotent', async t => {
  const f = await fixture(t);
  const input = message('message-1', { expiresAt: 1, receivedAt: 1 });
  const first = f.send([input]);
  assert.equal(first.messages[0].relaySeq, 1);
  const row = f.queue.listRecipient(B.installId)[0];
  assert.equal(row.receivedAt, NOW);
  assert.equal(row.expiresAt, 1_802_592_000_000);
  assert.equal(row.ciphertext.toString(), 'synthetic encrypted envelope');
  f.restart();
  assert.equal(f.send([input], A, NOW + 100).messages[0].relaySeq, 1);
  assert.equal(f.queue.listRecipient(B.installId).length, 1);
  assert.equal(f.queue.listRecipient(B.installId)[0].expiresAt, NOW + TTL);
  f.ack([{ messageId: 'message-1', outcome: 'processed' }]);
  f.restart();
  assert.equal(f.send([message('message-2')]).messages[0].relaySeq, 2);
});

test('route, actor, recipient and key version validation fails closed with no partial enqueue', async t => {
  const f = await fixture(t);
  for (const bad of [message('bad', { routeId: 'unknown' }), message('bad', { recipientInstallId: C.installId }),
    message('bad', { senderKeyVersion: 2 }), message('bad', { recipientKeyVersion: 2 })]) {
    assert.throws(() => f.send([message(), bad]));
    assert.equal(f.inbox().messages.length, 0);
  }
  fails(() => f.send([message()], B), 403);
  fails(() => f.send([message()], null), 401);
  f.metadata.revokeRoute('route-A-B', NOW);
  fails(() => f.send([message()]), 403);
});

test('revoked sender and recipient are rechecked, including stale authenticated objects', async t => {
  const f = await fixture(t);
  for (const actor of [A, B]) {
    const current = f.metadata.getInstallation(actor.installId);
    f.metadata.upsertInstallation({ ...current, revokedAt: NOW, now: NOW });
    assert.throws(() => f.send([message()]));
    fails(() => f.inbox(actor), 401);
    fails(() => f.ack([], actor), 401);
    f.metadata.upsertInstallation({ ...current, revokedAt: null, now: NOW });
  }
});

test('strict envelope rejects plaintext fields, sender spoofing, malformed base64 and oversized ciphertext', async t => {
  const f = await fixture(t);
  for (const extra of [{ eventType: 'dose' }, { medication: 'private' }, { payload: {} }, { note: 'private' },
    { senderInstallId: C.installId }, { ciphertext: { medication: 'private' } }, { ciphertext: 'not base64!' },
    { ciphertext: '' }, { messageId: '../bad' }, { recipientKeyVersion: '1' }]) fails(() => f.send([message('bad', extra)]), 400);
  fails(() => f.send([message('big', { ciphertext: Buffer.alloc(524289).toString('base64') })]), 413);
  f.send([message('max-size', { ciphertext: Buffer.alloc(524288).toString('base64') })]);
  assert.equal(f.queue.listRecipient(B.installId)[0].ciphertext.length, 524288);
});

test('duplicate IDs cannot overwrite or expose another message and mixed conflicts roll back the batch', async t => {
  const f = await fixture(t);
  f.send([message()]);
  fails(() => f.send([message('fresh'), message('message-1', { ciphertext: 'YWJj' })]), 409);
  fails(() => f.send([message('message-1', { routeId: 'route-A-C', recipientInstallId: C.installId })]), 409);
  assert.equal(f.inbox().messages.length, 1);
  assert.equal(f.inbox(C).messages.length, 0);
  assert.equal(f.send([message('after-conflict')]).messages[0].relaySeq, 2);
});

test('batch count and shape are bounded; duplicates within a batch insert once', async t => {
  const f = await fixture(t);
  for (const messages of [[], null, {}, Array.from({ length: 101 }, (_, i) => message(`m-${i}`))]) fails(() => f.send(messages), 400);
  f.send([message(), message()]);
  assert.equal(f.inbox().messages.length, 1);
});

test('inbox is non-destructive, recipient scoped, count/serialized-byte bounded and filters exact TTL', async t => {
  const f = await fixture(t);
  f.send(Array.from({ length: 100 }, (_, i) => message(`small-${i}`)));
  f.send([message('small-100')]);
  assert.deepEqual(f.inbox(), f.inbox());
  assert.equal(f.inbox().messages.length, 100);
  assert.equal(f.inbox(B, 1).messages[0].messageId, 'small-0');
  assert.equal(f.inbox(B, 999).messages.length, 100);
  assert.equal(f.inbox(C).messages.length, 0);
  for (const limit of [0, -1, 1.5, NaN]) fails(() => f.inbox(B, limit), 400);
  assert.equal(f.inbox(B, 100, NOW + TTL).messages.length, 0);
  const large = Buffer.alloc(524288, 7).toString('base64');
  f.send([message('large-1', { ciphertext: large }), message('large-2', { ciphertext: large })], A, NOW + TTL);
  const inbox = f.inbox(B, 100, NOW + TTL);
  assert.equal(inbox.messages.length, 1);
  assert.equal(inbox.messages[0].ciphertext, large);
  assert.ok(Buffer.byteLength(JSON.stringify(inbox)) <= 1048576);
});

test('all terminal ACK outcomes delete ciphertext durably and repeated ACK succeeds without tombstones', async t => {
  const f = await fixture(t);
  for (const outcome of ['processed', 'duplicate', 'rejected']) {
    f.send([message(outcome)]);
    f.ack([{ messageId: outcome, outcome }]);
    f.ack([{ messageId: outcome, outcome }]);
  }
  f.restart();
  assert.equal(f.queue.listRecipient(B.installId).length, 0);
});

test('ACK ownership, outcomes and detailed rejection fields are validated atomically', async t => {
  const f = await fixture(t);
  f.send([message(), message('for-C', { routeId: 'route-A-C', recipientInstallId: C.installId })]);
  fails(() => f.ack([{ messageId: 'message-1', outcome: 'processed' }], C), 403);
  fails(() => f.ack([{ messageId: 'message-1', outcome: 'processed' }, { messageId: 'for-C', outcome: 'processed' }]), 403);
  for (const ack of [{ messageId: 'message-1', outcome: 'retry' }, { messageId: 'message-1', outcome: 'rejected', reason: 'health detail' }]) fails(() => f.ack([ack]), 400);
  assert.equal(f.inbox().messages.length, 1);
  fails(() => acknowledgeMessages(f.metadata, f.queue, B, { acks: [], note: 'private' }), 400);
});

test('recipient count and byte quotas reject atomically without evicting old ciphertext', async t => {
  const f = await fixture(t);
  const db = new Database(join(f.dir, 'queue.db'));
  t.after(() => db.close());
  f.send([message('seed')]);
  db.transaction(() => {
    const insert = db.prepare(`INSERT INTO relay_messages SELECT ?, ?, route_id, sender_install_id, recipient_install_id,
      sender_key_version, recipient_key_version, ciphertext, received_at, expires_at, next_wake_at, wake_attempts
      FROM relay_messages WHERE message_id = 'seed'`);
    for (let i = 2; i <= 10000; i++) insert.run(`seed-${i}`, i);
    db.prepare('UPDATE recipient_sequences SET next_seq = 10001').run();
  })();
  assert.equal(f.send([message('seed')]).messages[0].relaySeq, 1);
  fails(() => f.send([message('over-count')]), 429);
  assert.equal(db.prepare('SELECT count(*) AS n FROM relay_messages').get().n, 10000);
  db.prepare("DELETE FROM relay_messages WHERE message_id != 'seed'").run();
  db.prepare("UPDATE relay_messages SET ciphertext = zeroblob(104857599) WHERE message_id = 'seed'").run();
  fails(() => f.send([message('one-byte', { ciphertext: 'YQ==' }), message('two-byte', { ciphertext: 'Yg==' })]), 429);
  assert.equal(db.prepare('SELECT count(*) AS n FROM relay_messages').get().n, 1);
  f.send([message('one-byte', { ciphertext: 'YQ==' })]);
  assert.equal(db.prepare('SELECT sum(length(ciphertext)) AS n FROM relay_messages').get().n, 104857600);
});

test('HTTP endpoints enforce bearer identities, body limits and durable inbox/ACK contract', async t => {
  const f = await fixture(t);
  const child = spawn(process.execPath, ['src/server.mjs'], { cwd: new URL('..', import.meta.url), env: {
    ...process.env, HOST: '127.0.0.1', PORT: '26026', DATA_FILE: join(f.dir, 'legacy.json'),
    RELAY_METADATA_DB: join(f.dir, 'metadata.db'), RELAY_QUEUE_DB: join(f.dir, 'queue.db'),
    INSTALL_HMAC_KEY: 'test-legacy-key', INTERNAL_WAKE_SECRET: 'test-internal-key',
    APNS_TEAM_ID: '', APNS_KEY_ID: '', APNS_KEY_PATH: '', NTFY_AUTH_FILE: ''
  }, stdio: ['ignore', 'pipe', 'pipe'] });
  t.after(async () => { if (child.exitCode === null) { child.kill(); await once(child, 'exit'); } });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('startup timeout')), 5000);
    child.stdout.on('data', data => { if (data.toString().includes('listening')) { clearTimeout(timer); resolve(); } });
    child.once('exit', () => { clearTimeout(timer); reject(new Error('startup failure')); });
  });
  const request = (path, actor, body) => fetch(`http://127.0.0.1:26026${path}`, {
    method: body === undefined ? 'GET' : 'POST', headers: {
      ...(actor ? { authorization: `Bearer ${f.credentials[actor.installId]}` } : {}), 'content-type': 'application/json'
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
  assert.equal((await request('/v1/messages', null, { messages: [message()] })).status, 401);
  assert.equal((await request('/v1/messages', B, { messages: [message()] })).status, 403);
  assert.equal((await request('/v1/messages', A, { messages: [message()], senderInstallId: C.installId })).status, 400);
  assert.equal((await request('/v1/messages', A, { messages: [message('http-large', { ciphertext: Buffer.alloc(524288).toString('base64') })] })).status, 201);
  const inboxResponse = await request('/v1/inbox?limit=100', B);
  assert.equal(inboxResponse.status, 200);
  const inbox = await inboxResponse.json();
  assert.equal(inbox.messages[0].senderInstallId, A.installId);
  assert.equal(inbox.messages[0].expiresAt - inbox.messages[0].receivedAt, TTL);
  assert.equal((await request('/v1/inbox', null)).status, 401);
  assert.deepEqual(await (await request('/v1/inbox', C)).json(), { messages: [] });
  assert.equal((await request('/v1/messages/ack', C, { acks: [{ messageId: 'http-large', outcome: 'processed' }] })).status, 403);
  assert.equal((await request('/v1/messages/ack', B, { acks: [{ messageId: 'http-large', outcome: 'processed' }] })).status, 200);
  assert.deepEqual(await (await request('/v1/inbox', B)).json(), { messages: [] });
  assert.equal((await request('/v1/messages', A, { messages: [], padding: 'x'.repeat(1048576) })).status, 413);
  // A database outage must remain retryable, not be reported as bad client input.
  const metadataPath = join(f.dir, 'metadata.db');
  const savedPath = join(f.dir, 'saved-metadata.db');
  await rename(metadataPath, savedPath);
  await mkdir(metadataPath);
  try {
    const unavailable = await request('/v1/inbox', B);
    assert.equal(unavailable.status, 503);
    assert.deepEqual(await unavailable.json(), { error: 'relay_unavailable' });
  } finally {
    await rm(metadataPath, { recursive: true });
    await rename(savedPath, metadataPath);
  }
});
