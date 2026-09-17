import Database from 'better-sqlite3';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

function prepareDatabase(filePath) {
  const path = resolve(filePath);
  mkdirSync(dirname(path), { recursive: true });
  const db = new Database(path);
  db.pragma('journal_mode = WAL');
  db.pragma('foreign_keys = ON');
  return { db, path };
}

function mapInstallation(row) {
  if (!row) return null;
  return {
    installId: row.install_id,
    platform: row.platform,
    credentialHash: row.credential_hash,
    encryptionPublicKey: row.encryption_public_key,
    signingPublicKey: row.signing_public_key,
    keyVersion: row.key_version,
    revokedAt: row.revoked_at,
    createdAt: row.created_at,
    lastSeenAt: row.last_seen_at
  };
}

function mapRoute(row) {
  if (!row) return null;
  return {
    routeId: row.route_id,
    senderInstallId: row.sender_install_id,
    recipientInstallId: row.recipient_install_id,
    status: row.status,
    createdAt: row.created_at,
    revokedAt: row.revoked_at
  };
}

function mapRouteListing(row) {
  return {
    routeId: row.route_id,
    recipientInstallId: row.recipient_install_id,
    encryptionPublicKey: row.encryption_public_key,
    signingPublicKey: row.signing_public_key,
    keyVersion: row.key_version
  };
}

function mapPairingOffer(row) {
  if (!row) return null;
  return {
    offerId: row.offer_id,
    creatorInstallId: row.creator_install_id,
    secretHash: row.secret_hash,
    creatorProof: row.creator_proof,
    peerInstallId: row.peer_install_id,
    peerProof: row.peer_proof,
    status: row.status,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
    acceptedAt: row.accepted_at,
    confirmedAt: row.confirmed_at
  };
}

