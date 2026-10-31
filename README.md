# API Testing Copilot

Generate API test scenarios from an OpenAPI contract, inspect their evidence, approve a request, and see the observed HTTP result. Built with **Java 21, Spring Boot, Spring AI, PostgreSQL/pgvector, and React**.

The central constraint: a model can suggest a test, but application code decides whether the suggestion is valid and what it may execute.

## Run the working demo

Requirements: JDK 21 or newer; Node 20.19+ or 22.12+; internet for the first dependency download. Maven is included through the Apache Maven wrapper. The default demo needs no database, Docker, or provider key.

Terminal 1, from the repository root:

```sh
./mvnw spring-boot:run
```

Terminal 2:

```sh
cd frontend
npm ci
npm run dev
```

Open [the local app](http://127.0.0.1:5173). Load the sample API, import it, select `POST /products`, and generate scenarios. Select the minimum-price case, review the request body, check approval, and execute. Its **expected 201 / actual 400** result reveals an intentional sandbox defect: the contract permits price zero, while the fixture rejects it. Click **Explain this result** for a diagnosis. Try the positive and missing-field cases too.

The sandbox is a disposable fixture: product 1 exists, other IDs return 404, and POST returns a response without storing a product. It is not a commerce backend.

## Import and edit requests

Drop a `.json`, `.yaml` or `.yml` specification onto the import area, choose a file, or paste its contents. Files are read locally into the editor before you click **Import contract**. The same 60,000-character contract limit applies; malformed JSON/YAML reports a line and column when available.

After generation, select **Edit request** to change the body, documented query parameters or expected response status. The form editor preserves JSON value types; JSON view supports nested, array and scalar bodies. **Reset edits** restores the last saved request and **Cancel** discards the draft. Method, path and source references remain fixed to the generated scenario. The backend applies the same scenario validation to saved edits as to generated plans; deliberately invalid business inputs remain possible for negative tests.

Each saved edit increments that scenario's revision. Execution requires the exact reviewed revision and explicit approval; outdated edits or execution requests receive HTTP 409. The UI clears approval after editing and labels previous results with their original revision. Executions retain the request snapshot that actually ran. Generated titles, categories and rationale remain contextual suggestions after an edit, not a new assessment of the edited request. Revisions and execution snapshots are saved in the workspace database.

![Demo request review and boundary failure](docs/demo-screenshot.jpg)

## Saved projects and history

Importing a contract creates a project. Importing again while a project is selected creates its next specification version; **New project** starts a separate workspace. Select saved projects or specification versions, rename a project, and reopen earlier plans from **Saved plans and execution history**. Two run selectors show the exact historical request/assertions and recorded results side by side. Historical results retain their original revision even after edits. Browser refresh restores the selected project and last plan; approval always starts unchecked. Unsaved draft edits are not persisted.

Both modes use an embedded H2 file database at `.tools/workspace.mv.db` by default. No database server or key is required for the demo. SQL migration `db/workspace/V1.sql` creates related project, specification, plan, revision, execution and analysis tables with foreign keys and cascading deletion. A migration ledger records the installed version; JSON payloads preserve domain snapshots. Tests use isolated memory/temporary databases rather than your workspace. Back up the database with the backend stopped. Only one backend process may open the default file at a time.

Project data is retained until explicitly deleted. The UI asks you to confirm deletion. In AI mode evidence is removed by specification metadata before relational deletion. A persisted deletion marker prevents reuse if vector cleanup fails; **Retry deletion** completes cleanup after connectivity recovers. This is retryable coordination, not a transaction spanning H2 and pgvector. A crash during indexing before import publication can still leave orphan vectors and needs separate maintenance. Data from the previous in-memory implementation cannot be recovered after its process exits.

Workspace JDBC settings are `copilot.storage-url`, `copilot.storage-user` and `copilot.storage-password`, separate from the AI vector database settings. The verified default is embedded H2. Keep `.tools/` private: it contains imported documents and response snapshots. Switching between demo and AI keeps saved records, but imports indexed only in demo must be reimported as a new version to create AI vectors. AI imports are not automatically re-embedded at startup.

## Development API environments and durable approvals

The execution environment defaults to the bundled sandbox. A project can also have named local development environments with a base URL, authentication mode and header references. This release allows only HTTP origins with numeric host `127.0.0.1`, ports 1024–65535, and an exact entry in the server's `copilot.allowed-origins` setting. The default allowlist is `http://127.0.0.1:8081`. Configure a comma-separated list through `COPILOT_ALLOWED_ORIGINS` and restart to change it. DNS names, remote/private-network hosts, URL credentials/query/fragment, encoded prefixes and redirects are excluded from this first development-target implementation. Specification `servers` URLs never select a target.

Try a second controlled API in another terminal:

```sh
python3 scripts/dev-api.py
```

In **Execution environment**, expand **Add or update a development environment**, keep the default base URL and authentication None, confirm authorization, and save. Select **Check connection with HEAD**, then review a request and approve execution against the displayed URL. The second API accepts zero-price products and stores created products in disposable memory until it restarts. It is a development fixture, not a deployed backend. HEAD checks establish reachability only and can return authentication or method errors from your own API.

Bearer and API-key modes accept a **server environment-variable name** such as `MY_DEV_API_TOKEN`; they never accept or return the secret itself. Set that variable in the backend process environment before starting it. For the supplied fixture, `DEV_API_TOKEN` enables optional bearer authentication when set in both processes. Additional headers map names to variable references, for example `{"X-Test-Header":"MY_TEST_HEADER"}`. Values are resolved only on the server. Hop-by-hop, Host and proxy authorization headers are blocked. Environment configuration is saved with a revision; changes invalidate unused approvals.

Configured credential values are masked in captured request/response evidence and assertion results before persistence or the execution tool returns. Sensitive cookie/authorization response headers are masked. Raw credentials are not stored in environment records, logs or provider prompts by this feature. This redaction cannot identify every unrelated or transformed secret an API might return; use synthetic development data and keep secrets out of ordinary contracts, bodies and assertions. The database is local and is not configured as an encrypted credential vault.

The UI first creates a durable approval for the exact plan/scenario revision and environment. Approvals expire after ten minutes and include a request/credential fingerprint, environment revision and target. Execution requires that approval ID. An attempt is committed as STARTED before sending HTTP; completion stores the result and COMPLETED state in one transaction. A repeated submission with the same matching approval returns its saved completed result. Failures after an attempt starts are conservatively UNKNOWN; interrupted STARTED attempts become UNKNOWN at startup. UNKNOWN approvals never execute again. Inspect the API's actual state before deliberately editing/reviewing a new request and issuing a fresh approval. This prevents duplicate submission through one approval; it does not promise exactly-once side effects across new approvals, processes or target systems.

The browser retains the pending approval ID for an action so a network retry can reuse it. Selecting another environment clears the visible approval; it is not restored as checked after refresh. Completed executions and history display their target. No application-level automatic retry is used for writes. HTTP execution has a five-second deadline covering response capture; capture stops after 8,192 bytes plus one truncation lookahead byte.

API clients now call `POST /api/approvals` with `{planId, scenarioIndex, expectedRevision, environmentId, approved:true}` (`environmentId:null` selects the sandbox), then send its `id` as `approvalId` to `/api/executions` with the same fields. Missing durable approval is rejected. `GET /api/approvals/{id}/attempt` returns NOT_STARTED, STARTED, COMPLETED or UNKNOWN and the completed execution ID when available. Saved approval/attempt records cascade with project deletion. The default workspace remains a single-process local application.

## Contract rules and missing tests

The **Contract rules and missing tests** panel links supported request probes to the exact specification version and JSON Pointer in the original contract. Local references link to their component definitions. Expand **View exact contract rule** to see the source value highlighted; **Review case** selects a matching saved scenario. This works for existing saved plans as well as new ones.

Supported probes cover required top-level JSON body fields, inclusive numeric minimum/maximum and one unit outside those bounds, bounded string minLength/maxLength and one character outside, and enum examples (first valid value and an excluded string when applicable). Numeric fields with exclusive bounds/multipleOf or fractional integer bounds, strings with pattern/format, nested request rules and other schema constructs are outside this first coverage map. Negative cases require an explicitly documented HTTP 400; successful probes require a documented 2xx. Enumeration and length probes are bounded; length limits above 1,000 are omitted. Reports contain at most 64 probes.

States are **MISSING**, **GENERATED**, **PASSED** and **FAILED**. Matching uses the saved input probe and expected status, not a model-supplied coverage label. Results count only when their scenario revision matches the current saved case. For matching cases with executions, the latest run per case across targets determines pass/fail (inspect the recorded target when comparing environments); any nonpassing result makes the row failed. A passing row means its configured assertions passed, not that the source rule or all business behavior has been proved correct. Unsupported rules are outside the denominator; this is separate from category coverage and code coverage.

**Generate missing tests** appends deterministic reviewable cases to the same plan without replacing edits, assertions or existing revisions. The endpoint requires the complete current revision list. Repeated calls avoid duplicate represented probes; when a previously generated case is edited away from a probe, a newly named case can fill the gap while preserving the edit. Plans still hold at most 12 cases; remaining gaps stay visible. Fixture assumptions and unrelated constraints still require review, particularly for unfamiliar APIs. Generation does not execute the added requests.

The panel also flags a deliberately narrow class of possible documentation conflicts: an explicit `field > number` statement that excludes a documented inclusive numeric minimum. It shows both the contract statement and exact documentation excerpt. **Use the contract for this version** requires a reason and permits generation/execution. **Contract needs revision before testing** records that decision and keeps testing blocked; import a corrected version to change the contract. Unresolved conflicts also block generation and execution at the backend. This is deterministic pattern detection, not general natural-language contradiction detection. Decisions persist under the specification version and never silently rewrite the contract.

Workspace schema migration V2 adds version-scoped conflict decisions. Existing V1 workspace records are preserved. Deleting a project cascades deletion of its decisions with the rest of its data.

## Response assertions

Select **Edit request**, then **Add response check**. HTTP status equality is always evaluated. Additional checks support exact header equality, JSON field existence, JSON types, JSON equality, inclusive numeric lower/upper bounds and a bounded JSON schema subset. JSON targets use JSON Pointer: `/price`, `/items/0/id`, or an empty pointer for the whole response. Escape `/` as `~1` and `~` as `~0` in property names. Numbers compare numerically; strings are never coerced into numbers. Existing JSON `null` is different from a missing field.

For the positive Catalog request, add **JSON value equals**, target `/price`, expected `999`. Save and approve the new revision: HTTP 201 passes, but the overall test fails because the actual price is 79. The result displays each check's outcome, expected value, actual value and explanation. **Explain this result** uses the recorded checks. Expand response headers or the executed request snapshot for more detail.

**Use documented response schema** copies the selected status's `application/json` schema into an editable check when one exists. Supported validation keywords are `type` (one type), `properties`, `required`, `items`, `enum`, `minimum`, `maximum`, `nullable` and boolean `additionalProperties`. Annotation keywords such as descriptions/examples are ignored. Other keywords, malformed schemas or schemas beyond the bounded complexity return **UNEVALUABLE**, never a silent pass. This is an explicit subset, not complete OpenAPI/JSON Schema validation. Schema assertions are reviewed expectations, not proof of business correctness.

Results distinguish **PASS**, **FAIL** and **UNEVALUABLE**. Every configured check must pass for overall success. JSON checks on invalid/empty JSON or responses exceeding 8,192 captured bytes are unevaluable; status/header checks still run. Network failures return an execution error rather than a fabricated assertion result. Header names are case-insensitive; values compare exactly (multiple values joined with comma and space), including content-type parameters.

The edit API accepts an optional `assertions` array. Omit it to preserve current assertions; send `[]` to remove them. For example:

```json
{"expectedRevision":1,"query":{},"body":{"name":"Keyboard","price":79},"expectedStatus":201,
 "assertions":[{"kind":"JSON_GTE","target":"/price","expected":0},
               {"kind":"JSON_TYPE","target":"/name","expected":"string"}]}
```

Limits: 20 additional assertions per scenario, 300-character targets, 8,000-character expected JSON values. Assertion edits increment the same request revision and require new approval. Execution snapshots include the exact assertions, response headers, a `bodyTruncated` indicator and `assertionResults`. Existing clients must derive overall success from all recorded check outcomes instead of status equality alone.

## Demo and AI modes

| Behaviour | Demo (default) | AI (`ai` profile) |
| --- | --- | --- |
| Generation | Deterministic schema-derived cases | Spring AI `ChatClient` and typed `Draft` conversion |
| Retrieval | Exact lookup within imported endpoint | OpenAI embeddings, pgvector cosine search, HNSW index |
| Evidence | Endpoint contract and documentation | Contract anchor plus top 4 endpoint-filtered semantic matches |
| Execution | Approved scenario sent directly to sandbox | Model invokes a zero-argument tool bound to the approved scenario |
| Analysis | Deterministic explanation | Model explanation using the recorded result, with no tools |
| Storage | Embedded workspace database | Embedded workspace database plus PostgreSQL vectors |

Both modes enforce the same scenario validation, explicit execution approval, application-owned target selection, sandbox method/path allowlist, no redirects, request timeout, response size limit, and workspace capacity limits. Source citations are checked against the retrieved context. A citation does **not** prove that an assertion is correct.

## Enable AI mode

Docker with Compose is needed for the supplied database setup. Stop an existing backend before restarting it in AI mode.

```sh
docker compose up -d --wait
# Set OPENAI_API_KEY securely in this terminal's environment (never commit it).
export OPENAI_MODEL=gpt-4o-mini
./mvnw spring-boot:run -Dspring-boot.run.profiles=ai
```

The model is configurable; the default is a compatible baseline rather than a claim about the latest model. Embeddings use `text-embedding-3-small` with 1536 dimensions. Changing embedding models requires a matching dimension/schema and reindexing. Database overrides are in [.env.example](.env.example); Spring Boot reads **environment variables**, not that file automatically. Compose reads `.env` for its password override. Keep the values consistent.

AI mode sends imported contract/documentation text and observed sandbox responses to the configured provider and may incur usage charges. Use synthetic project data for the demo. There is no silent fallback from AI mode to demo mode.

**Verification status:** the demo, frontend build, fake-provider structured-output/tool protocol, and real PostgreSQL/pgvector integration have been checked locally. Database tests cover vector dimensions, HNSW index creation, cosine ranking, persisted rows and specification/operation filtering using synthetic embeddings. Live provider verification is incomplete: the embedding API rejected the smoke check because API credit was exhausted. Model quality and production performance have not been benchmarked.

For a private local configuration, create `.env.ai.properties` (ignored by Git):

```properties
OPENAI_API_KEY=your-key-here
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/copilot
DATABASE_USER=copilot
DATABASE_PASSWORD=copilot-local
```

Start with `./scripts/run-ai.sh`. The script explicitly loads this file without putting the key in command arguments. Ensure the database URL matches your setup; the dedicated database on this development machine uses port **55432**, while Compose uses **5432**.

To reproduce a live smoke check after configuring API billing, run `python3 scripts/smoke-ai.py` while AI mode is running. It imports the synthetic sample, retrieves evidence, generates a validated plan, checks approval enforcement, invokes the approved execution tool and requests analysis. It incurs provider usage and writes its result under the ignored `.tools/` directory. Credit/quota failures produce a fixed, actionable error without exposing provider payloads.

## Verify

```sh
./mvnw verify
cd frontend
npm ci
npm test
npm run build
```

The default backend suite covers local reference resolution, prohibited remote references, recursive schemas, scenario validation, source isolation, actual sandbox HTTP execution, approval enforcement, provider error sanitization, typed model responses, and tool invocation with synthetic provider responses. HTTP tests bind random localhost ports. CI runs the suite on JDK 21, builds React on Node 24 and runs a database integration job against pgvector.

For database verification, start PostgreSQL/pgvector, then run:

```sh
./mvnw -B -ntp -Pintegration verify
# Dedicated local development database:
TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/copilot ./mvnw -B -ntp -Pintegration verify
```

Optional test overrides: `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD`. Database tests create a unique schema and remove it after the run; application tables are not truncated. They use synthetic 1536-dimensional vectors and make no provider calls.

## API

All JSON endpoints are under `/api`. Error responses use Spring `ProblemDetail`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/info` | Mode, target and storage information |
| GET | `/api/projects` | List saved projects and pending deletions |
| GET | `/api/projects/{id}` | Project versions, plans, revision snapshots, runs and analyses |
| PUT | `/api/projects/{id}` | Rename using `{name}` |
| DELETE | `/api/projects/{id}` | Delete project data and indexed evidence; retry after cleanup failure |
| GET | `/api/specifications/{id}/workspace` | Open the workspace owning this import |
| POST | `/api/specifications` | Import `{name, content, documentation, projectId}`; omit projectId for a new project |
| GET | `/api/specifications` | List saved imports |
| POST | `/api/plans` | Generate using `{specificationId, endpointKey}` |
| GET | `/api/plans/{id}` | Reload the current plan and its `scenarioRevisions` |
| PUT | `/api/plans/{id}/scenarios/{index}` | Save `{expectedRevision, query, body, expectedStatus, assertions}` |
| GET | `/api/specifications/{id}/coverage?endpointKey=...&planId=...` | Coverage report; omit planId to review before generation |
| POST | `/api/specifications/{id}/conflicts?endpointKey=...` | Save `{conflictId, choice, reason}` |
| POST | `/api/plans/{id}/missing-tests` | Append missing probes using `{expectedRevisions: [...]}` |
| GET/POST | `/api/projects/{id}/environments` | List/create environments with authorization and variable references |
| PUT | `/api/projects/{id}/environments/{envId}` | Update environment using expectedRevision |
| POST | `/api/projects/{id}/environments/{envId}/check` | HEAD reachability check |
| POST | `/api/approvals` | Create ten-minute approval for request and target |
| GET | `/api/approvals/{id}/attempt` | Read durable attempt outcome |
| POST | `/api/executions` | Execute with matching approvalId, environmentId and reviewed revision |
| POST | `/api/executions/{id}/analysis` | Explain a recorded result |
| GET | `/actuator/health` | Backend health |

`expectedRevision` is required for both editing and execution; take it from `plan.scenarioRevisions[scenarioIndex]`. Clients using the older execution payload must supply this field. A successful edit returns the updated plan. Execution responses include `scenarioRevision` and `requestSnapshot`; existing analysis uses the recorded execution even if the plan is edited later.

## Technical documentation

- [Architecture and data flow](docs/ARCHITECTURE.md) — component boundaries and design tradeoffs.
- [Evaluation protocol](docs/EVALUATION.md) — what is measured, what is not, and how to collect real model evidence.
- [Architecture decisions](docs/decisions/) — why the project is structured this way.

## Scope and limitations

This is a **single-user localhost application**, not an authenticated production service. It supports a useful subset of OpenAPI 3 JSON/YAML: ordinary operations, local nonrecursive references, parameters, JSON request schemas and explicit numeric response statuses. It does not implement complete OpenAPI/JSON Schema validation, recursive models, reference sibling merging, composed schemas, XML/multipart test generation, OAuth login/refresh flows, or wildcard response-status matching. Demo cases are templates and can require manual fixture adjustments for unfamiliar APIs.

The bundled sandbox allows GET `/products`, GET `/products/{id}` and POST `/products`. Explicitly approved development environments may execute the selected imported operation on their allowlisted local base URL. Specification `servers` URLs and generated hostnames are never used. HTTP status and configured response assertions run deterministically. Response schema validation covers only the documented subset; business-state assertions are future work.

Imports, plans, revisions, executions and explanations survive restarts. Workspace caps are 200 imports, 1,000 plans and 5,000 executions; delete unused projects to free capacity. A single service lock serializes expensive operations, making capacity checks simple but limiting concurrency. Durable single-use approvals and per-approval duplicate protection are implemented. Distributed jobs, multi-process coordination and comprehensive telemetry remain future work.

## References

- [Spring AI 1.1 ChatClient](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html)
- [Spring AI tool calling](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html)
- [Spring AI pgvector integration](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)
- [OpenAI structured output concepts](https://developers.openai.com/api/docs/guides/structured-outputs)

This version uses Spring AI's typed conversion plus application validation. It does not currently enable provider-native strict JSON Schema output.
