# 0.1.0-rc.1 verification record

This candidate is scoped to the single-user local demo. The build generates the machine-specific evidence and timestamps; source documentation describes the checks and their limits.

## Automated checks

- Full backend verification includes parsing, validation, HTTP execution, assertions, workspace migrations/reopen/deletion, credential redaction, durable approval and uncertain-attempt recovery, batch cancellation/restart, report escaping, contract comparison and reviewed-plan preservation.
- The synthetic quality regression adds ten operations and 34 saved cases against independent healthy/mutant fixture constants. It deliberately retains two missed defects. `target/evaluation/demo-results.json` records the actual counters and per-case observations; independent human sign-off is pending.
- Frontend unit tests and production bundling pass. These unit tests cover request/assertion editing and batch outcome preparation; they are not a full UI test suite.
- Python tests cover CLI validation, target authorization, exit codes, stable submission IDs, incomplete outcomes and release jar assembly. Packaging preserves uncompressed nested libraries and refuses symlink inputs.
- Release smoke verification launches the assembled jar from an empty temporary workspace, checks `/api/info` version/mode, the UI index and hashed assets, runs the headless synthetic example, parses JUnit and reopens saved executions after restart. It runs on a random loopback port and does not load a provider key.
- CI additionally verifies real PostgreSQL/pgvector integration, runs the packaged CLI and uploads synthetic results and the release-candidate ZIP. Check the commit's Actions result for the actual remote conclusion.

## Browser checks performed on 2 November 2026

Checked the extracted single-jar bundle at localhost:8082, serving its own UI:

- Keyboard Tab/Enter activated the skip link and focused the main workspace.
- Onboarding guided sample import, generation, explicit approval and the expected zero-price failure.
- Contract comparison identified the changed minimum, showed input 0 beside proposed input 1 with expected status 201 retained, and created a separate reviewed plan.
- Execution of the new plan required a fresh approval and passed; history retained the original failed execution, both request snapshots, exact revision labels and targets.
- At 320 pixels, long normalized rule paths/history buttons initially overflowed. After the fix, document width equalled viewport width (320/320). At 1280 pixels, width also matched (1280/1280) and history used two columns.
- The tested flows produced no captured browser console errors. Temporary viewport overrides were reset after verification.

The screenshots and local workspace used for this check stay outside source control. This replaces the earlier Phase 8 note that browser verification was unavailable at delivery time. It is not an independent usability study, full screen-reader audit, all-browser matrix or WCAG certification.

## Candidate gates still open

- Independent human sign-off of the synthetic corpus and a held-out evaluation set.
- Successful live provider verification, repeated AI generations and measured model quality, token usage, latency and cost. No successful live benchmark is claimed from synthetic provider or demo results.
- Broader user/assistive-technology review and Windows build/install verification.

The local release candidate can be reproduced and tried with these limits. A stable/production release should not be inferred from its versioned bundle or CI success.
