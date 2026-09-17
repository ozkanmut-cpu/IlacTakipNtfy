import { authorizeRoute } from './relay-routes.mjs';

const MAX_CIPHERTEXT = 512 * 1024;
const MAX_BATCH = 100;
const MAX_INBOX_BYTES = 1024 * 1024;
const TTL = 30 * 24 * 60 * 60 * 1000;
export const MAX_RELAY_REQUEST_BYTES = 1024 * 1024;

function fail(status, code) { throw Object.assign(new Error(code), { status }); }
function onlyFields(value, fields) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || Object.keys(value).some(key => !fields.includes(key))) fail(400, 'invalid_envelope');
}
function opaqueId(value) {
  return typeof value === 'string' && /^[A-Za-z0-9._-]{1,128}$/.test(value);
}
function activeActor(metadata, actor) {
  const current = opaqueId(actor?.installId) ? metadata.getInstallation(actor.installId) : null;
  if (!current || current.revokedAt !== null) fail(401, 'unauthorized');
  return current;
}
function boundedBatch(body, field) {
  onlyFields(body, [field]);
  const batch = body[field];
  if (!Array.isArray(batch) || batch.length < 1 || batch.length > MAX_BATCH) fail(400, 'invalid_batch');
  return batch;
}
function decodeCiphertext(value) {
  if (typeof value !== 'string' || !value.length) fail(400, 'invalid_ciphertext');
  if (value.length > 4 * Math.ceil(MAX_CIPHERTEXT / 3)) fail(413, 'ciphertext_too_large');
  const bytes = Buffer.from(value, 'base64');
  if (bytes.toString('base64') !== value) fail(400, 'invalid_ciphertext');
  if (bytes.length > MAX_CIPHERTEXT) fail(413, 'ciphertext_too_large');
  return bytes;
}

export function enqueueMessages(metadata, queue, actor, body, now = Date.now()) {
  const sender = activeActor(metadata, actor);
  const messages = boundedBatch(body, 'messages').map(input => {
    // Client timestamps are tolerated but never trusted, persisted or echoed.
    onlyFields(input, ['messageId', 'routeId', 'recipientInstallId', 'senderKeyVersion',
      'recipientKeyVersion', 'ciphertext', 'receivedAt', 'expiresAt']);
    if (![input.messageId, input.routeId, input.recipientInstallId].every(opaqueId)
      || !Number.isSafeInteger(input.senderKeyVersion) || input.senderKeyVersion < 1
      || !Number.isSafeInteger(input.recipientKeyVersion) || input.recipientKeyVersion < 1) fail(400, 'invalid_envelope');
    const route = authorizeRoute(metadata, input.routeId, sender);
    if (!route || route.recipientInstallId !== input.recipientInstallId) fail(403, 'route_forbidden');
    const recipient = metadata.getInstallation(route.recipientInstallId);
    if (input.senderKeyVersion !== sender.keyVersion || input.recipientKeyVersion !== recipient.keyVersion) fail(409, 'key_version_mismatch');
    return {
      messageId: input.messageId, routeId: route.routeId,
      senderInstallId: sender.installId, recipientInstallId: route.recipientInstallId,
      senderKeyVersion: input.senderKeyVersion, recipientKeyVersion: input.recipientKeyVersion,
      ciphertext: decodeCiphertext(input.ciphertext), receivedAt: now, expiresAt: now + TTL
    };
  });
  return { messages: queue.enqueueBatch(messages, { maxCount: 10000, maxBytes: 100 * 1024 * 1024 }) };
}

export function readInbox(metadata, queue, actor, limit = 100, now = Date.now()) {
  const recipient = activeActor(metadata, actor);
  if (!Number.isSafeInteger(limit) || limit < 1) fail(400, 'invalid_limit');
  const messages = [];
  let bytes = Buffer.byteLength('{"messages":[]}');
  for (const row of queue.pendingRecipient(recipient.installId, now, Math.min(limit, MAX_BATCH))) {
    const { nextWakeAt, wakeAttempts, ...envelope } = row;
    envelope.ciphertext = row.ciphertext.toString('base64');
    const additional = Buffer.byteLength(JSON.stringify(envelope)) + (messages.length ? 1 : 0);
    if (bytes + additional > MAX_INBOX_BYTES) break;
    bytes += additional;
    messages.push(envelope);
  }
  return { messages };
}

export function acknowledgeMessages(metadata, queue, actor, body) {
  const recipient = activeActor(metadata, actor);
  const acks = boundedBatch(body, 'acks');
  for (const ack of acks) {
    onlyFields(ack, ['messageId', 'outcome']);
    if (!opaqueId(ack.messageId) || !['processed', 'duplicate', 'rejected'].includes(ack.outcome)) fail(400, 'invalid_ack');
  }
  queue.acknowledgeBatch(recipient.installId, acks);
  return { ok: true };
}
