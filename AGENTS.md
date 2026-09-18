# Codex cost-aware routing

Use the cheapest agent that can reliably complete each delegated task.

- Use `cost_explorer`, `ci_checker`, and `docs_researcher` for bounded read-only work: repository mapping, file discovery, CI polling, failing-step extraction, and documentation checks.
- Use `implementer` for normal feature work, routine debugging, tests, and scoped refactors.
- Use `reviewer` for correctness/security/regression review.
- Escalate to `architect` only for cross-module architecture, unresolved root cause after normal investigation, security/privacy/safety-sensitive decisions, concurrency or data-integrity risks, difficult migrations, or production-impacting design.

Before escalating for task size alone, split independent work and keep cheap read-only discovery separate from implementation.

Do not select Astra/Fast or another higher-cost model unless the user explicitly requests it or the available configured agents are demonstrably insufficient.

Cost optimization never overrides repository safety rules, user instructions, TDD requirements, required regression tests, exact-SHA CI verification, branch protections, or production safeguards. Never weaken or skip validation to save credits. Never call queued or in-progress CI green or complete.

Keep subagent outputs concise and evidence-driven. Avoid redundant repository scans, repeated status polling, and duplicate analysis.
