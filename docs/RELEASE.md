# API Testing Copilot 0.1.0-rc.1

A local, single-user release candidate for contract-grounded API testing. This bundle contains a backend jar with the React UI, a Python headless runner, a synthetic example and evaluation evidence. No Node, Maven, Docker, database server or provider key is needed to run demo mode after extraction.

## Start

Requirements: JDK 21+ with `java` on PATH. Python 3 is optional for the headless runner. The build/release verification scripts target macOS/Linux with a POSIX shell; Windows interactive packaging has not been verified.

Extract the ZIP, open a terminal in its extracted directory and run:

```sh
java -jar copilot.jar --spring.profiles.active=demo
```

Open [the local workspace](http://127.0.0.1:8080). Load the sample, import the contract, generate `POST /products`, select the minimum-price case, review and approve it, then execute. Expected 201 / observed 400 demonstrates the intentional zero-price fixture defect. Inspect the failed check and preview its report. Demo generation is deterministic and makes no model-provider calls.

Keep the terminal running. Stop with Ctrl-C. Saved projects live under `.tools/workspace*` relative to the directory where you start the jar. Start from the same directory to reopen them. Choose another port with `--server.port=8082` if 8080 is occupied, then open that port. The backend remains bound to numeric loopback; this is not a shared/public server.

For headless checks:

```sh
python3 scripts/copilot-ci.py prepare-demo --config .tools/reviewed-demo.json
python3 scripts/copilot-ci.py run --config .tools/reviewed-demo.json \
  --authorize-target sandbox --output .tools/demo-run-1
```

The known boundary failure makes this example exit 1. See `docs/CI.md` for intentional new runs versus resubmitting an existing job, credentials, exit codes and artifact formats.

## Verify integrity

The adjacent `.zip.sha256` checks the archive. `SHA256SUMS` lists the extracted files and `manifest.json` records the source commit, whether source was dirty, packaged smoke checks and individual content hashes. On macOS, `shasum -a 256 -c SHA256SUMS` verifies the extracted files; Linux can use `sha256sum -c SHA256SUMS`. Checksums detect changes; they are not a cryptographic publisher signature. Setup and verification are reproducible, but ZIP bytes vary with build timestamps, runtime and measured latency; this is not a bit-for-bit reproducible-build claim.

The build performs a clean dependency install, frontend tests/build, full backend suite and Python tests. Its smoke check launches the assembled jar in an empty temporary workspace, fetches the UI and hashed assets, executes the synthetic CLI batch, parses JUnit and restarts the process to verify saved executions. Java classpath resources and dependency compression modes are preserved when UI assets are bundled. Runtime demo use needs no external fonts or model service.

## Evaluation and limitations

The bundled `evaluation/demo-results.json` records the actual run, configuration, per-fixture observations and latency. The authored corpus covers ten synthetic operations with independent healthy/defective fixture oracles. The baseline produces 34 cases, agrees with 34 healthy outcomes, represents 24/24 supported probes and 25/27 authored behaviors, and detects 8/10 deliberately introduced defects. It misses missing-required-query behavior and a response-value defect with correct HTTP status. The corpus is authored and reproducible; independent human sign-off remains pending. These numbers are not general API coverage or AI accuracy claims.

Live AI model quality remains unverified because the last live verification lacked provider credit. There is no new paid provider run in this candidate. Model latency, token usage and live provider cost are not available; the deterministic run makes zero provider requests. See `docs/EVALUATION.md` for definitions and the review procedure.

This candidate preserves the existing bounded OpenAPI/response-schema subsets, local target allowlist, revision-bound approvals, uncertain-outcome handling, two batch workers, version comparison and report exports. Authentication, multi-user isolation, remote target support, full JSON Schema compliance, shared/distributed queues, encrypted data storage, stateful fixture orchestration and production hardening remain outside this release. Ordinary request/response artifacts may contain sensitive fixture data; review before sharing.

## Upgrade and backup

Stop the previous backend before replacing its jar. Back up the entire `.tools` workspace directory while stopped; in AI mode also back up the separate vector database. Keep the backup outside the release directory. Never run two backends on the same H2 file. V1–V6 migrations preserve saved records; restore a copy when testing an upgrade, and retain the old jar/backup until verification succeeds. Downgrading a migrated workspace is not supported—restore the old backup instead.

The clean-workspace restart smoke test complements the existing migration, deletion, approval interruption and batch recovery regression tests. It is not proof of every future schema upgrade or abrupt hardware/storage failure.

## Accessibility and review status

The UI includes a skip link, visible keyboard focus, labelled form controls, button-based view selection, task/status messages, larger review text and narrow-screen grid safeguards. Onboarding explains import, generation, approval and the sample failure. These changes still require broader independent user and assistive-technology review; no WCAG conformance claim is made. See the bundled `docs/RELEASE-VERIFICATION.md` record for the checks actually performed.

This is a release candidate, not a production certification or claim that all planned future work is complete. No GitHub release/tag is created by the local packaging script.
