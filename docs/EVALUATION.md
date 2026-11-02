# Evaluation: what is actually measured

## Release-candidate regression corpus

`src/test/resources/evaluation/corpus.json` contains ten authored synthetic operations: inclusive numeric minimum/maximum, string minimum/maximum length, enum, required field, absent resource, required query, response-field correctness and combined numeric bounds. `ReleaseEvaluationTest` uses independent fixture-oracle constants rather than deriving expected HTTP behavior from the generator. It generates one plan per fixture, explicitly fills supported missing probes, then executes each saved case against the healthy and deliberate-defect variants through actual localhost HTTP. It never repairs the generated tests to improve a score.

Run `./mvnw -Dtest=ReleaseEvaluationTest test`. The machine-readable evidence is `target/evaluation/demo-results.json`, including every request, healthy/mutant outcomes, generation-and-fill elapsed milliseconds and observed HTTP durations. Full `verify` and release packaging run it too. CI publishes the result; local generated evidence remains ignored.

The deterministic baseline currently measures:

| Measure | Numerator / denominator | Definition |
| --- | --- | --- |
| Structural acceptance | 10 / 10 operations | Generated drafts accepted by the existing validator |
| Healthy-fixture agreement | 34 / 34 cases | Configured checks pass against the independently authored healthy oracle |
| Misleading healthy failures | 0 / 34 cases | Tests reporting failure against a healthy fixture |
| Supported-probe representation | 24 / 24 probes | Existing supported request probes represented after explicit rule fill |
| Authored behavior representation | 25 / 27 behaviors | Explicit corpus behaviors represented by saved inputs/checks, including absent-resource, missing-query and response-field behavior |
| Useful defect detection | 8 / 10 fixture defects | At least one case passes on the healthy variant and fails on that fixture's mutant |

Two intentional gaps remain: the required-query fixture has no missing-query scenario, and the response-value fixture's status-only generated checks miss a negative price returned with HTTP 201. These are measured deficiencies, not silently excluded samples. Response assertions can be added by a user, but this benchmark does not claim they were generated. Each fixture has a single defined mutant; it does not measure the probability of detecting arbitrary defects.

The corpus has **not received independent human sign-off**. Its metadata says so. Before claiming human-reviewed quality, have a reviewer inspect each contract, fixture oracle, behavior list and mutant independently; record reviewer/date, corrections and disputed expectations in a separate reviewed corpus version. Do not label authored synthetic data as externally validated. There is one deterministic generation per operation, no held-out statistical estimate and no claim of real-world coverage.

The report records generator/configuration, Java runtime, corpus version and measurement timestamp. It makes zero provider requests and has zero provider usage cost. It records live AI verification as NOT_RUN; model quality, live model latency, tokens and provider cost remain unavailable rather than being inferred from the deterministic baseline. The live protocol below remains separate.

## Current deterministic measurements

`PlanEvaluator` reports:

- Total scenario count.
- Counts for POSITIVE, NEGATIVE and EDGE.
- Category coverage: number of represented categories divided by three.
- Citation coverage: proportion of scenarios with at least one cited source.

Because the validator requires source IDs, accepted plans should have full citation presence. This is a structural invariant, not evidence of semantic faithfulness. A plan can score 100% category coverage and still contain weak or incorrect tests.

## Current automated evidence

| Check | What it demonstrates | What it does not demonstrate |
| --- | --- | --- |
| Reader tests | Bounded subset parsing and reference refusal | Complete OpenAPI compliance |
| Validator tests | Method/path, status and source membership checks | Semantic correctness of a rationale |
| Demo HTTP integration | Actual approve/run/status comparison against localhost fixture | Real-world API coverage |
| Fake-provider structured output | Spring AI parses a synthetic provider response into records | Live model output reliability |
| Fake-provider tool invocation | Bound tool can be invoked and return an observed result | Live model tool-selection rate |
| Retrieval filter test | Both metadata constraints are sent to the vector store | Actual pgvector query/index behaviour or recall |
| Real pgvector integration | 1536-dimensional storage, cosine HNSW index creation, similarity ordering, persisted rows and metadata-filtered queries | Live embedding quality, index recall at scale or model accuracy |
| Provider error tests | Known quota, key and rate-limit failures become fixed messages without raw provider text | Availability of provider credits or live model output quality |
| Frontend production build | React source can be bundled | Full browser automation or accessibility conformance |

The browser workflow was manually checked with sample import, generation, request review, approval, HTTP boundary failure and explanation. The saved screenshot illustrates that local demo. It is not a live AI benchmark.

## Collect a small real evaluation set

Start with ten hand-reviewed operations. Include request-body validation, absent resources, numeric minima/maxima, required query parameters, enums, optional fields, pagination, authentication requirements, and a misleading documentation paragraph. Keep the expected cases in version control and use synthetic fixture data.

For each operation, record:

1. Explicit contract facts and source IDs.
2. A list of positive, negative and boundary behaviours that a human expects.
3. Which cases are executable in the fixture environment.
4. Unsupported or ambiguous assumptions.

Run the deterministic generator and AI generator on the same cases. For AI, repeat at least three times and record the model identifier, prompt commit, embedding model, chunk settings, top-K, temperature and timestamp. Do not cherry-pick successful generations.

Track separately:

- **Structural pass rate:** drafts passing the validator / all generation attempts.
- **Unsupported assertion rate:** assertions lacking contract support / generated assertions, human-reviewed.
- **Useful boundary recall:** expected boundaries represented correctly / known useful boundaries.
- **Duplicate rate:** semantically duplicate scenarios / generated scenarios.
- **Retrieval recall@K:** relevant evidence chunks in top-K / expected relevant chunks.
- **Execution agreement:** assertions matching the reviewed fixture behaviour, with intended defects annotated.
- **Latency/cost:** per-request and aggregate measurements from the real provider, including failed attempts.

Use a fixed regression set plus a held-out set. Improvements to prompts or retrieval should be evaluated against both. Add a failing example to regression tests whenever a useful defect is found.

## Remaining verification

- Complete the live smoke check once API credit is available. A check on 2026-10-28 reached the embedding API, which returned `credit_balance_exhausted` / `insufficient_quota`; subsequent generation, tool calling and analysis remain unverified against the live provider.
- Test refused/truncated/malformed provider outputs and throttling with real response shapes.
- Compare model generations against a human-reviewed dataset before reporting a quality percentage.

## Reproduce database verification

With a running PostgreSQL/pgvector database, run `./mvnw -Pintegration verify`.
`TEST_DATABASE_URL`, `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD` override the test connection.
The suite uses a unique test schema with synthetic vectors and cleans up that schema after testing.
This exercises the real vector-store adapter and database queries without a paid provider.

`python3 scripts/smoke-ai.py` separately checks the live workflow and records completed stages in `.tools/live-ai-report.json`. A failed report must not be described as a completed live verification.
