import { normalizeTopicAccess } from './ntfy-auth.mjs';

export async function buildProvisioningCredentials({
  installId,
  gatewayCredential,
  localTopic,
  subscriptions = [],
  ntfyAuth,
  requireNtfyToken = false
}) {
  const response = { installId, credential: gatewayCredential };
  if (!ntfyAuth?.ready) {
    if (requireNtfyToken) throw new Error('ntfy_auth_unavailable');
    return response;
  }
  if (!requireNtfyToken && !localTopic) return response;

  const access = normalizeTopicAccess(localTopic, subscriptions);
  const native = await ntfyAuth.provision(
    installId,
    access.localTopic,
    [access.localTopic, ...access.readTopics]
  );
  if (!native?.credential) throw new Error('ntfy_token_create_failed');
  return { ...response, ntfyToken: native.credential };
}
