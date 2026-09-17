import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

const PAIRING_LIFETIME_MS = 10 * 60 * 1000;

function safeEqualText(a, b) {
  const aa = Buffer.from(String(a || ''));
  const bb = Buffer.from(String(b || ''));
  return aa.length === bb.length && timingSafeEqual(aa, bb);
}

function activeInstallation(metadataStore, installId) {
  if (typeof installId !== 'string' || !installId) return null;
  const installation = metadataStore.getInstallation(installId);
  return installation && installation.revokedAt === null ? installation : null;
}

function validOfferId(value) {
  return typeof value === 'string' && /^[A-Za-z0-9._-]{8,128}$/.test(value);
}

function validDigest(value) {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{32,128}$/.test(value);
}

function validPairingSecret(value) {
  return typeof value === 'string' && value.length >= 16 && value.length <= 1024;
}

export function hashPairingSecret(pairingSecret) {
  return createHash('sha256').update(String(pairingSecret)).digest('base64url');
}

export function createPairingProof(pairingSecret, { offerId, role, installId }) {
  const transcript = `dosefolk-pairing-v2\n${role}\n${offerId}\n${installId}`;
  return createHmac('sha256', String(pairingSecret)).update(transcript).digest('base64url');
}

export function createPairingOffer(metadataStore, {
  offerId,
  creatorInstallId,
  secretHash,
  creatorProof,
  now = Date.now()
}) {
  metadataStore.purgeExpiredPairingOffers(now);
  if (!activeInstallation(metadataStore, creatorInstallId)) return null;
  if (!validOfferId(offerId) || !validDigest(secretHash) || !validDigest(creatorProof)) return null;
  if (metadataStore.getPairingOffer(offerId)) return null;

  return metadataStore.insertPairingOffer({
    offerId,
    creatorInstallId,
    secretHash,
    creatorProof,
    status: 'pending',
    createdAt: Number(now),
    expiresAt: Number(now) + PAIRING_LIFETIME_MS
  });
}

export function acceptPairingOffer(metadataStore, {
  offerId,
  peerInstallId,
  pairingSecret,
  peerProof,
  now = Date.now()
}) {
  metadataStore.purgeExpiredPairingOffers(now);
  const peer = activeInstallation(metadataStore, peerInstallId);
  if (!peer || !validPairingSecret(pairingSecret) || !validDigest(peerProof)) return null;

  const offer = metadataStore.getPairingOffer(offerId);
  if (!offer || offer.status !== 'pending' || offer.creatorInstallId === peer.installId) return null;
  if (!activeInstallation(metadataStore, offer.creatorInstallId)) return null;
  if (!safeEqualText(hashPairingSecret(pairingSecret), offer.secretHash)) return null;

  const expectedCreatorProof = createPairingProof(pairingSecret, {
    offerId,
    role: 'creator',
    installId: offer.creatorInstallId
  });
  const expectedPeerProof = createPairingProof(pairingSecret, {
    offerId,
    role: 'peer',
    installId: peer.installId
  });
  if (!safeEqualText(expectedCreatorProof, offer.creatorProof)) return null;
  if (!safeEqualText(expectedPeerProof, peerProof)) return null;

  return metadataStore.acceptPairingOffer(offerId, peer.installId, peerProof, Number(now));
}

export function confirmPairingOffer(metadataStore, {
  offerId,
  actorInstallId,
  pairingSecret,
  now = Date.now()
}) {
  metadataStore.purgeExpiredPairingOffers(now);
  const actor = activeInstallation(metadataStore, actorInstallId);
  if (!actor || !validPairingSecret(pairingSecret)) return null;

  const offer = metadataStore.getPairingOffer(offerId);
  if (!offer || offer.status !== 'accepted' || offer.creatorInstallId !== actor.installId) return null;
  if (!offer.peerInstallId || !offer.peerProof || !activeInstallation(metadataStore, offer.peerInstallId)) return null;
  if (!safeEqualText(hashPairingSecret(pairingSecret), offer.secretHash)) return null;

  const expectedCreatorProof = createPairingProof(pairingSecret, {
    offerId,
    role: 'creator',
    installId: offer.creatorInstallId
  });
  const expectedPeerProof = createPairingProof(pairingSecret, {
    offerId,
    role: 'peer',
    installId: offer.peerInstallId
  });
  if (!safeEqualText(expectedCreatorProof, offer.creatorProof)) return null;
  if (!safeEqualText(expectedPeerProof, offer.peerProof)) return null;

  const createdAt = Number(now);
  return metadataStore.activatePairingOffer({
    offerId,
    confirmedAt: createdAt,
    routes: [
      {
        routeId: `route-${randomBytes(18).toString('base64url')}`,
        senderInstallId: offer.creatorInstallId,
        recipientInstallId: offer.peerInstallId,
        status: 'active',
        createdAt
      },
      {
        routeId: `route-${randomBytes(18).toString('base64url')}`,
        senderInstallId: offer.peerInstallId,
        recipientInstallId: offer.creatorInstallId,
        status: 'active',
        createdAt
      }
    ]
  });
}
