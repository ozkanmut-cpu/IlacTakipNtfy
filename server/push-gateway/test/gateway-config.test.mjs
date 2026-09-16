import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const gateway = new URL('../', import.meta.url);
const repo = new URL('../../../', import.meta.url);

async function text(url) {
  return readFile(url, 'utf8');
}

test('gateway dependency and secret configuration is reproducible and fail-closed', async () => {
  const [packageJson, packageLock, dockerfile, compose, gitignore, workflow] = await Promise.all([
    text(new URL('package.json', gateway)),
    text(new URL('package-lock.json', gateway)),
    text(new URL('Dockerfile', gateway)),
    text(new URL('docker-compose.yml', gateway)),
    text(new URL('.gitignore', gateway)),
    text(new URL('.github/workflows/build-apk.yml', repo))
  ]);

  const pkg = JSON.parse(packageJson);
  const lock = JSON.parse(packageLock);
  assert.equal(pkg.dependencies?.['firebase-admin'], '14.4.0');
  assert.equal(lock.packages?.['']?.dependencies?.['firebase-admin'], '14.4.0');

  for (const source of ['src/fcm.mjs', 'src/push-dispatcher.mjs', 'src/push-registration.mjs']) {
    assert.match(pkg.scripts.check, new RegExp(source.replaceAll('.', '\\.')));
  }

  assert.match(dockerfile, /COPY package\.json package-lock\.json \.\//);
  assert.match(dockerfile, /RUN npm ci --omit=dev/);

  assert.match(compose, /GOOGLE_APPLICATION_CREDENTIALS:\s*\/run\/secrets\/firebase-service-account\.json/);
  assert.match(compose, /\.\/secrets:\/run\/secrets:ro/);

  assert.match(gitignore, /^firebase-service-account\.json$/m);
  assert.match(gitignore, /^\*service-account\*\.json$/m);

  const install = workflow.indexOf('npm ci');
  const check = workflow.indexOf('npm run check');
  const suite = workflow.indexOf('npm test');
  assert.ok(install >= 0 && check > install && suite > check);
});
