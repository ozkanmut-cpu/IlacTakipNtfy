export function relayMetrics(queue, now = Date.now()) {
  const aggregate = queue.activeAggregate(now);
  return {
    pendingCount: aggregate.pendingCount,
    pendingBytes: aggregate.pendingBytes,
    oldestPendingAgeMs: aggregate.oldestReceivedAt === null
      ? 0
      : Math.max(0, now - aggregate.oldestReceivedAt)
  };
}
