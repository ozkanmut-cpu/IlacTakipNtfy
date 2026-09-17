function activeInstallation(metadataStore, installId) {
  if (typeof installId !== 'string' || !installId) return null;
  const installation = metadataStore.getInstallation(installId);
  return installation && installation.revokedAt === null ? installation : null;
}

export function listRoutesForSender(metadataStore, installId) {
  if (!activeInstallation(metadataStore, installId)) return [];
  return metadataStore.listActiveRoutesForSender(installId);
}

export function authorizeRoute(metadataStore, routeId, authenticatedSender) {
  const senderInstallId = authenticatedSender?.installId;
  const sender = activeInstallation(metadataStore, senderInstallId);
  if (!sender) return null;

  const route = metadataStore.getRoute(routeId);
  if (!route || route.status !== 'active' || route.senderInstallId !== sender.installId) return null;
  if (!activeInstallation(metadataStore, route.recipientInstallId)) return null;
  return route;
}

export function revokeRoute(metadataStore, relayQueue, routeId, actorInstallId, now = Date.now()) {
  const actor = activeInstallation(metadataStore, actorInstallId);
  if (!actor) return false;

  const route = metadataStore.getRoute(routeId);
  if (!route) return false;
  if (route.senderInstallId !== actor.installId && route.recipientInstallId !== actor.installId) return false;

  if (route.status !== 'revoked' && !metadataStore.revokeRoute(routeId, now)) return false;
  relayQueue.purgeRoute(routeId);
  return true;
}
