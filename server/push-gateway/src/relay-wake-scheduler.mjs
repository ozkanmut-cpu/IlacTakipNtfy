// Applied to the previous scheduled due time, these deltas yield absolute
// attempts at T+2m, T+15m, T+1h, T+6h and T+24h.
const RETRY_DELAYS_MS = [
  2 * 60 * 1000,
  13 * 60 * 1000,
  45 * 60 * 1000,
  5 * 60 * 60 * 1000,
  18 * 60 * 60 * 1000
];
const DAILY_RETRY_MS = 24 * 60 * 60 * 1000;

function nextWakeAt(job) {
  return job.dueAt + (RETRY_DELAYS_MS[job.wakeAttempts] ?? DAILY_RETRY_MS);
}

export class RelayWakeScheduler {
  constructor({ queue, dispatcher, resolveInstall }) {
    this.queue = queue;
    this.dispatcher = dispatcher;
    this.resolveInstall = resolveInstall;
    this.pendingRun = Promise.resolve();
  }

  runDue(now = Date.now()) {
    const run = this.pendingRun.then(() => this.#runDue(Number(now)));
    this.pendingRun = run.catch(() => {});
    return run;
  }

  async #runDue(now) {
    const jobs = this.queue.dueWakeRecipients(now);
    let sent = 0;

    for (const job of jobs) {
      let result = { status: 'missing_target' };
      try {
        const install = await this.resolveInstall(job.recipientInstallId);
        if (!this.queue.hasActiveRecipient(job.recipientInstallId, now)) continue;
        if (install) result = await this.dispatcher.dispatch(install);
      } catch {
        result = { status: 'transient_error' };
      }

      this.queue.markWakeAttempt(
        job.recipientInstallId,
        job.wakeAttempts + 1,
        nextWakeAt(job),
        job.selectedThroughRelaySeq,
        now
      );
      if (result.status === 'sent') sent += 1;
    }

    return { attempted: jobs.length, sent };
  }
}
