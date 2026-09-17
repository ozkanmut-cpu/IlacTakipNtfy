import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { FCMClient } from '../src/fcm.mjs';
import { PushDispatcher } from '../src/push-dispatcher.mjs';
import { openRelayQueue } from '../src/relay-queue-store.mjs';
import { RelayWakeScheduler } from '../src/relay-wake-scheduler.mjs';

const NOW = 1_800_000_000_000;
const RECIPIENT = 'recipient-B';
const LIMITS = { maxCount: 10_000, maxBytes: 100 * 1024 * 1024 };

function relayMessage(messageId, extra = {}) {
  return {
    messageId,
    routeId: 'route-A-B',
    senderInstallId: 'sender-A',
    recipientInstallId: RECIPIENT,
    senderKeyVersion: 1,
    recipientKeyVersion: 1,
    ciphertext: Buffer.from('opaque-ciphertext-medication-dose-event-marker'),
    receivedAt: NOW,
    expiresAt: NOW + 30 * 24 * 60 * 60 * 1000,
    ...extra
  };
}

async function fixture(t, { send = async () => 'provider-message-id' } = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-relay-wake-'));
  let queue = openRelayQueue(join(dir, 'queue.sqlite3'));
  const providerMessages = [];
  const fcm = new FCMClient({
    credentialPath: '/synthetic/firebase-service-account.json',
    credentialProbe: async () => true,
    sender: async message => {
      providerMessages.push(message);
      return send(message);
    }
  });
  const dispatcher = new PushDispatcher({
    apns: { ready: async () => false, wake: async () => assert.fail('unexpected APNs wake') },
    fcm,
    sleeper: async () => {},
    random: () => 0
  });
  let scheduler = new RelayWakeScheduler({
    queue,
    dispatcher,
    resolveInstall: installId => installId === RECIPIENT
      ? { platform: 'android', pushToken: 'fid-opaque-recipient-target' }
      : null
  });

  t.after(async () => {
    queue.close();
    await rm(dir, { recursive: true, force: true });
  });

  return {
    providerMessages,
    get queue() { return queue; },
    get scheduler() { return scheduler; },
    enqueue(messages) { return queue.enqueueBatch(messages, LIMITS); },
    restart() {
      queue.close();
      queue = openRelayQueue(join(dir, 'queue.sqlite3'));
      scheduler = new RelayWakeScheduler({
        queue,
        dispatcher,
        resolveInstall: installId => installId === RECIPIENT
          ? { platform: 'android', pushToken: 'fid-opaque-recipient-target' }
          : null
      });
    }
  };
}

// Mutation caught: dispatch each due message instead of coalescing by recipient.
test('ten queued messages for one recipient coalesce into one pending wake', async t => {
  const f = await fixture(t);
  f.enqueue(Array.from({ length: 10 }, (_, index) => relayMessage(`message-${index}`)));

  await f.scheduler.runDue(NOW);

  assert.equal(f.providerMessages.length, 1);
  assert.equal(f.queue.listRecipient(RECIPIENT).length, 10);
});

// Mutation caught: delete or roll back queued ciphertext when the push provider rejects a wake.
test('provider failure cannot roll back the durable queue insertion', async t => {
  const f = await fixture(t, {
    send: async () => {
      throw Object.assign(new Error('provider rejected request'), {
        code: 'messaging/authentication-error'
      });
    }
  });
  f.enqueue([relayMessage('durable-on-provider-failure')]);

  await f.scheduler.runDue(NOW);
  f.restart();

  const [persisted] = f.queue.listRecipient(RECIPIENT);
  assert.equal(persisted.messageId, 'durable-on-provider-failure');
  assert.equal(persisted.ciphertext.toString(), 'opaque-ciphertext-medication-dose-event-marker');
  await f.scheduler.runDue(NOW + 119_999);
  assert.equal(f.providerMessages.length, 1);
  await f.scheduler.runDue(NOW + 120_000);
  assert.equal(f.providerMessages.length, 2);
});

// Mutation caught: treat an accepted provider request as delivery and delete the relay message.
test('provider success is not delivery confirmation', async t => {
  const f = await fixture(t);
  f.enqueue([relayMessage('still-awaiting-dosefolk-ack')]);

  await f.scheduler.runDue(NOW);
  f.restart();

  assert.deepEqual(
    f.queue.listRecipient(RECIPIENT).map(row => row.messageId),
    ['still-awaiting-dosefolk-ack']
  );
});

// Mutation caught: use relative backoff, omit a retry, or stop retrying after the 24-hour wake.
test('pending inbox retries at T+0, 2m, 15m, 1h, 6h, 24h, then daily', async t => {
  const f = await fixture(t);
  f.enqueue([relayMessage('scheduled-retries')]);
  const schedule = [
    { due: 0, next: 120_000 },
    { due: 120_000, next: 900_000 },
    { due: 900_000, next: 3_600_000 },
    { due: 3_600_000, next: 21_600_000 },
    { due: 21_600_000, next: 86_400_000 },
    { due: 86_400_000, next: 172_800_000 },
    { due: 172_800_000, next: 259_200_000 },
    { due: 259_200_000, next: 345_600_000 }
  ];

  for (const [index, expected] of schedule.entries()) {
    await f.scheduler.runDue(NOW + expected.due - 1);
    assert.equal(f.providerMessages.length, index);

    await f.scheduler.runDue(NOW + expected.due);
    assert.equal(f.providerMessages.length, index + 1);
    await f.scheduler.runDue(NOW + expected.next - 1);
    assert.equal(f.providerMessages.length, index + 1);
  }
  assert.deepEqual(f.queue.listRecipient(RECIPIENT).map(row => row.messageId), ['scheduled-retries']);
});

// Mutation caught: copy queue metadata, ciphertext, or domain fields into the provider payload.
test('relay wakes expose exactly the generic two-field payload', async t => {
  const f = await fixture(t);
  f.enqueue([relayMessage('private-message-id')]);

  await f.scheduler.runDue(NOW);

  assert.equal(f.providerMessages.length, 1);
  assert.deepEqual(f.providerMessages[0].data, {
    wakeType: 'sync',
    protocolVersion: '1'
  });
  assert.deepEqual(Object.keys(f.providerMessages[0].data).sort(), ['protocolVersion', 'wakeType']);
  const providerBody = JSON.stringify(f.providerMessages[0]);
  for (const forbidden of [
    'private-message-id',
    'route-A-B',
    'sender-A',
    RECIPIENT,
    'ciphertext',
    'medication',
    'dose',
    'event'
  ]) {
    assert.equal(providerBody.includes(forbidden), false, `provider payload leaked ${forbidden}`);
  }
});

// Mutation caught: retain an in-memory wake job after terminal ACKs drain the durable inbox.
test('ACK-drained inbox cancels every future wake attempt', async t => {
  const f = await fixture(t);
  f.enqueue([relayMessage('acked-message')]);
  await f.scheduler.runDue(NOW);
  assert.equal(f.providerMessages.length, 1);

  f.queue.acknowledgeBatch(RECIPIENT, [
    { messageId: 'acked-message', outcome: 'processed' }
  ]);
  await f.scheduler.runDue(NOW + 120_000);
  await f.scheduler.runDue(NOW + 900_000);
  await f.scheduler.runDue(NOW + 86_400_000);
  await f.scheduler.runDue(NOW + 172_800_000);

  assert.deepEqual(f.queue.listRecipient(RECIPIENT), []);
  assert.equal(f.providerMessages.length, 1);
});
