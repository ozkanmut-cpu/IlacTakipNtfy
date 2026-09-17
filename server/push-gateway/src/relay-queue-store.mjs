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

function mapMessage(row) {
  if (!row) return null;
  return {
    messageId: row.message_id,
    relaySeq: row.relay_seq,
    routeId: row.route_id,
    senderInstallId: row.sender_install_id,
    recipientInstallId: row.recipient_install_id,
    senderKeyVersion: row.sender_key_version,
    recipientKeyVersion: row.recipient_key_version,
    ciphertext: row.ciphertext,
    receivedAt: row.received_at,
    expiresAt: row.expires_at,
    nextWakeAt: row.next_wake_at,
    wakeAttempts: row.wake_attempts
  };
}

export function openRelayQueue(filePath) {
  const { db, path } = prepareDatabase(filePath);
  db.exec(`
    CREATE TABLE IF NOT EXISTS recipient_sequences (
      recipient_install_id TEXT PRIMARY KEY,
      next_seq INTEGER NOT NULL CHECK(next_seq > 0)
    );

    CREATE TABLE IF NOT EXISTS relay_messages (
      message_id TEXT PRIMARY KEY,
      relay_seq INTEGER NOT NULL CHECK(relay_seq > 0),
      route_id TEXT NOT NULL,
      sender_install_id TEXT NOT NULL,
      recipient_install_id TEXT NOT NULL,
      sender_key_version INTEGER NOT NULL CHECK(sender_key_version > 0),
      recipient_key_version INTEGER NOT NULL CHECK(recipient_key_version > 0),
      ciphertext BLOB NOT NULL,
      received_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL,
      next_wake_at INTEGER,
      wake_attempts INTEGER NOT NULL DEFAULT 0 CHECK(wake_attempts >= 0)
    );

    CREATE UNIQUE INDEX IF NOT EXISTS relay_recipient_seq
      ON relay_messages(recipient_install_id, relay_seq);
    CREATE INDEX IF NOT EXISTS relay_recipient_received
      ON relay_messages(recipient_install_id, received_at, relay_seq);
    CREATE INDEX IF NOT EXISTS relay_expiry
      ON relay_messages(expires_at);
  `);

  const findMessageStatement = db.prepare(
    'SELECT * FROM relay_messages WHERE message_id = ?'
  );
  const sequenceStatement = db.prepare(
    'SELECT next_seq FROM recipient_sequences WHERE recipient_install_id = ?'
  );
  const insertSequenceStatement = db.prepare(
    'INSERT INTO recipient_sequences (recipient_install_id, next_seq) VALUES (?, ?)'
  );
  const advanceSequenceStatement = db.prepare(
    'UPDATE recipient_sequences SET next_seq = ? WHERE recipient_install_id = ?'
  );
  const insertMessageStatement = db.prepare(`
    INSERT INTO relay_messages (
      message_id, relay_seq, route_id, sender_install_id, recipient_install_id,
      sender_key_version, recipient_key_version, ciphertext, received_at, expires_at,
      next_wake_at, wake_attempts
    ) VALUES (
      @messageId, @relaySeq, @routeId, @senderInstallId, @recipientInstallId,
      @senderKeyVersion, @recipientKeyVersion, @ciphertext, @receivedAt, @expiresAt,
      @nextWakeAt, @wakeAttempts
    )
  `);
  const listRecipientStatement = db.prepare(`
    SELECT * FROM relay_messages
    WHERE recipient_install_id = ?
    ORDER BY relay_seq ASC
  `);
  const deleteMessageStatement = db.prepare(`
    DELETE FROM relay_messages
    WHERE recipient_install_id = ? AND message_id = ?
  `);
  const purgeRouteStatement = db.prepare(
    'DELETE FROM relay_messages WHERE route_id = ?'
  );

  const enqueueTransaction = db.transaction((message) => {
    const existing = findMessageStatement.get(message.messageId);
    if (existing) {
      return { inserted: false, relaySeq: existing.relay_seq };
    }

    const sequence = sequenceStatement.get(message.recipientInstallId);
    const relaySeq = sequence?.next_seq ?? 1;
    if (sequence) {
      advanceSequenceStatement.run(relaySeq + 1, message.recipientInstallId);
    } else {
      insertSequenceStatement.run(message.recipientInstallId, 2);
    }

    insertMessageStatement.run({
      messageId: message.messageId,
      relaySeq,
      routeId: message.routeId,
      senderInstallId: message.senderInstallId,
      recipientInstallId: message.recipientInstallId,
      senderKeyVersion: message.senderKeyVersion,
      recipientKeyVersion: message.recipientKeyVersion,
      ciphertext: message.ciphertext,
      receivedAt: message.receivedAt,
      expiresAt: message.expiresAt,
      nextWakeAt: message.nextWakeAt ?? null,
      wakeAttempts: message.wakeAttempts ?? 0
    });
    return { inserted: true, relaySeq };
  });

  return {
    path,
    enqueue(message) {
      return enqueueTransaction(message);
    },
    listRecipient(recipientInstallId) {
      return listRecipientStatement.all(recipientInstallId).map(mapMessage);
    },
    deleteMessage(recipientInstallId, messageId) {
      return deleteMessageStatement.run(recipientInstallId, messageId).changes > 0;
    },
    purgeRoute(routeId) {
      return purgeRouteStatement.run(routeId).changes;
    },
    close() {
      db.close();
    }
  };
}
