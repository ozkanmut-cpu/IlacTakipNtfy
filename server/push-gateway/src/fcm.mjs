import { readFile } from 'node:fs/promises';

const INVALID_TARGET_CODES = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
  'messaging/invalid-recipient'
]);

const TRANSIENT_CODES = new Set([
  'messaging/server-unavailable',
  'messaging/internal-error',
  'messaging/message-rate-exceeded',
  'messaging/device-message-rate-exceeded',
  'messaging/topics-message-rate-exceeded'
]);

export function classifyFcmError(error) {
  const code = typeof error?.code === 'string' ? error.code : '';
  if (INVALID_TARGET_CODES.has(code)) return 'invalid_target';
  if (TRANSIENT_CODES.has(code)) return 'transient';
  if (!code) return 'transient';
  return 'permanent';
}

async function defaultCredentialProbe(credentialPath) {
  const raw = await readFile(credentialPath, 'utf8');
  const json = JSON.parse(raw);
  if (!(json?.project_id && json?.client_email && json?.private_key)) return false;
  const { cert } = await import('firebase-admin/app');
  cert(json);
  return true;
}

let defaultMessagingPromise;
async function defaultSender(message) {
  if (!defaultMessagingPromise) {
    defaultMessagingPromise = (async () => {
      const { applicationDefault, getApps, initializeApp } = await import('firebase-admin/app');
      const { getMessaging } = await import('firebase-admin/messaging');
      const app = getApps()[0] || initializeApp({ credential: applicationDefault() });
      return getMessaging(app);
    })();
  }
  const messaging = await defaultMessagingPromise;
  return messaging.send(message);
}

export class FCMClient {
  constructor({
    credentialPath = process.env.GOOGLE_APPLICATION_CREDENTIALS || '',
    credentialProbe = defaultCredentialProbe,
    sender = defaultSender
  } = {}) {
    this.credentialPath = credentialPath;
    this.credentialProbe = credentialProbe;
    this.sender = sender;
  }

  async ready() {
    if (!this.credentialPath) return false;
    try {
      return Boolean(await this.credentialProbe(this.credentialPath));
    } catch {
      return false;
    }
  }

  async wake(pushTarget) {
    const fid = typeof pushTarget === 'string' ? pushTarget.trim() : '';
    if (!fid) throw new Error('invalid_fcm_target');
    const messageId = await this.sender({
      fid,
      data: { wakeType: 'sync', protocolVersion: '1' },
      android: { priority: 'normal' }
    });
    return { messageId };
  }
}
