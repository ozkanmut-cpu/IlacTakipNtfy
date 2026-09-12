import { createHmac, timingSafeEqual } from 'node:crypto';
import { loadGatewaySecrets } from './local-secrets.mjs';

const port = Number(process.env.PORT || 2587);
const dataFile = process.env.DATA_FILE || '/data/registrations.json';
const installId = 'dosefolk-enrollment-self-test';
const base = `http://127.0.0.1:${port}`;

const secrets = await loadGatewaySecrets({
  dataFile,
  installHmacKey: process.env.INSTALL_HMAC_KEY || '',
  internalSecret: process.env.INTERNAL_WAKE_SECRET || ''
});

async function post(path, body, headers = {}) {
  const response = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body)
  });
  const json = await response.json().catch(() => ({}));
  return { status: response.status, body: json };
}

const issued = await post('/internal/enrollment', { installId }, {
  'x-internal-secret': secrets.internalSecret
});

const ticket = typeof issued.body.ticket === 'string' ? issued.body.ticket : '';
const redeemed = ticket
  ? await post('/v1/provision', { installId, ticket })
  : { status: 0, body: {} };

const expected = createHmac('sha256', secrets.installHmacKey)
  .update(installId)
  .digest('base64url');
const actual = typeof redeemed.body.credential === 'string' ? redeemed.body.credential : '';
const aa = Buffer.from(actual);
const bb = Buffer.from(expected);
const credentialValid = aa.length === bb.length && timingSafeEqual(aa, bb);

const replay = ticket
  ? await post('/v1/provision', { installId, ticket })
  : { status: 0, body: {} };

const ok = issued.status === 200
  && redeemed.status === 200
  && credentialValid
  && replay.status === 401;

console.log(JSON.stringify({
  ok,
  issueStatus: issued.status,
  redeemStatus: redeemed.status,
  credentialValid,
  credentialLength: actual.length,
  replayStatus: replay.status
}));

if (!ok) process.exitCode = 1;
