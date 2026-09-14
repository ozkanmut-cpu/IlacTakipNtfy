import assert from 'node:assert/strict';
import test from 'node:test';
import {
  NtfyAuthManager,
  normalizeTopicAccess,
  parseNtfyTokens,
  usernameForInstall
} from '../src/ntfy-auth.mjs';

const TOKEN_NEW = `tk_${'n'.repeat(29)}`;
const TOKEN_OLD = `tk_${'o'.repeat(29)}`;

test('install usernames are deterministic and do not expose install id', () => {
  const first = usernameForInstall('install-1234');
  assert.equal(first, usernameForInstall('install-1234'));
  assert.match(first, /^df_[0-9a-f]{24}$/);
  assert.equal(first.includes('install-1234'), false);
  assert.throws(() => usernameForInstall('short'), /invalid_install_id/);
});

test('topic access normalizes own and peer topics', () => {
  assert.deepEqual(
    normalizeTopicAccess('dosefolk-local', ['dosefolk-peer', 'dosefolk-peer', 'dosefolk-local']),
    { localTopic: 'dosefolk-local', readTopics: ['dosefolk-peer'] }
  );
  assert.throws(() => normalizeTopicAccess('bad topic', []), /invalid_local_topic/);
  assert.throws(
    () => normalizeTopicAccess('dosefolk-local', Array.from({ length: 65 }, (_, i) => `dosefolk-p${i}`)),
    /invalid_subscriptions/
  );
});

test('token parser extracts ntfy tokens only once', () => {
  assert.deepEqual(
    parseNtfyTokens(`user df_test\n- ${TOKEN_NEW} label\n- ${TOKEN_NEW} duplicate\n- not-a-token`),
    [TOKEN_NEW]
  );
});

test('manager fails closed without auth database', async () => {
  const manager = new NtfyAuthManager({ authFile: '' });
  assert.equal(manager.ready, false);
  await assert.rejects(
    manager.provision('install-1234', 'dosefolk-local', []),
    /ntfy_auth_not_configured/
  );
});

test('provision creates scoped user token and revokes previous token', async () => {
  const calls = [];
  const runner = async (args, env = {}) => {
    calls.push({ args, env });
    if (args[0] === 'token' && args[1] === 'add') return { stdout: `created ${TOKEN_NEW}\n`, stderr: '' };
    if (args[0] === 'token' && args[1] === 'list') {
      return { stdout: `user df_test\n- ${TOKEN_OLD}\n- ${TOKEN_NEW}\n`, stderr: '' };
    }
    return { stdout: '', stderr: '' };
  };

  const manager = new NtfyAuthManager({ authFile: '/tmp/user.db', runner });
  const result = await manager.provision(
    'install-1234',
    'dosefolk-local',
    ['dosefolk-peer-a', 'dosefolk-peer-b']
  );

  assert.equal(result.credential, TOKEN_NEW);
  assert.match(result.username, /^df_[0-9a-f]{24}$/);
  assert.equal(calls[0].args.join(' '), `user add --ignore-exists ${result.username}`);
  assert.ok(calls[0].env.NTFY_PASSWORD);
  assert.equal(calls[0].env.NTFY_PASSWORD.includes(TOKEN_NEW), false);

  assert.deepEqual(calls.slice(1, 5).map(call => call.args), [
    ['access', '--reset', result.username],
    ['access', result.username, 'dosefolk-local', 'rw'],
    ['access', result.username, 'dosefolk-peer-a', 'ro'],
    ['access', result.username, 'dosefolk-peer-b', 'ro']
  ]);
  assert.deepEqual(calls[5].args, ['token', 'add', '--label=dosefolk-install', result.username]);
  assert.deepEqual(calls[6].args, ['token', 'list', result.username]);
  assert.deepEqual(calls[7].args, ['token', 'remove', result.username, TOKEN_OLD]);
  assert.equal(calls.some(call => call.args.includes(TOKEN_NEW) && call.args[1] === 'remove'), false);
});
