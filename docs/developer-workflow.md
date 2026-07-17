# QuizMaker Developer Workflow

This is the practical issue-to-local-commit workflow for human contributors and AI agents.

## 1. Read And Orient

Read these documents in order:

1. [Contribution and local Git rules](../CONTRIBUTING.md)
2. [Issue-writing guide](github-issue-guide.md)
3. [Open-issue dependency roadmap](open-issue-roadmap.md)
4. [Detailed development rules](agents.md)
5. [Testing guide](testing-guide.md)
6. [Known risk register](known-risk-register.md), when touching authentication, deployment, CI, or secrets.

Then inspect the closest implementation and tests in the relevant feature package. Follow current feature conventions unless the issue explicitly includes an architectural migration.

## 2. Validate The Issue

Before coding:

- reproduce the bug or trace the current behavior;
- compare acceptance criteria with current code, migrations, tests, and OpenAPI;
- identify stale work, duplicates, dependencies, and frontend counterparts;
- define one observable outcome and explicit out-of-scope boundaries;
- identify API, data, compatibility, security, privacy, billing, and operability risks.

Do not implement a stale class-by-class checklist blindly. If the outcome already exists, report evidence and recommend closing or narrowing the issue.

## 3. Make A Proportional Plan

For nontrivial work, record:

- problem, users, success criteria, and unchanged behavior;
- likely touch points, happy path, and important edge/failure paths;
- API DTOs, status codes, RFC 7807 errors, pagination, and OpenAPI group;
- domain invariants, transactions, migrations, indexes, compatibility, and rollout;
- permissions, ownership, visibility, organization boundaries, privacy, abuse, and rate limits;
- unit, MVC/contract, repository, integration, and external-fake tests;
- observability and documentation changes.

A narrow bug fix needs less design than a new aggregate or public API.

## 4. Implement A Vertical Slice

Use the feature-first layout:

```text
features/<feature>/
  api/              controllers and API DTOs
  application/      service/use-case interfaces
  application/impl/ service implementations
  domain/           entities, value objects, rules, repository contracts
  infra/            persistence/provider adapters and mappings
```

Recommended order:

1. Additive migration and constraints, if needed.
2. Domain model and invariants.
3. Repository/provider interfaces and adapters.
4. Application-service interface.
5. Service implementation with transactions and authorization.
6. DTOs and mappings.
7. Thin controller/API boundary.
8. OpenAPI grouping, typed schemas, examples, and errors.
9. Tests at each affected layer.
10. User/developer documentation.

Controllers and other features depend on service interfaces, never `*ServiceImpl`. External systems stay behind project-owned ports so tests can use fakes or stubs.

## 5. Apply SOLID And KISS Together

- Keep responsibilities cohesive and dependency direction clear.
- Prefer the smallest explicit implementation that satisfies current requirements.
- Use Strategy for real interchangeable behavior, Factory for selection, Adapter for external providers, and events for decoupled reactions.
- Do not add patterns, generic base services, or layers for hypothetical future needs.
- Use constructor injection; keep business logic out of controllers and repositories.
- Return DTOs, never JPA entities, from public APIs.

## 6. Review Security Explicitly

For each use case, answer:

- Must the caller be authenticated?
- Which permission authorizes the action?
- Must the caller own the resource?
- Does visibility permit access?
- Is organization membership or a role required?
- Is rate limiting, quota, audit, PII masking, upload validation, SSRF protection, idempotency, or replay protection needed?

Roles group permissions; they do not replace permission checks. Permissions do not replace ownership, visibility, or tenant checks. Resolve identity and organization context server-side and default to deny when context is missing.

Test relevant negative cases: unauthenticated, insufficient permission, wrong owner, wrong organization, private resource, invalid input, and exceeded limit.

## 7. Test At The Correct Layer

- Pure rules/services: JUnit 5 with real values and narrow Mockito collaborators.
- Controllers: `@WebMvcTest` or standalone MockMvc for validation, HTTP, serialization, security, and ProblemDetail.
- Repositories: `@DataJpaTest`/integration tests for queries, constraints, locking, and fetch behavior.
- Cross-layer behavior: integration tests for security, transactions, migrations, serialization, and jobs.
- OpenAPI: contract tests for groups, schemas, examples, enums, and errors.

Tests must assert meaningful behavior. Do not mock impossible collaborator output, load Spring/MySQL for pure parsing, use sleeps for time, or call real external providers.

Run scoped checks first, then broader verification:

```bash
./mvnw test -Dtest=RelevantTest,RelevantContractTest
./mvnw verify
git diff --check
```

## 8. Treat OpenAPI As Product Code

- Discover existing contracts from `/api/v1/api-summary`, then inspect only the relevant `/v3/api-docs/<group>` document. For question creation or generation, inspect `/api/v1/questions/schemas` and the specific question-type schema before deciding a payload shape.
- Assign each endpoint to exactly one logical group in `OpenApiGroupConfig`.
- Keep the group discoverable through `/api/v1/api-summary`.
- Use named DTOs and typed page/list envelopes, not generic `object`, raw `Page`, or ambiguous collection shapes.
- Document authentication, permissions, ownership/visibility, filters, sorting, pagination, units, nullability, enums, idempotency, and partial failures.
- Document expected RFC 7807 errors and valid representative examples.
- Preserve existing clients unless a breaking change is explicitly approved.

## 9. Self-Review And Handoff

- Inspect the diff and remove unrelated churn.
- Verify acceptance criteria, negative paths, security, and compatibility.
- Confirm no secrets, tokens, PII, or production data were added.
- Report tests run, tests not run, remaining risks, and follow-ups.

## 10. Keep Git Local

- Never run `git push` or create/merge a PR, release, or deployment from an agent session.
- Create a local commit only when the repository owner explicitly requests it.
- Stage only issue-related files and preserve unrelated user changes.
- The human repository owner controls every remote action.
