# 1. Start with a modular monolith

Status: accepted

## Context

The difficult boundaries are between an API contract, retrieved evidence, model output and network execution. Splitting these into distributed services would add deployment and failure-handling work without a current requirement for independent deployment or scaling.

## Decision

Use one Spring Boot application with separate parser, knowledge, generation, validation and execution components. Keep provider calls behind interfaces. Use Java records for immutable API data. Use a React client for the review/approval workflow.

## Consequences

The demo can run with no database or provider key. AI mode can replace retrieval and generation without replacing the application workflow. This is a single-user localhost development application; authentication, tenancy, durable workflow storage, quotas and production deployment are separate future work.
