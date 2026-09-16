import { readStoredPushTarget } from './push-registration.mjs';

export function clearStoredPushTarget(install) {
  if (!install || typeof install !== 'object') return;
  delete install.pushToken;
  delete install.deviceToken;
  delete install.environment;
  delete install.bundleId;
}

export function selectWakeTargets(installs, topics) {
  const requested = new Set((topics || []).filter(topic => typeof topic === 'string'));
  if (requested.size === 0) return [];

  return Object.values(installs || {}).filter(install =>
    readStoredPushTarget(install) &&
    Array.isArray(install.subscriptions) &&
    install.subscriptions.some(topic => requested.has(topic))
  );
}
