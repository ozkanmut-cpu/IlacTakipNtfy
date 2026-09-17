import http from 'node:http';
import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { APNsClient } from './apns.mjs';
import { FCMClient } from './fcm.mjs';
import { authenticateInstall, issueInstallCredential } from './install-auth.mjs';
import { loadGatewaySecrets } from './local-secrets.mjs';
import { NtfyAuthManager } from './ntfy-auth.mjs';
import { PushDispatcher } from './push-dispatcher.mjs';
import { validatePushRegistration } from './push-registration.mjs';
import { buildProvisioningCredentials } from './provisioning-credentials.mjs';
import { openMetadataStore } from './relay-metadata-store.mjs';
import { acceptPairingOffer, confirmPairingOffer, createPairingOffer } from './relay-pairing.mjs';
import { listRoutesForSender } from './relay-routes.mjs';
import { clearStoredPushTarget, selectWakeTargets } from './wake-targets.mjs';

const cfg = {
  host: process.env.HOST || '127.0.0.1',
  port: Number(process.env.PORT || 2587),
  dataFile: process.env.DATA_FILE || '/data/registrations.json',
  relayMetadataDb: process.env.RELAY_METADATA_DB || '/data/metadata/relay-metadata.sqlite3',
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
const fcm = new FCMClient();
const pushDispatcher = new PushDispatcher({ apns, fcm });

const pairingCleanupTimer = setInterval(() => {
  const metadataStore = openMetadataStore(cfg.relayMetadataDb);
  try {
    metadataStore.purgeExpiredPairingOffers(Date.now());
  } catch {
    console.error('pairing metadata cleanup failed');
  } finally {
    metadataStore.close();
  }
}, 60_000);
pairingCleanupTimer.unref();

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
function validIdentityKey(value) {
  return typeof value === 'string'
    && value.length >= 8
    && value.length <= 8192
    && value === value.trim()
    && !/[\x00-\x1F\x7F]/.test(value);
}
function nativeProvisionRequest(body) {
  return body?.platform !== undefined
    || body?.encryptionPublicKey !== undefined
    || body?.signingPublicKey !== undefined
    || body?.keyVersion !== undefined;
}
function hasInternalAccess(req) {
  return Boolean(cfg.internalSecret) && safeEqual(String(req.headers['x-internal-secret'] || ''), cfg.internalSecret);
}
function hasInstallAccess(req, installId) {
  const auth = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  return Boolean(cfg.installHmacKey) && validInstallId(installId) && safeEqual(auth, installToken(installId));
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
  if (status === 204) {
    res.writeHead(status, { 'cache-control': 'no-store' });
    return res.end();
  }
  const data = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data), 'cache-control': 'no-store' });
  res.end(data);
}
async function synchronizeBoundAccess(store, installId, subscriptions) {
  if (!ntfyAuth.ready) throw new Error('ntfy_auth_unavailable');
  const boundLocalTopic = store.topicBindings[installId];
  if (typeof boundLocalTopic !== 'string' || !boundLocalTopic) throw new Error('ntfy_reprovision_required');
  await ntfyAuth.setAccess(installId, boundLocalTopic, subscriptions);
  return boundLocalTopic;
}
function provisionNativeInstall(body) {
  if (body.platform !== 'android' && body.platform !== 'ios') return { status: 400, body: { error: 'unsupported_platform' } };
  if (!validIdentityKey(body.encryptionPublicKey) || !validIdentityKey(body.signingPublicKey)) {
    return { status: 400, body: { error: 'invalid_identity_key' } };
  }
  const keyVersion = body.keyVersion === undefined ? 1 : body.keyVersion;
  if (!Number.isSafeInteger(keyVersion) || keyVersion < 1) return { status: 400, body: { error: 'invalid_key_version' } };

  const metadataStore = openMetadataStore(cfg.relayMetadataDb);
  try {
    let installId;
    do {
      installId = `relay-${randomBytes(18).toString('base64url')}`;
    } while (metadataStore.getInstallation(installId));

    const issued = issueInstallCredential();
    const now = Date.now();
    metadataStore.upsertInstallation({
      installId,
      platform: body.platform,
      credentialHash: issued.credentialHash,
      encryptionPublicKey: body.encryptionPublicKey,
      signingPublicKey: body.signingPublicKey,
      keyVersion,
      now
    });
    return { status: 200, body: { installId, credential: issued.credential } };
  } finally {
    metadataStore.close();
  }
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      const providerReadiness = await pushDispatcher.readiness();
      const provisioned = provisioningReady();
      return json(res, 200, {
        ok: true,
        ready: Boolean(provisioned && (providerReadiness.apns || providerReadiness.fcm)),
        provisioningReady: provisioned,
        providers: {
          apns: { ready: providerReadiness.apns },
          fcm: { ready: providerReadiness.fcm }
        }
      });
    }

    if (req.method === 'GET' && req.url === '/v1/routes') {
      const metadataStore = openMetadataStore(cfg.relayMetadataDb);
      try {
        const authenticated = authenticateInstall(String(req.headers.authorization || ''), metadataStore);
        if (!authenticated) return json(res, 401, { error: 'unauthorized' });
        return json(res, 200, { routes: listRoutesForSender(metadataStore, authenticated.installId) });
      } finally {
        metadataStore.close();
      }
    }

    if (req.method === 'POST' && req.url === '/v1/pairing/offers') {
      const metadataStore = openMetadataStore(cfg.relayMetadataDb);
      try {
        const authenticated = authenticateInstall(String(req.headers.authorization || ''), metadataStore);
        if (!authenticated) return json(res, 401, { error: 'unauthorized' });
        const body = await readJson(req);
        const offer = createPairingOffer(metadataStore, {
          offerId: body.offerId,
          creatorInstallId: authenticated.installId,
          secretHash: body.secretHash,
          creatorProof: body.creatorProof,
          now: Date.now()
        });
        if (!offer) return json(res, 409, { error: 'pairing_unavailable' });
        return json(res, 201, {
          offerId: offer.offerId,
          creatorInstallId: offer.creatorInstallId,
          expiresAt: offer.expiresAt,
          status: offer.status
        });
      } finally {
        metadataStore.close();
      }
    }

    const pairingAcceptMatch = req.method === 'POST'
      ? req.url?.match(/^\/v1\/pairing\/offers\/([A-Za-z0-9._-]{8,128})\/accept$/)
      : null;
    if (pairingAcceptMatch) {
      const metadataStore = openMetadataStore(cfg.relayMetadataDb);
      try {
        const authenticated = authenticateInstall(String(req.headers.authorization || ''), metadataStore);
        if (!authenticated) return json(res, 401, { error: 'unauthorized' });
        const body = await readJson(req);
        const offer = acceptPairingOffer(metadataStore, {
          offerId: pairingAcceptMatch[1],
          peerInstallId: authenticated.installId,
          pairingSecret: body.pairingSecret,
          peerProof: body.peerProof,
          now: Date.now()
        });
        if (!offer) return json(res, 409, { error: 'pairing_unavailable' });
        return json(res, 200, {
          offerId: offer.offerId,
          creatorInstallId: offer.creatorInstallId,
          peerInstallId: offer.peerInstallId,
          expiresAt: offer.expiresAt,
          status: offer.status
        });
      } finally {
        metadataStore.close();
      }
    }

    const pairingConfirmMatch = req.method === 'POST'
      ? req.url?.match(/^\/v1\/pairing\/offers\/([A-Za-z0-9._-]{8,128})\/confirm$/)
      : null;
    if (pairingConfirmMatch) {
      const metadataStore = openMetadataStore(cfg.relayMetadataDb);
      try {
        const authenticated = authenticateInstall(String(req.headers.authorization || ''), metadataStore);
        if (!authenticated) return json(res, 401, { error: 'unauthorized' });
        const body = await readJson(req);
        const offer = confirmPairingOffer(metadataStore, {
          offerId: pairingConfirmMatch[1],
          actorInstallId: authenticated.installId,
          pairingSecret: body.pairingSecret,
          now: Date.now()
        });
        if (!offer) return json(res, 409, { error: 'pairing_unavailable' });
        return json(res, 200, {
          offerId: offer.offerId,
          status: offer.status
        });
      } finally {
        metadataStore.close();
      }
    }

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
      const body = await readJson(req);
      if (nativeProvisionRequest(body)) {
        const result = provisionNativeInstall(body);
        return json(res, result.status, result.body);
      }

      if (!provisioningReady()) return json(res, 503, { error: 'not_provisioned' });
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
        if (String(error.message || '').startsWith('invalid_')) return json(res, 400, { error: 'invalid_topic_access' });
        if (error.message === 'ntfy_auth_unavailable') return json(res, 503, { error: 'ntfy_auth_unavailable' });
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
        if (String(error.message || '').startsWith('invalid_')) return json(res, 400, { error: 'invalid_topic_access' });
        if (error.message === 'ntfy_auth_unavailable') return json(res, 503, { error: 'ntfy_auth_unavailable' });
        console.error('ntfy credential provisioning failed');
        return json(res, 503, { error: 'ntfy_provisioning_failed' });
      }
    }

    if (req.method === 'POST' && req.url === '/v1/access') {
      if (!provisioningReady()) return json(res, 503, { error: 'not_provisioned' });
      const body = await readJson(req);
      if (!hasInstallAccess(req, body.installId)) return json(res, 401, { error: 'unauthorized' });
      const store = await loadStore();
      try {
        await synchronizeBoundAccess(store, body.installId, body.subscriptions);
      } catch (error) {
        if (error.message === 'ntfy_auth_unavailable') return json(res, 503, { error: 'ntfy_auth_unavailable' });
        if (error.message === 'ntfy_reprovision_required') return json(res, 409, { error: 'ntfy_reprovision_required' });
        if (String(error.message || '').startsWith('invalid_')) return json(res, 400, { error: 'invalid_topic_access' });
        console.error('ntfy access synchronization failed');
        return json(res, 503, { error: 'ntfy_access_sync_failed' });
      }
      return json(res, 204, {});
    }

    if (req.method === 'POST' && req.url === '/v1/register') {
      if (!provisioningReady()) return json(res, 503, { error: 'not_provisioned' });
      const body = await readJson(req);
      if (!hasInstallAccess(req, body.installId)) return json(res, 401, { error: 'unauthorized' });

      const registration = validatePushRegistration(body);
      if (!registration.ok) {
        if (registration.error === 'unsupported_platform') return json(res, 400, { error: 'unsupported_platform' });
        return json(res, 400, { error: registration.platform === 'ios' ? 'invalid_device_token' : 'invalid_push_token' });
      }

      const subscriptions = [...new Set((body.subscriptions || []).filter(x => typeof x === 'string' && /^dosefolk-[A-Za-z0-9_-]+$/.test(x)))];
      const store = await loadStore();
      if (ntfyAuth.ready) {
        const boundLocalTopic = store.topicBindings[body.installId];
        if (typeof boundLocalTopic !== 'string' || !boundLocalTopic) return json(res, 409, { error: 'ntfy_reprovision_required' });
        if (body.localTopic !== boundLocalTopic) return json(res, 409, { error: 'ntfy_topic_mismatch' });
        try {
          await ntfyAuth.setAccess(body.installId, boundLocalTopic, body.subscriptions);
        } catch (error) {
          if (String(error.message || '').startsWith('invalid_')) return json(res, 400, { error: 'invalid_topic_access' });
          console.error('ntfy access synchronization failed');
          return json(res, 503, { error: 'ntfy_access_sync_failed' });
        }
      }

      const providerReadiness = await pushDispatcher.readiness();
      if (!providerReadiness[registration.provider]) {
        return json(res, 503, { error: 'push_provider_unavailable', provider: registration.provider });
      }

      store.installs[body.installId] = {
        platform: registration.platform,
        pushToken: registration.pushToken,
        ...(registration.platform === 'ios' ? {
          environment: body.environment === 'sandbox' ? 'sandbox' : 'production',
          bundleId: cfg.bundleId
        } : {}),
        subscriptions,
        updatedAt: Date.now()
      };
      await saveStore(store);
      return json(res, 204, {});
    }

    if (req.method === 'POST' && req.url === '/internal/wake') {
      if (!provisioningReady()) return json(res, 503, { error: 'not_provisioned' });
      if (!hasInternalAccess(req)) return json(res, 401, { error: 'unauthorized' });
      const body = await readJson(req);
      const store = await loadStore();
      const targets = selectWakeTargets(store.installs, body.topics);
      const outcomes = await Promise.all(targets.map(async target => ({
        target,
        result: await pushDispatcher.dispatch(target)
      })));

      let storeChanged = false;
      for (const outcome of outcomes) {
        if (outcome.result.status === 'invalid_target') {
          clearStoredPushTarget(outcome.target);
          storeChanged = true;
        }
      }
      if (storeChanged) await saveStore(store);

      const providerCounts = {
        apns: { targeted: 0, sent: 0 },
        fcm: { targeted: 0, sent: 0 }
      };
      for (const { result } of outcomes) {
        if (result.provider === 'apns' || result.provider === 'fcm') {
          providerCounts[result.provider].targeted += 1;
          if (result.status === 'sent') providerCounts[result.provider].sent += 1;
        }
      }
      return json(res, 200, {
        targeted: targets.length,
        sent: outcomes.filter(({ result }) => result.status === 'sent').length,
        providers: providerCounts
      });
    }

    return json(res, 404, { error: 'not_found' });
  } catch (error) {
    console.error(error);
    return json(res, error.message === 'body_too_large' ? 413 : 400, { error: 'bad_request' });
  }
});
server.listen(cfg.port, cfg.host, () => console.log(`dosefolk-push-gateway listening on ${cfg.host}:${cfg.port}`));
