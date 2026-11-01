# Headless execution and result artifacts

The Python 3 CLI runs reviewed saved configurations through a running localhost backend. It uses the same target policy, conflict checks, exact revisions, ten-minute approvals and batch dispatcher as the browser. It neither generates test expectations during `run` nor supplies credentials in command arguments/config files.

## Run the synthetic example

Start the demo backend with `./mvnw spring-boot:run` in one terminal. In another:

```sh
python3 scripts/copilot-ci.py prepare-demo --config .tools/ci-demo.json
python3 scripts/copilot-ci.py run --config .tools/ci-demo.json \
  --authorize-target sandbox --output .tools/ci-demo-run-1
```

`prepare-demo` imports synthetic Catalog data, generates a saved plan and writes its current IDs/revisions to a local configuration. It refuses AI mode and does not execute tests. The bundled sandbox deliberately fails the zero-price boundary, so the example returns **exit 1**, with two PASSED cases and one FAILED case. The output contains `submission.json`, `results.json` and `junit.xml`.

Review the boundary report in the browser or inspect `results.json`. To run a passing subset, copy the config, keep only its first two cases, and choose a **new output directory**. A passing selection exits 0. The application contract is not changed to hide the known boundary defect.

## Run your saved selection

Download the plan JSON to obtain its `id` and `scenarioRevisions`. Environment IDs appear in `GET /api/projects/{projectId}/environments`. Create a local config containing only these fields (replace the illustrative UUID with your saved plan ID):

```json
{
  "planId": "39ca1ec9-91de-485c-8a80-93332825128f",
  "environmentId": null,
  "cases": [
    {"scenarioIndex": 0, "expectedRevision": 1},
    {"scenarioIndex": 2, "expectedRevision": 1}
  ],
  "parallelism": 1,
  "independent": false
}
```

Use `environmentId:null` with `--authorize-target sandbox`. For a saved development environment, set its ID and use the **exact** base URL, including any path prefix:

```sh
python3 scripts/copilot-ci.py --server-url http://127.0.0.1:8080 run \
  --config .tools/reviewed-tests.json \
  --authorize-target http://127.0.0.1:8081 \
  --output .tools/reviewed-run-1 --timeout 120
```

The backend must already allow that numeric-loopback origin and have the referenced credentials in its process environment. Supply those variables through the CI secret mechanism before starting the backend. The CLI does not copy secrets to the client or change the server allowlist. Configure an isolated synthetic fixture for writes. Different batches can interleave; this phase does not provide fixtures, dependencies or cleanup.

Every case must specify its exact current revision. Stale revisions or a target mismatch fail before POST. The CLI also binds the observed environment revision and target URL in the batch payload; a change between preflight and server approval is rejected. Parallelism 2 requires `independent:true` and GET/HEAD cases only. The server remains authoritative.

## Submission identity and interruption

The CLI writes a client-generated job ID to `submission.json` **before** its single POST. Repeating the same command with the same configuration and output directory reuses that ID, and the server returns the existing job. It does not start another run. A changed config/target is refused in that directory.

If a POST response is lost, retain the output directory. Inspect `GET /api/batches/{id}` using the ID from `submission.json`, or invoke the same command to resubmit the same identity. Do not use a fresh directory to retry an uncertain write. A deliberate new run uses a new directory only after reviewing the target's state. IDs and plans belong to that backend workspace; copying a config alone does not seed an empty workspace. Use `prepare-demo` for a fresh synthetic CI fixture, or provide the intended persistent workspace separately.

Exit codes:

| Code | Meaning |
| --- | --- |
| 0 | The job completed and every selected case PASSED |
| 1 | A test failed, a case was incomplete/uncertain/blocked/cancelled, or the polling deadline expired |
| 2 | Invalid configuration, refused authorization, transport/protocol or artifact failure |

`--timeout` bounds polling after submission (default 120 seconds, at most 3600). Expiry requests cancellation once, exports the available snapshot and exits 1. Claimed requests can finish afterward; inspect that batch before another run. Transport failures are not automatically retried. Exports obtained while active are snapshots and fail closed in JUnit for incomplete cases; separately downloaded formats can reflect different moments. CLI responses are capped at 8 MiB; oversized artifacts fail explicitly rather than truncating or fabricating results.

## Report formats

`GET /api/executions/{id}/report?format=markdown|curl|json|junit` exports one recorded result. `GET /api/batches/{id}/export?format=json|junit` exports a selected batch. Reads never execute HTTP requests or call a model provider. JSON includes `schemaVersion:"1"`. Batch JSON contains per-case states and available recorded execution reports, without exposing unused approvals or unfinished raw request snapshots.

JUnit uses one testcase per selected case. Assertion failures become `<failure>`; every other non-PASSED state (including cancellation) becomes `<error>`. The suite records durations in seconds. XML markup and invalid XML control characters in untrusted response text are handled safely. The full observed evidence and reproduction text appear in `system-out` for completed cases.

Markdown separates observed checks from interpretation, links related documented request rules by specification ID/version/JSON Pointer, and retains the original request/assertion revision. Related rules provide provenance, not a claim that all requirements were tested. The root cause remains unestablished unless investigated separately. Truncated capture is labelled.

The cURL download is a POSIX `sh` script. Request literals are shell-quoted, JSON travels through a quoted heredoc, query values are percent-encoded, URL globbing and redirects are disabled, and numeric-loopback target selection stays application-owned. Authentication/header values use `${VARIABLE:?Set VARIABLE privately}` references; actual credentials are not embedded. The script prints HTTP status without treating all non-2xx responses as failures because negative tests can expect them. It does not execute the saved response assertions; compare with the report or use the headless runner.

Review before running: reproduction sends a new request and can repeat side effects. If request evidence contains `[REDACTED]`, restore placeholders privately and remove the script's explicit stop guard. Legacy development executions without an authentication snapshot also get a stop guard. Legacy sandbox reproduction uses the backend's current sandbox port and states that limitation. V5 atomically saves the actual base URL and environment configuration (references only) with every new execution's completed attempt; editing an environment later does not rewrite it.

Known credential reflections and sensitive response headers already sanitized during execution stay masked in exports. This is not general secret discovery: ordinary request/schema/response data can still be sensitive, and fixture data, transformed secrets or unrelated secrets may not be recognized. Review artifacts before sharing and keep private configuration under ignored paths.

## GitHub Actions

The backend CI job builds the jar, runs CLI unit tests, starts a separate demo workspace and verifies both failing and passing headless selections. The known boundary failure is an **expected regression fixture**: the workflow explicitly requires CLI exit 1 and the corresponding JUnit failure. It separately requires exit 0 for the passing subset. A new CLI regression fails the workflow.

Only JSON results and JUnit XML are uploaded as `copilot-ci-results`, retained for seven days through the official [upload-artifact action](https://github.com/actions/upload-artifact). Workspace files and credentials are excluded from the upload paths. Synthetic response data is used throughout. Actual GitHub execution status must be checked on the pushed commit; local verification alone does not establish remote CI success.
