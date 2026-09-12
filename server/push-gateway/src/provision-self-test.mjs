import { createHmac, timingSafeEqual } from 'node:crypto';
import { loadGatewaySecrets } from './local-secrets.mjs';

const port = Number(process.env.PORT || 2587);
const dataFile = process.env.DATA_FILE || '/data/registrations.json';
const installId = 'dosefolk-self-test-install';

const secrets = await loadGatewaySecrets({
  dataFile,
  installHmacKey: process.env.INSTALL_HMAC_KEY || '',
  internalSecret: process.env.INTERNAL_WAKE_SECRET || ''
});

const response = await fetch(`http://127.0.0.1:${port}/internal/provision`, {
  method: 'POST',
  headers: {
    'content-type': 'application/json',
    'x-internal-secret': secrets.internalSecret
  },
  body: JSON.stringify({ installId })
});

const body = await response.json().catch(() => ({}));
const expected = createHmac('sha256', secrets.installHmacKey)
  .update(installId)
  .digest('base64url');
const actual = typeof body.credential === 'string' ? body.credential : '';
const aa = Buffer.from(actual);
const bb = Buffer.from(expected);
const credentialValid = aa.length === bb.length && timingSafeEqual(aa, bb);
const ok = response.status === 200 && body.installId === installId && credentialValid;

console.log(JSON.stringify({
  ok,
  status: response.status,
  credentialValid,
  credentialLength: actual.length
}));

if (!ok) process.exitCode = 1;
