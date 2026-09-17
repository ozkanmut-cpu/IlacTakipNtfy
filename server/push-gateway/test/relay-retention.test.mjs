import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { openRelayQueue } from '../src/relay-queue-store.mjs';
import { createInstallRateLimiter, purgeExpired } from '../src/relay-retention.mjs';

const NOW = 1_800_000_000_000;
const row = (messageId, recipientInstallId, expiresAt, bytes = 4) => ({
  messageId, routeId: `route-${recipientInstallId}`, senderInstallId: 'sender-A', recipientInstallId,
  senderKeyVersion: 1, recipientKeyVersion: 1, ciphertext: Buffer.alloc(bytes, 7),
  receivedAt: NOW - 1000, expiresAt
});

async function queueFixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-retention-'));
  const queue = openRelayQueue(join(dir, 'queue.sqlite3'));
  t.after(async () => { queue.close(); await rm(dir, { recursive: true, force: true }); });
  return queue;
}

test('expired ciphertext is unreadable at the exact TTL boundary before physical cleanup', async t => {
  const queue = await queueFixture(t);
  queue.enqueue(row('expired', 'recipient-B', NOW));
  queue.enqueue(row('future', 'recipient-B', NOW + 1));

  assert.deepEqual([...queue.pendingRecipient('recipient-B', NOW, 100)].map(x => x.messageId), ['future']);
  assert.deepEqual(queue.listRecipient('recipient-B').map(x => x.messageId), ['expired', 'future']);
});

test('purgeExpired deletes every row at or before now and preserves newer ciphertext', async t => {
  const queue = await queueFixture(t);
  queue.enqueue(row('past', 'recipient-B', NOW - 1));
  queue.enqueue(row('boundary', 'recipient-B', NOW));
  queue.enqueue(row('future', 'recipient-B', NOW + 1));
  queue.enqueue(row('other-recipient', 'recipient-C', NOW - 20));

  assert.deepEqual(purgeExpired(queue, NOW), { expiredCount: 3, expiredBytes: 12 });
  assert.deepEqual(queue.listRecipient('recipient-B').map(x => x.messageId), ['future']);
  assert.deepEqual(queue.listRecipient('recipient-C'), []);
  assert.deepEqual(purgeExpired(queue, NOW), { expiredCount: 0, expiredBytes: 0 });
});

test('quota rejection leaves existing ciphertext and sequence state untouched', async t => {
  const queue = await queueFixture(t);
  queue.enqueueBatch([row('existing', 'recipient-B', NOW + 1000, 4)], { maxCount: 1, maxBytes: 4 });

  assert.throws(() => queue.enqueueBatch([row('rejected', 'recipient-B', NOW + 1000, 1)],
    { maxCount: 1, maxBytes: 4 }), error => error.status === 429);
  assert.deepEqual(queue.listRecipient('recipient-B').map(x => ({ id: x.messageId, seq: x.relaySeq })),
    [{ id: 'existing', seq: 1 }]);

  queue.deleteMessage('recipient-B', 'existing');
  assert.equal(queue.enqueueBatch([row('after', 'recipient-B', NOW + 1000, 4)],
    { maxCount: 1, maxBytes: 4 })[0].relaySeq, 2);
});

test('rate limiter permits 120 requests per fixed minute and resets at the boundary', () => {
  const limiter = createInstallRateLimiter({ limit: 120, windowMs: 60_000, maxEntries: 10 });
  for (let i = 0; i < 120; i++) assert.equal(limiter.consume('install-A', NOW).allowed, true);
  const blocked = limiter.consume('install-A', NOW + 59_999);
  assert.deepEqual(blocked, { allowed: false, retryAfterSeconds: 1 });
  assert.equal(limiter.consume('install-B', NOW + 59_999).allowed, true);
  assert.equal(limiter.consume('install-A', NOW + 60_000).allowed, true);
});

test('rate limiter bounds memory without resetting an active installation allowance', () => {
  const limiter = createInstallRateLimiter({ limit: 2, windowMs: 60_000, maxEntries: 2 });
  assert.equal(limiter.consume('install-A', NOW).allowed, true);
  assert.equal(limiter.consume('install-B', NOW + 1).allowed, true);
  assert.equal(limiter.consume('install-C', NOW + 2).allowed, false);
  assert.equal(limiter.size, 2);
  assert.equal(limiter.consume('install-A', NOW + 3).allowed, true);
  assert.equal(limiter.consume('install-A', NOW + 4).allowed, false);
  assert.equal(limiter.consume('install-D', NOW + 60_003).allowed, true);
  assert.equal(limiter.size, 1);
});

test('expired ciphertext is removed inside enqueue transaction and no longer consumes quota', async t => {
  const queue = await queueFixture(t);
  queue.enqueueBatch([row('expired', 'recipient-B', NOW, 4)], { maxCount: 1, maxBytes: 4 });
  const inserted = queue.enqueueBatch([{ ...row('fresh', 'recipient-B', NOW + 1000, 4), receivedAt: NOW + 1 }],
    { maxCount: 1, maxBytes: 4 });
  assert.equal(inserted[0].relaySeq, 2);
  assert.deepEqual(queue.listRecipient('recipient-B').map(x => x.messageId), ['fresh']);
});
