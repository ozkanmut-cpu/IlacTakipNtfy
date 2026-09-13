export function selectWakeTargets(installs, topics) {
  const requested = new Set((topics || []).filter(topic => typeof topic === 'string'));
  if (requested.size === 0) return [];

  return Object.values(installs || {}).filter(install =>
    Array.isArray(install.subscriptions) &&
    install.subscriptions.some(topic => requested.has(topic))
  );
}
