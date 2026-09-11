import { connect } from 'node:http2';
import { readFile } from 'node:fs/promises';
import { createPrivateKey, sign } from 'node:crypto';

function base64url(value) {
  return Buffer.from(value).toString('base64url');
}

export class APNsClient {
  constructor({ teamId, keyId, keyPath, bundleId }) {
    this.teamId = teamId;
    this.keyId = keyId;
    this.keyPath = keyPath;
    this.bundleId = bundleId;
    this.key = null;
    this.cachedJwt = null;
    this.cachedJwtAt = 0;
  }

  async ready() {
    return Boolean(this.teamId && this.keyId && this.keyPath && this.bundleId);
  }

  async jwt() {
    const now = Math.floor(Date.now() / 1000);
    if (this.cachedJwt && now - this.cachedJwtAt < 50 * 60) return this.cachedJwt;
    if (!this.key) this.key = createPrivateKey(await readFile(this.keyPath));
    const header = base64url(JSON.stringify({ alg: 'ES256', kid: this.keyId }));
    const claims = base64url(JSON.stringify({ iss: this.teamId, iat: now }));
    const input = `${header}.${claims}`;
    const signature = sign('sha256', Buffer.from(input), {
      key: this.key,
      dsaEncoding: 'ieee-p1363'
    }).toString('base64url');
    this.cachedJwt = `${input}.${signature}`;
    this.cachedJwtAt = now;
    return this.cachedJwt;
  }

  async wake(deviceToken, environment = 'production') {
    const origin = environment === 'sandbox'
      ? 'https://api.sandbox.push.apple.com'
      : 'https://api.push.apple.com';
    const client = connect(origin);
    const token = await this.jwt();
    const body = JSON.stringify({ aps: { 'content-available': 1 } });

    return new Promise((resolve, reject) => {
      let response = '';
      const req = client.request({
        ':method': 'POST',
        ':path': `/3/device/${deviceToken}`,
        authorization: `bearer ${token}`,
        'apns-topic': this.bundleId,
        'apns-push-type': 'background',
        'apns-priority': '5',
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(body)
      });
      let status = 0;
      req.setEncoding('utf8');
      req.on('response', headers => { status = Number(headers[':status'] || 0); });
      req.on('data', chunk => { response += chunk; });
      req.on('end', () => {
        client.close();
        if (status === 200) return resolve({ status });
        reject(new Error(`APNs ${status}: ${response || 'unknown error'}`));
      });
      req.on('error', error => { client.destroy(); reject(error); });
      req.end(body);
    });
  }
}
