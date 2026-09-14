import { execFile } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const INSTALL_ID_PATTERN = /^[A-Za-z0-9._-]{8,128}$/;
const TOPIC_PATTERN = /^dosefolk-[A-Za-z0-9_-]+$/;
const TOKEN_PATTERN = /\btk_[A-Za-z0-9_-]{29}\b/g;
const MAX_SUBSCRIPTIONS = 64;

export function usernameForInstall(installId) {
  if (!INSTALL_ID_PATTERN.test(installId || '')) throw new Error('invalid_install_id');
  const digest = createHash('sha256').update(installId).digest('hex').slice(0, 24);
  return `df_${digest}`;
}

export function normalizeTopicAccess(localTopic, subscriptions = []) {
  if (!TOPIC_PATTERN.test(localTopic || '')) throw new Error('invalid_local_topic');
  if (!Array.isArray(subscriptions) || subscriptions.length > MAX_SUBSCRIPTIONS) {
    throw new Error('invalid_subscriptions');
  }
  const topics = [...new Set([localTopic, ...subscriptions])];
  if (topics.some(topic => !TOPIC_PATTERN.test(topic || ''))) throw new Error('invalid_subscription_topic');
  return {
    localTopic,
    readTopics: topics.filter(topic => topic !== localTopic)
  };
}

export function parseNtfyTokens(output) {
  return [...new Set(String(output || '').match(TOKEN_PATTERN) || [])];
}

export class NtfyAuthManager {
  constructor({
    authFile = process.env.NTFY_AUTH_FILE || '',
    cliPath = process.env.NTFY_CLI_PATH || 'ntfy',
    runner
  } = {}) {
    this.authFile = authFile;
    this.cliPath = cliPath;
    this.runner = runner || this.#run.bind(this);
  }

  get ready() {
    return Boolean(this.authFile);
  }

  async #run(args, extraEnv = {}) {
    if (!this.ready) throw new Error('ntfy_auth_not_configured');
    return execFileAsync(this.cliPath, args, {
      env: { ...process.env, NTFY_AUTH_FILE: this.authFile, ...extraEnv },
      maxBuffer: 1024 * 1024
    });
  }

  async setAccess(installId, localTopic, subscriptions = []) {
    if (!this.ready) throw new Error('ntfy_auth_not_configured');
    const username = usernameForInstall(installId);
    const access = normalizeTopicAccess(localTopic, subscriptions);
    await this.runner(['access', '--reset', username]);
    await this.runner(['access', username, access.localTopic, 'rw']);
    for (const topic of access.readTopics) {
      await this.runner(['access', username, topic, 'ro']);
    }
    return username;
  }

  async provision(installId, localTopic, subscriptions = []) {
    if (!this.ready) throw new Error('ntfy_auth_not_configured');
    const username = usernameForInstall(installId);
    const password = randomBytes(32).toString('base64url');
    await this.runner(
      ['user', 'add', '--ignore-exists', username],
      { NTFY_PASSWORD: password }
    );
    await this.setAccess(installId, localTopic, subscriptions);

    const created = await this.runner(['token', 'add', '--label=dosefolk-install', username]);
    const newTokens = parseNtfyTokens(created.stdout);
    if (newTokens.length !== 1) throw new Error('ntfy_token_create_failed');
    const credential = newTokens[0];

    const listed = await this.runner(['token', 'list', username]);
    for (const token of parseNtfyTokens(listed.stdout)) {
      if (token !== credential) {
        await this.runner(['token', 'remove', username, token]);
      }
    }
    return { username, credential };
  }
}
