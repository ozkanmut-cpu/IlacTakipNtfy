import test from 'node:test';
import assert from 'node:assert/strict';
import { silentWakePayload } from '../src/apns.mjs';

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
