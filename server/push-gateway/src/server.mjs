import http from 'node:http';
import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { APNsClient } from './apns.mjs';
import { loadGatewaySecrets } from './local-secrets.mjs';
import { NtfyAuthManager } from './ntfy-auth.mjs';
import { buildProvisioningCredentials } from './provisioning-credentials.mjs';
import { selectWakeTargets } from './wake-targets.mjs';

const cfg = {
  host: process.env.HOST || '127.0.0.1',
  port: Number(process.env.PORT || 2587),
  dataFile: process.env.DATA_FILE || '/data/registrations.json',
  installHmacKey: process.env.INSTALL_HMAC_KEY || '',
  internalSecret: process.env.INTERNAL_WAKE_SECRET || '',
  bundleId: process.env.APNS_BUNDLE_ID || 'com.ozkanmut.dosefolk'
};
Object.assign(cfg, await loadGatewaySecrets(cfg));

const ntfyAuth = new NtfyAuthManager();

const apns = new APNsClient({
  teamId: process.env.APNS_TEAM_ID || '',
  keyId: process.env.APNS_KEY_ID || '',
  keyPath: process.env.APNS_KEY_PATH || '',
  bundleId: cfg.bundleId
});

async function loadStore() {
  try {
    const store = JSON.parse(await readFile(cfg.dataFile, 'utf8'));
    store.installs ||= {};
    store.enrollments ||= {};
    store.topicBindings ||= {};
    return store;
  } catch (error) {
    if (error.code === 'ENOENT') return { installs: {}, enrollments: {}, topicBindings: {} };
    throw error;
  }
}
async function saveStore(store) {
  await mkdir(dirname(cfg.dataFile), { recursive: true });
  const tmp = `${cfg.dataFile}.tmp`;
  await writeFile(tmp, JSON.stringify(store), { mode: 0o600 });
  await rename(tmp, cfg.dataFile);
}
function safeEqual(a, b) {
  const aa = Buffer.from(a || ''); const bb = Buffer.from(b || '');
  return aa.length === bb.length && timingSafeEqual(aa, bb);
}
function installToken(installId) {
  return createHmac('sha256', cfg.installHmacKey).update(installId).digest('base64url');
}
function ticketHash(ticket) {
  return createHash('sha256').update(ticket).digest('base64url');
}
function enrollmentLink(installId, ticket) {
  return `dosefolk://enroll?installId=${encodeURIComponent(installId)}&ticket=${encodeURIComponent(ticket)}`;
}
function validInstallId(value) {
  return typeof value === 'string' && /^[A-Za-z0-9._-]{8,128}$/.test(value);
}
function hasInternalAccess(req) {
  return safeEqual(String(req.headers['x-internal-secret'] || ''), cfg.internalSecret);
}
function provisioningReady() {
  return Boolean(cfg.installHmacKey && cfg.internalSecret);
}
async function readJson(req) {
  const chunks = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > 65536) throw new Error('body_too_large'); chunks.push(chunk); }
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
}
function json(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data), 'cache-control': 'no-store' });
  res.end(data);
}
async function isReady() {
  return Boolean(provisioningReady() && await apns.ready());
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') return json(res, 200, { ok: true, ready: await isReady() });

    if (req.method === 'POST' && req.url === '/internal/enrollment') {
      if (!provisioningReady()) return json(res, 503, { error: 'not_provisioned' });
      if (!hasInternalAccess(req)) return json(res, 401, { error: 'unauthorized' });
      const body = await readJson(req);
      if (!validInstallId(body.installId)) return json(res, 400, { error: 'invalid_install_id' });
      const ticket = randomBytes(32).toString('base64url');
      const expiresAt = Date.now() + 10 * 60 * 1000;
      const store = await loadStore();
      store.enrollments[ticketHash(ticket)] = { installId: body.installId, expiresAt };
      await saveStore(store);
      return json(res, 200, {
        installId: body.installId,
        ticket,
        expiresAt,
        enrollmentURL: enrollmentLink(body.installId, ticket)
      });
    }

    if (req.method === 'POST' && req.url === '/v1/provision') {
      if (!provisioningReady()) return json(res, 503, { error: 'not_provisioned' });
      const body = await readJson(req);
      if (!validInstallId(body.installId) || typeof body.ticket !== 'string') return json(res, 400, { error: 'invalid_request' });
      const store = await loadStore();
      const hash = ticketHash(body.ticket);
      const enrollment = store.enrollments[hash];
      if (!enrollment || enrollment.installId !== body.installId || enrollment.expiresAt < Date.now()) {
        if (enrollment) { delete store.enrollments[hash]; await saveStore(store); }
        return json(res, 401, { error: 'invalid_or_expired_ticket' });
      }
      let credentials;
      try {
        credentials = await buildProvisioningCredentials({
          installId: body.installId,
          gatewayCredential: installToken(body.installId),
          localTopic: body.localTopic,
          subscriptions: body.subscriptions,
          ntfyAuth,
          requireNtfyToken: body.requireNtfyToken === true
        });
      } catch (error) {
        if (String(error.message || '').startsWith('invalid_')) {
          return json(res, 400, { error: 'invalid_topic_access' });
        }
        if (error.message === 'ntfy_auth_unavailable') {
          return json(res, 503, { error: 'ntfy_auth_unavailable' });
        }
        console.error('ntfy credential provisioning failed');
        return json(res, 503, { error: 'ntfy_provisioning_failed' });
      }
      if (body.requireNtfyToken === true) store.topicBindings[body.installId] = body.localTopic;
      delete store.enrollments[hash];
      await saveStore(store);
      return json(res, 200, credentials);
    }

    if (req.method === 'POST' && req.url === '/internal/provision') {
      if (!provisioningReady()) return json(res, 503, { error: 'not_provisioned' });
      if (!hasInternalAccess(req)) return json(res, 401, { error: 'unauthorized' });
      const body = await readJson(req);
      if (!validInstallId(body.installId)) return json(res, 400, { error: 'invalid_install_id' });
      try {
        const credentials = await buildProvisioningCredentials({
          installId: body.installId,
          gatewayCredential: installToken(body.installId),
          localTopic: body.localTopic,
          subscriptions: body.subscriptions,
          ntfyAuth,
          requireNtfyToken: body.requireNtfyToken === true
        });
        if (body.requireNtfyToken === true) {
          const store = await loadStore();
          store.topicBindings[body.installId] = body.localTopic;
          await saveStore(store);
        }
        return json(res, 200, credentials);
      } catch (error) {
        if (String(error.message || '').startsWith('invalid_')) {
          return json(res, 400, { error: 'invalid_topic_access' });
        }
        if (error.message === 'ntfy_auth_unavailable') {
          return json(res, 503, { error: 'ntfy_auth_unavailable' });
        }
        console.error('ntfy credential provisioning failed');
        return json(res, 503, { error: 'ntfy_provisioning_failed' });
      }
    }

    if (!(await isReady())) return json(res, 503, { error: 'not_provisioned' });

    if (req.method === 'POST' && req.url === '/v1/register') {
      const body = await readJson(req);
      const auth = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '');
      if (!validInstallId(body.installId) || !safeEqual(auth, installToken(body.installId))) return json(res, 401, { error: 'unauthorized' });
      if (!/^[0-9a-f]{64,256}$/i.test(body.deviceToken || '')) return json(res, 400, { error: 'invalid_device_token' });
      const subscriptions = [...new Set((body.subscriptions || []).filter(x => typeof x === 'string' && /^dosefolk-[A-Za-z0-9_-]+$/.test(x)))];
      const store = await loadStore();
      if (ntfyAuth.ready) {
        const boundLocalTopic = store.topicBindings[body.installId];
        if (typeof boundLocalTopic !== 'string' || !boundLocalTopic) {
          return json(res, 409, { error: 'ntfy_reprovision_required' });
        }
        if (body.localTopic !== boundLocalTopic) {
          return json(res, 409, { error: 'ntfy_topic_mismatch' });
        }
        try {
          await ntfyAuth.setAccess(body.installId, boundLocalTopic, body.subscriptions);
        } catch (error) {
          if (String(error.message || '').startsWith('invalid_')) {
            return json(res, 400, { error: 'invalid_topic_access' });
          }
          console.error('ntfy access synchronization failed');
          return json(res, 503, { error: 'ntfy_access_sync_failed' });
        }
      }
      store.installs[body.installId] = {
        deviceToken: body.deviceToken,
        environment: body.environment === 'sandbox' ? 'sandbox' : 'production',
        bundleId: cfg.bundleId,
        subscriptions,
        updatedAt: Date.now()
      };
      await saveStore(store);
      return json(res, 204, {});
    }

    if (req.method === 'POST' && req.url === '/internal/wake') {
      if (!hasInternalAccess(req)) return json(res, 401, { error: 'unauthorized' });
      const body = await readJson(req);
      const store = await loadStore();
      const targets = selectWakeTargets(store.installs, body.topics);
      const results = await Promise.allSettled(targets.map(target => apns.wake(target.deviceToken, target.environment)));
      return json(res, 200, { targeted: targets.length, sent: results.filter(x => x.status === 'fulfilled').length });
    }

    return json(res, 404, { error: 'not_found' });
  } catch (error) {
    console.error(error);
    return json(res, error.message === 'body_too_large' ? 413 : 400, { error: 'bad_request' });
  }
});
server.listen(cfg.port, cfg.host, () => console.log(`dosefolk-push-gateway listening on ${cfg.host}:${cfg.port}`));
