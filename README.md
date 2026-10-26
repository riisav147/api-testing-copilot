# API Testing Copilot

A learning-focused Java 21 application for generating API test scenarios from OpenAPI contracts. Features are developed as runnable milestones with tests and architecture notes.

## Development approach

1. Parse and normalize an OpenAPI contract without making network requests.
2. Retrieve endpoint-scoped evidence and generate positive, negative and edge cases.
3. Validate generated scenarios against the contract and retrieved source IDs.
4. Execute a reviewed scenario only against the bundled local sandbox.
5. Evaluate coverage and analyse failures with bounded tool access.

Two modes: `demo` uses deterministic fixtures and in-memory retrieval; `ai` uses Spring AI, OpenAI embeddings/chat, and PostgreSQL/pgvector. Demo output is never presented as model output.

Build: `./mvnw verify` (JDK 21 or newer). Run: `./mvnw spring-boot:run`.

This project is under active development. Run and verification details will be added with each milestone.
