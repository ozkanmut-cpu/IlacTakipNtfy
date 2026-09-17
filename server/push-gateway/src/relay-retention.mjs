export function purgeExpired(queue, now = Date.now()) {
  return queue.purgeExpired(now);
}

export function createInstallRateLimiter({ limit = 120, windowMs = 60_000, maxEntries = 10_000 } = {}) {
  if (!Number.isSafeInteger(limit) || limit < 1
    || !Number.isSafeInteger(windowMs) || windowMs < 1
    || !Number.isSafeInteger(maxEntries) || maxEntries < 1) {
    throw new TypeError('invalid_rate_limit_configuration');
  }
  const entries = new Map();
  const retryAfter = (entry, now) => Math.max(1, Math.ceil((entry.windowStart + windowMs - now) / 1000));

  function pruneAtCapacity(now) {
    let earliestExpiry = Infinity;
    for (const [installId, entry] of entries) {
      const expiry = entry.windowStart + windowMs;
      if (now >= expiry) entries.delete(installId);
      else earliestExpiry = Math.min(earliestExpiry, expiry);
    }
    return earliestExpiry;
  }

  return {
    consume(installId, now = Date.now()) {
      if (typeof installId !== 'string' || !installId) return { allowed: false, retryAfterSeconds: 60 };
      let entry = entries.get(installId);
      if (entry && now >= entry.windowStart + windowMs) {
        entries.delete(installId);
        entry = null;
      }
      if (!entry) {
        if (entries.size >= maxEntries) {
          const earliestExpiry = pruneAtCapacity(now);
          if (entries.size >= maxEntries) {
            return { allowed: false, retryAfterSeconds: Math.max(1, Math.ceil((earliestExpiry - now) / 1000)) };
          }
        }
        entry = { windowStart: now, count: 0 };
        entries.set(installId, entry);
      }
      if (entry.count >= limit) return { allowed: false, retryAfterSeconds: retryAfter(entry, now) };
      entry.count += 1;
      return { allowed: true, retryAfterSeconds: 0 };
    },
    get size() { return entries.size; }
  };
}
