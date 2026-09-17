import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

function hashCredential(credential) {
  return createHash('sha256').update(credential).digest('base64url');
}

function bearerCredential(authorizationHeader) {
  if (typeof authorizationHeader !== 'string') return null;
  const match = authorizationHeader.match(/^Bearer\s+([^\s]+)$/i);
  return match?.[1] || null;
}

export function issueInstallCredential() {
  const credential = randomBytes(32).toString('base64url');
  return {
    credential,
    credentialHash: hashCredential(credential)
  };
}

export function authenticateInstall(authorizationHeader, metadataStore) {
  const credential = bearerCredential(authorizationHeader);
  if (!credential || !metadataStore?.getInstallationByCredentialHash) return null;

  const candidateHash = hashCredential(credential);
  const install = metadataStore.getInstallationByCredentialHash(candidateHash);
  if (!install || install.revokedAt != null) return null;

  const expected = Buffer.from(install.credentialHash || '');
  const candidate = Buffer.from(candidateHash);
  if (expected.length !== candidate.length || !timingSafeEqual(expected, candidate)) return null;
  return install;
}
