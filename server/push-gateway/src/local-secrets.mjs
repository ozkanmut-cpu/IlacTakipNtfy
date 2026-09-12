import { randomBytes } from 'node:crypto';
import { mkdir, open, readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';

export async function loadGatewaySecrets({ dataFile, installHmacKey = '', internalSecret = '' }) {
  if (installHmacKey && internalSecret) return { installHmacKey, internalSecret };

  const secretFile = process.env.GATEWAY_SECRET_FILE || join(dirname(dataFile), 'gateway-secrets.json');
  await mkdir(dirname(secretFile), { recursive: true });

  let stored;
  try {
    stored = JSON.parse(await readFile(secretFile, 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    const generated = {
      installHmacKey: randomBytes(32).toString('base64url'),
      internalSecret: randomBytes(32).toString('base64url')
    };
    try {
      const handle = await open(secretFile, 'wx', 0o600);
      try { await handle.writeFile(JSON.stringify(generated)); }
      finally { await handle.close(); }
      stored = generated;
    } catch (createError) {
      if (createError.code !== 'EEXIST') throw createError;
      stored = JSON.parse(await readFile(secretFile, 'utf8'));
    }
  }

  const resolvedInstallKey = installHmacKey || stored?.installHmacKey || '';
  const resolvedInternalSecret = internalSecret || stored?.internalSecret || '';
  return { installHmacKey: resolvedInstallKey, internalSecret: resolvedInternalSecret };
}
