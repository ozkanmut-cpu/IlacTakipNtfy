const APNS_TOKEN = /^[0-9a-f]{64,256}$/i;
const MAX_FCM_TOKEN_LENGTH = 4096;

export function normalizePlatform(value) {
  if (value === undefined || value === null) return 'ios';
  if (value === 'ios' || value === 'android') return value;
  return null;
}

export function readPushToken(body) {
  const value = typeof body?.pushToken === 'string'
    ? body.pushToken
    : typeof body?.deviceToken === 'string'
      ? body.deviceToken
      : '';
  return value.trim();
}

export function validatePushRegistration(body) {
  const platform = normalizePlatform(body?.platform);
  if (!platform) {
    return {
      ok: false,
      platform: null,
      pushToken: '',
      provider: null,
      error: 'unsupported_platform'
    };
  }

  const pushToken = readPushToken(body);
  const provider = platform === 'android' ? 'fcm' : 'apns';
  const validToken = platform === 'android'
    ? pushToken.length > 0 && pushToken.length <= MAX_FCM_TOKEN_LENGTH
    : APNS_TOKEN.test(pushToken);

  return {
    ok: validToken,
    platform,
    pushToken,
    provider,
    error: validToken ? null : 'invalid_push_token'
  };
}

export function readStoredPushTarget(install) {
  if (!install || typeof install !== 'object') return null;
  const platform = normalizePlatform(install.platform);
  if (!platform) return null;

  const pushToken = readPushToken(install);
  const validToken = platform === 'android'
    ? pushToken.length > 0 && pushToken.length <= MAX_FCM_TOKEN_LENGTH
    : APNS_TOKEN.test(pushToken);
  if (!validToken) return null;

  return {
    platform,
    provider: platform === 'android' ? 'fcm' : 'apns',
    pushToken,
    environment: platform === 'ios'
      ? (install.environment === 'sandbox' ? 'sandbox' : 'production')
      : undefined
  };
}
