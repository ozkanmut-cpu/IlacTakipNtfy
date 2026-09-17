import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { FCMClient } from '../src/fcm.mjs';
import { issueInstallCredential } from '../src/install-auth.mjs';
import { PushDispatcher } from '../src/push-dispatcher.mjs';
import { openMetadataStore } from '../src/relay-metadata-store.mjs';
import { openRelayQueue } from '../src/relay-queue-store.mjs';
import { RelayWakeScheduler } from '../src/relay-wake-scheduler.mjs';

const NOW = 1_800_000_000_000;
const RECIPIENT = 'recipient-B';
const LIMITS = { maxCount: 10_000, maxBytes: 100 * 1024 * 1024 };

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

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

async function fixture(t, {
  send = async () => 'provider-message-id',
  resolveInstall = installId => installId === RECIPIENT
    ? { platform: 'android', pushToken: 'fid-opaque-recipient-target' }
    : null
} = {}) {
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
    resolveInstall
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
        resolveInstall
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

// Mutation caught: advance rows enqueued after a due recipient was selected for dispatch.
test('an in-flight wake leaves newly enqueued recipient work immediately due', async t => {
  const dispatchStarted = deferred();
  const releaseDispatch = deferred();
  const f = await fixture(t, {
    send: async () => {
      dispatchStarted.resolve();
      await releaseDispatch.promise;
      return 'provider-message-id';
    }
  });
  f.enqueue([relayMessage('selected-before-dispatch')]);

  const firstRun = f.scheduler.runDue(NOW);
  await dispatchStarted.promise;
  f.enqueue([relayMessage('enqueued-during-dispatch', { receivedAt: NOW + 1 })]);
  releaseDispatch.resolve();
  await firstRun;

  const rows = f.queue.listRecipient(RECIPIENT);
  assert.deepEqual(rows.map(row => ({ id: row.messageId, attempts: row.wakeAttempts, next: row.nextWakeAt })), [
    { id: 'selected-before-dispatch', attempts: 1, next: NOW + 120_000 },
    { id: 'enqueued-during-dispatch', attempts: 0, next: null }
  ]);
  await f.scheduler.runDue(NOW + 1);
  assert.equal(f.providerMessages.length, 2);
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

// Mutation caught: dispatch a recipient captured in a due batch after ACK has drained its inbox.
test('ACK draining a later selected recipient prevents its stale in-batch wake', async t => {
  const firstDispatchStarted = deferred();
  const releaseFirstDispatch = deferred();
  const f = await fixture(t, {
    resolveInstall: installId => ({
      platform: 'android',
      pushToken: installId === 'recipient-A' ? 'fid-recipient-a' : 'fid-recipient-b'
    }),
    send: async message => {
      if (message.fid === 'fid-recipient-a') {
        firstDispatchStarted.resolve();
        await releaseFirstDispatch.promise;
      }
      return 'provider-message-id';
    }
  });
  f.enqueue([
    relayMessage('message-for-A', { recipientInstallId: 'recipient-A' }),
    relayMessage('message-for-B')
  ]);

  const run = f.scheduler.runDue(NOW);
  await firstDispatchStarted.promise;
  f.queue.acknowledgeBatch(RECIPIENT, [
    { messageId: 'message-for-B', outcome: 'processed' }
  ]);
  releaseFirstDispatch.resolve();
  await run;

  assert.deepEqual(f.providerMessages.map(message => message.fid), ['fid-recipient-a']);
  assert.deepEqual(f.queue.listRecipient(RECIPIENT), []);
});

// Mutation caught: await provider availability before returning 201, or omit the post-commit wake trigger.
test('HTTP enqueue remains durable while an unavailable provider schedules a retry', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-relay-wake-http-'));
  const metadataPath = join(dir, 'metadata.sqlite3');
  const queuePath = join(dir, 'queue.sqlite3');
  const dataFile = join(dir, 'registrations.json');
  const metadata = openMetadataStore(metadataPath);
  const sender = issueInstallCredential();
  const recipient = issueInstallCredential();
  metadata.upsertInstallation({
    installId: 'sender-http', platform: 'android', credentialHash: sender.credentialHash,
    encryptionPublicKey: 'synthetic-encryption-key', signingPublicKey: 'synthetic-signing-key',
    keyVersion: 1, now: NOW
  });
  metadata.upsertInstallation({
    installId: 'recipient-http', platform: 'android', credentialHash: recipient.credentialHash,
    encryptionPublicKey: 'synthetic-encryption-key', signingPublicKey: 'synthetic-signing-key',
    keyVersion: 1, now: NOW
  });
  metadata.insertRoute({
    routeId: 'route-http', senderInstallId: 'sender-http', recipientInstallId: 'recipient-http',
    status: 'active', createdAt: NOW
  });
  metadata.close();
  await writeFile(dataFile, JSON.stringify({
    installs: {
      'recipient-http': {
        platform: 'android',
        pushToken: 'fid-unavailable-provider',
        subscriptions: []
      }
    },
    enrollments: {},
    topicBindings: {}
  }));

  const child = spawn(process.execPath, ['src/server.mjs'], {
    cwd: new URL('..', import.meta.url),
    env: {
      ...process.env,
      HOST: '127.0.0.1',
      PORT: '26038',
      DATA_FILE: dataFile,
      RELAY_METADATA_DB: metadataPath,
      RELAY_QUEUE_DB: queuePath,
      INSTALL_HMAC_KEY: 'test-legacy-key',
      INTERNAL_WAKE_SECRET: 'test-internal-key',
      APNS_TEAM_ID: '',
      APNS_KEY_ID: '',
      APNS_KEY_PATH: '',
      GOOGLE_APPLICATION_CREDENTIALS: '',
      NTFY_AUTH_FILE: ''
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  t.after(async () => {
    if (child.exitCode === null) {
      child.kill();
      await once(child, 'exit');
    }
    await rm(dir, { recursive: true, force: true });
  });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('startup timeout')), 5000);
    child.stdout.on('data', data => {
      if (data.toString().includes('listening')) {
        clearTimeout(timer);
        resolve();
      }
    });
    child.once('exit', () => {
      clearTimeout(timer);
      reject(new Error('startup failure'));
    });
  });

  const response = await fetch('http://127.0.0.1:26038/v1/messages', {
    method: 'POST',
    headers: {
      authorization: `Bearer ${sender.credential}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify({
      messages: [{
        messageId: 'http-provider-outage',
        routeId: 'route-http',
        recipientInstallId: 'recipient-http',
        senderKeyVersion: 1,
        recipientKeyVersion: 1,
        ciphertext: Buffer.from('synthetic encrypted envelope').toString('base64')
      }]
    })
  });
  assert.equal(response.status, 201);

  let queued;
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const queue = openRelayQueue(queuePath);
    [queued] = queue.listRecipient('recipient-http');
    queue.close();
    if (queued?.wakeAttempts === 1) break;
    await new Promise(resolve => setTimeout(resolve, 20));
  }

  assert.equal(queued.messageId, 'http-provider-outage');
  assert.equal(queued.ciphertext.toString(), 'synthetic encrypted envelope');
  assert.equal(queued.wakeAttempts, 1);
  assert.equal(queued.nextWakeAt, queued.receivedAt + 120_000);
});
