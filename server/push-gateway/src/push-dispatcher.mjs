import { classifyFcmError } from './fcm.mjs';
import { readStoredPushTarget } from './push-registration.mjs';

function classifyApnsError(error) {
  const message = String(error?.message || '');
  if (/BadDeviceToken|DeviceTokenNotForTopic|Unregistered/i.test(message)) return 'invalid_target';
  const status = Number(message.match(/APNs\s+(\d{3})/)?.[1] || 0);
  if (status === 429 || status >= 500) return 'transient';
  if (!status) return 'transient';
  return 'permanent';
}

function defaultSleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

export class PushDispatcher {
  constructor({ apns, fcm, sleeper = defaultSleep, random = Math.random, retryBaseMs = 250 }) {
    this.apns = apns;
    this.fcm = fcm;
    this.sleeper = sleeper;
    this.random = random;
    this.retryBaseMs = retryBaseMs;
  }

  async readiness() {
    const safeReady = async client => {
      try {
        return Boolean(client && await client.ready());
      } catch {
        return false;
      }
    };
    const [apns, fcm] = await Promise.all([safeReady(this.apns), safeReady(this.fcm)]);
    return { apns, fcm };
  }

  async dispatch(install) {
    const target = readStoredPushTarget(install);
    if (!target) return { provider: null, status: 'missing_target' };

    const client = target.provider === 'apns' ? this.apns : this.fcm;
    let available = false;
    try {
      available = Boolean(client && await client.ready());
    } catch {
      available = false;
    }
    if (!available) return { provider: target.provider, status: 'unavailable' };

    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        if (target.provider === 'apns') {
          await client.wake(target.pushToken, target.environment);
        } else {
          await client.wake(target.pushToken);
        }
        return { provider: target.provider, status: 'sent' };
      } catch (error) {
        const kind = target.provider === 'fcm'
          ? classifyFcmError(error)
          : classifyApnsError(error);
        if (kind === 'invalid_target') return { provider: target.provider, status: 'invalid_target' };
        if (kind !== 'transient') return { provider: target.provider, status: 'permanent_error' };
        if (attempt === 2) return { provider: target.provider, status: 'transient_error' };
        const base = this.retryBaseMs * (2 ** attempt);
        const jitter = 0.75 + (Math.max(0, Math.min(1, this.random())) * 0.5);
        await this.sleeper(Math.round(base * jitter));
      }
    }

    return { provider: target.provider, status: 'transient_error' };
  }
}