export function openMetadataStore(filePath) {
  const { db, path } = prepareDatabase(filePath);
  db.exec(`
    CREATE TABLE IF NOT EXISTS installations (
      install_id TEXT PRIMARY KEY,
      platform TEXT NOT NULL CHECK(platform IN ('android','ios')),
      credential_hash TEXT NOT NULL,
      encryption_public_key TEXT NOT NULL,
      signing_public_key TEXT NOT NULL,
      key_version INTEGER NOT NULL CHECK(key_version > 0),
      revoked_at INTEGER,
      created_at INTEGER NOT NULL,
      last_seen_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS routes (
      route_id TEXT PRIMARY KEY,
      sender_install_id TEXT NOT NULL,
      recipient_install_id TEXT NOT NULL,
      status TEXT NOT NULL CHECK(status IN ('active','revoked')),
      created_at INTEGER NOT NULL,
      revoked_at INTEGER
    );

    CREATE TABLE IF NOT EXISTS pairing_offers (
      offer_id TEXT PRIMARY KEY,
      creator_install_id TEXT NOT NULL,
      secret_hash TEXT NOT NULL,
      creator_proof TEXT NOT NULL,
      peer_install_id TEXT,
      peer_proof TEXT,
      status TEXT NOT NULL CHECK(status IN ('pending','accepted','confirmed')),
      created_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL,
      accepted_at INTEGER,
      confirmed_at INTEGER
    );

    CREATE UNIQUE INDEX IF NOT EXISTS installations_credential_hash
      ON installations(credential_hash);
    CREATE INDEX IF NOT EXISTS routes_sender_status
      ON routes(sender_install_id, status);
    CREATE INDEX IF NOT EXISTS routes_recipient_status
      ON routes(recipient_install_id, status);
    CREATE INDEX IF NOT EXISTS pairing_offers_expiry
      ON pairing_offers(status, expires_at);
  `);

  const upsertInstallationStatement = db.prepare(`
    INSERT INTO installations (
      install_id, platform, credential_hash, encryption_public_key,
      signing_public_key, key_version, revoked_at, created_at, last_seen_at
    ) VALUES (
      @installId, @platform, @credentialHash, @encryptionPublicKey,
      @signingPublicKey, @keyVersion, @revokedAt, @createdAt, @lastSeenAt
    )
    ON CONFLICT(install_id) DO UPDATE SET
      platform = excluded.platform,
      credential_hash = excluded.credential_hash,
      encryption_public_key = excluded.encryption_public_key,
      signing_public_key = excluded.signing_public_key,
      key_version = excluded.key_version,
      revoked_at = excluded.revoked_at,
      last_seen_at = excluded.last_seen_at
  `);
  const getInstallationStatement = db.prepare(
    'SELECT * FROM installations WHERE install_id = ?'
  );
  const getInstallationByCredentialHashStatement = db.prepare(
    'SELECT * FROM installations WHERE credential_hash = ?'
  );
  const insertRouteStatement = db.prepare(`
    INSERT INTO routes (
      route_id, sender_install_id, recipient_install_id, status, created_at, revoked_at
    ) VALUES (
      @routeId, @senderInstallId, @recipientInstallId, @status, @createdAt, @revokedAt
    )
  `);
  const getRouteStatement = db.prepare('SELECT * FROM routes WHERE route_id = ?');
  const listActiveRoutesForSenderStatement = db.prepare(`
    SELECT
      r.route_id,
      r.recipient_install_id,
      recipient.encryption_public_key,
      recipient.signing_public_key,
      recipient.key_version
    FROM routes AS r
    JOIN installations AS recipient
      ON recipient.install_id = r.recipient_install_id
    WHERE r.sender_install_id = ?
      AND r.status = 'active'
      AND recipient.revoked_at IS NULL
    ORDER BY r.created_at ASC, r.route_id ASC
  `);
  const revokeRouteStatement = db.prepare(`
    UPDATE routes
    SET status = 'revoked', revoked_at = ?
    WHERE route_id = ? AND status = 'active'
  `);
  const insertPairingOfferStatement = db.prepare(`
    INSERT INTO pairing_offers (
      offer_id, creator_install_id, secret_hash, creator_proof,
      peer_install_id, peer_proof, status, created_at, expires_at,
      accepted_at, confirmed_at
    ) VALUES (
      @offerId, @creatorInstallId, @secretHash, @creatorProof,
      NULL, NULL, @status, @createdAt, @expiresAt,
      NULL, NULL
    )
  `);
  const getPairingOfferStatement = db.prepare(
    'SELECT * FROM pairing_offers WHERE offer_id = ?'
  );
  const acceptPairingOfferStatement = db.prepare(`
    UPDATE pairing_offers
    SET peer_install_id = ?, peer_proof = ?, status = 'accepted', accepted_at = ?
    WHERE offer_id = ? AND status = 'pending'
  `);
  const confirmPairingOfferStatement = db.prepare(`
    UPDATE pairing_offers
    SET status = 'confirmed', confirmed_at = ?
    WHERE offer_id = ? AND status = 'accepted'
  `);
  const purgeExpiredPairingOffersStatement = db.prepare(`
    DELETE FROM pairing_offers
    WHERE status IN ('pending','accepted') AND expires_at <= ?
  `);

  const activatePairingTransaction = db.transaction(({ offerId, confirmedAt, routes }) => {
    if (!Array.isArray(routes) || routes.length !== 2) return null;
    const update = confirmPairingOfferStatement.run(Number(confirmedAt), offerId);
    if (update.changes !== 1) return null;
    for (const route of routes) {
      insertRouteStatement.run({
        routeId: route.routeId,
        senderInstallId: route.senderInstallId,
        recipientInstallId: route.recipientInstallId,
        status: route.status,
        createdAt: route.createdAt,
        revokedAt: route.revokedAt ?? null
      });
    }
    return mapPairingOffer(getPairingOfferStatement.get(offerId));
  });

  return {
    path,
    upsertInstallation(record) {
      const now = Number(record.now);
      upsertInstallationStatement.run({
        installId: record.installId,
        platform: record.platform,
        credentialHash: record.credentialHash,
        encryptionPublicKey: record.encryptionPublicKey,
        signingPublicKey: record.signingPublicKey,
        keyVersion: record.keyVersion,
        revokedAt: record.revokedAt ?? null,
        createdAt: record.createdAt ?? now,
        lastSeenAt: record.lastSeenAt ?? now
      });
      return mapInstallation(getInstallationStatement.get(record.installId));
    },
    getInstallation(installId) {
      return mapInstallation(getInstallationStatement.get(installId));
    },
    getInstallationByCredentialHash(credentialHash) {
      return mapInstallation(getInstallationByCredentialHashStatement.get(credentialHash));
    },
    insertRoute(route) {
      insertRouteStatement.run({
        routeId: route.routeId,
        senderInstallId: route.senderInstallId,
        recipientInstallId: route.recipientInstallId,
        status: route.status,
        createdAt: route.createdAt,
        revokedAt: route.revokedAt ?? null
      });
      return mapRoute(getRouteStatement.get(route.routeId));
    },
    getRoute(routeId) {
      return mapRoute(getRouteStatement.get(routeId));
    },
    listActiveRoutesForSender(installId) {
      return listActiveRoutesForSenderStatement.all(installId).map(mapRouteListing);
    },
    revokeRoute(routeId, revokedAt) {
      return revokeRouteStatement.run(Number(revokedAt), routeId).changes > 0;
    },
    insertPairingOffer(record) {
      insertPairingOfferStatement.run(record);
      return mapPairingOffer(getPairingOfferStatement.get(record.offerId));
    },
    getPairingOffer(offerId) {
      return mapPairingOffer(getPairingOfferStatement.get(offerId));
    },
    acceptPairingOffer(offerId, peerInstallId, peerProof, acceptedAt) {
      const result = acceptPairingOfferStatement.run(peerInstallId, peerProof, Number(acceptedAt), offerId);
      return result.changes === 1 ? mapPairingOffer(getPairingOfferStatement.get(offerId)) : null;
    },
    activatePairingOffer(record) {
      return activatePairingTransaction(record);
    },
    purgeExpiredPairingOffers(now) {
      return purgeExpiredPairingOffersStatement.run(Number(now)).changes;
    },
    close() {
      db.close();
    }
  };
}
