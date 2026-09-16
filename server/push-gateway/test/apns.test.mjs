import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { APNsClient, silentWakePayload } from '../src/apns.mjs';

test('APNs wake payload contains only the background wake signal', () => {
  const payload = silentWakePayload();

  assert.deepEqual(payload, { aps: { 'content-available': 1 } });
  assert.deepEqual(Object.keys(payload), ['aps']);
  assert.deepEqual(Object.keys(payload.aps), ['content-available']);
  assert.equal(JSON.stringify(payload).includes('medication'), false);
  assert.equal(JSON.stringify(payload).includes('dose'), false);
  assert.equal(JSON.stringify(payload).includes('event'), false);
  assert.equal(JSON.stringify(payload).includes('topic'), false);
});

test('APNs readiness requires a readable valid EC private key', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dosefolk-apns-ready-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const common = {
    teamId: 'TEAM123456',
    keyId: 'KEY1234567',
    bundleId: 'com.ozkanmut.dosefolk'
  };

  const missing = new APNsClient({ ...common, keyPath: join(dir, 'missing.p8') });
  assert.equal(await missing.ready(), false);

  const malformedPath = join(dir, 'malformed.p8');
  await writeFile(malformedPath, 'not-a-private-key');
  const malformed = new APNsClient({ ...common, keyPath: malformedPath });
  assert.equal(await malformed.ready(), false);

  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const validPath = join(dir, 'valid.p8');
  await writeFile(validPath, privateKey.export({ type: 'pkcs8', format: 'pem' }));
  const valid = new APNsClient({ ...common, keyPath: validPath });
  assert.equal(await valid.ready(), true);
});
