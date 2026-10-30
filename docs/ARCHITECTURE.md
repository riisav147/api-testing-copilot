# Architecture

```mermaid
flowchart TD
    U[React: import, review, approve] --> C[API controller]
    C --> S[Copilot service: saved workspace]
    S --> P[Bounded OpenAPI reader]
    S --> K[Knowledge interface]
    K --> D[Demo exact lookup]
    K --> V[AI: embedding model and pgvector]
    S --> G[Scenario generator]
    G --> T[Demo templates]
    G --> L[AI ChatClient: typed Draft]
    L --> Q[Scenario validator]
    T --> Q
    Q --> E[Plan evaluator]
    S --> A[Explicitly approved stored scenario]
    A --> W[Direct demo workflow or scoped AI tool]
    W --> R[Sandbox runner]
    R --> M[Fixed loopback mock API]
    M --> O[Observed execution]
    O --> F[Analysis with no tools]
```

## Generation lifecycle

1. HTTP Bean Validation checks import size and required fields.
2. The reader normalizes the contract without fetching external resources.
3. The service creates a specification ID and indexes its evidence. It publishes the import only after indexing returns successfully.
4. Generation selects an endpoint belonging to that import.
5. Retrieval scopes evidence by specification ID and operation key. AI mode additionally ranks matching vectors.
6. The generator creates a typed draft. It has no execution tools.
7. The validator checks shape and contract/evidence invariants. Invalid drafts are not stored.
8. The evaluator counts categories and citation presence; the plan is saved in memory and returned for review.

## Execution lifecycle

1. The browser displays the generated request and requires explicit approval.
2. The server checks approval again, resolves the stored plan, and checks the scenario index and reviewed revision. An outdated revision returns HTTP 409 before any execution tool is invoked.
3. In AI mode a per-request tool wraps that fixed action. It accepts no model-supplied arguments and can attempt HTTP execution only once.
4. The runner allows only the bundled fixture's method/path combinations. It never uses OpenAPI `servers` values or a user-provided host.
5. An HTTP client sends to `127.0.0.1` on the running application's port. Redirects are disabled, timeout is five seconds, and a lookahead byte detects truncation beyond the 8192 captured response bytes.
6. The deterministic assertion engine evaluates mandatory status equality and the saved response assertions. Overall success requires every result to be PASS. The execution stores the scenario revision and request snapshot separately from the editable plan.
7. Analysis receives a recorded result and no tools. It cannot execute another request.

## Editing lifecycle

Generated scenarios start at revision 1. The editor sends the expected revision together with a replacement query map, body and expected status. Method, path, title, category, rationale and sources are retained from the saved scenario. The generated descriptive fields remain context rather than a fresh interpretation of the user's edited request.

The service checks the revision and validates the replacement against the selected endpoint and retrieved evidence. Only a successful save replaces the plan entry and increments the edited scenario's revision. Invalid edits leave the saved plan unchanged. Edits and executions use the same service lock so concurrent edits cannot both overwrite the same revision and an edit cannot change an action during execution.

The browser disables execution while the draft editor is open and resets approval after edits. Old results remain labelled with their original revision and do not count toward execution of the current revision. A conflict offers an explicit discard-and-reload action. This is optimistic concurrency protection in one process, not a durable approval record, access control or cross-request idempotency.

## Where the data lives

| Data | Demo | AI |
| --- | --- | --- |
| Specification metadata | H2 plus memory cache | H2 plus memory cache |
| Evidence chunks | Concurrent map | pgvector document table |
| Plans and revisions | H2 plus memory cache | H2 plus memory cache |
| Executions and analyses | H2 plus memory cache | H2 plus memory cache |
| Embeddings | None | PostgreSQL, 1536-dimensional vectors |
| Provider key | Not needed | Environment variable, never returned by the API |

`WorkspaceStore` initializes a versioned SQL migration and loads saved records into the service at startup. Foreign keys connect specification versions to projects, plans to specifications, revision snapshots and executions to plans, and analyses to executions. Plan edits atomically update the current plan and append an immutable revision snapshot. Demo evidence is rebuilt from saved contracts/documentation; AI evidence remains in pgvector without paid startup reindexing.

Project deletion first persists a tombstone, then removes all vectors for each specification, then cascades relational deletion and clears caches. A vector cleanup failure leaves the project unavailable and retryable, including after restart. Import indexing and relational publication still span separate stores: a crash can leave orphan vectors. Successful external HTTP execution and result persistence are also not atomic; a database failure after a side effect requires investigation rather than a blind retry.

The embedded database keeps the demo setup simple and holds all records in one process. It is not configured for shared service deployment. Records are retained until project deletion; bounded workspace caches remain, while plan revision snapshots can grow until deletion. Production work includes pagination, finer retention controls, backups, schema upgrade policies, and durable job/idempotency states.

## Important tradeoffs

**Modular monolith:** one process keeps orchestration understandable. It avoids service-network failure modes while the domain is small. [Decision 1](decisions/0001-modular-monolith.md).

**Manual AI configuration:** only the `ai` profile creates provider/database beans. Default startup cannot accidentally call a paid provider or require a database. Versions are pinned to a compatible Spring Boot 3.5 / Spring AI 1.1 pair.

**Deterministic baseline:** demo cases help establish whether additional LLM complexity produces useful coverage. Exact lookup is labelled honestly rather than described as semantic retrieval.

**Filtered retrieval plus contract anchor:** the explicit operation contract remains in context even if prose ranks above it. Supporting prose is duplicated per operation, which is simple but storage-inefficient for larger documents. A future design should split shared documentation and use metadata references.

**Prompt-based typed conversion:** Spring AI generates formatting instructions and converts output to a record. Provider-native strict JSON Schema is not enabled. Malformed or domain-invalid output fails closed; automatic repair is not attempted.

**Coarse service synchronization:** capacity checks and writes are simple and deterministic, but slow provider calls serialize unrelated requests. A production design should use durable jobs, bounded queues, cancellation, per-resource locking and quotas.

**Deterministic assertions:** `AssertionEngine` evaluates typed header/JSON checks after bounded response capture. JSON Pointer selects fields without running expressions or scripts. Missing fields, wrong values and wrong types fail; invalid/empty/truncated JSON and unsupported schema rules are unevaluable. Header and status checks remain available independently. An overall result passes only when every configured check passes. The schema checker first scans the complete schema definition for unsupported keywords, so unsupported rules in absent optional properties cannot silently pass. Business state and side effects remain outside these checks.

## Security boundary and remaining gaps

This app binds to loopback and has no login or tenant isolation. Endpoint metadata filtering is not access control. Do not expose the development servers publicly. Contracts, documentation and responses are treated as untrusted text in prompts. The stronger boundary is the absence of execution tools during generation, explicit approval before execution, and application-owned target selection.

The tool guard covers repeated calls within one model invocation. It does not cover duplicate user submissions, retries by clients, crashes between a network side effect and storing its result, or a second process. Durable idempotency would require a unique request key, state transitions, a persisted request hash and careful handling of uncertain network outcomes.

Local input bounds reduce accidental resource use, but this is not a hardened multi-user ingestion service. Production requirements include strict HTTP body limits, full schema validation, authentication, encryption/access policies for documents, provider data policies, outbound network isolation, database lifecycle migrations and audited approvals.
