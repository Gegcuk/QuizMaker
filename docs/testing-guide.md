# QuizMaker Testing Guide

This guide explains which test style to use, what behavior to prove, and how to keep tests trustworthy. It applies to human contributors and AI agents.

## Choose The Smallest Correct Test

| Concern | Preferred test | What it proves |
| --- | --- | --- |
| Pure transformation, validator, parser, calculation, or service rule | JUnit 5 unit test | Business behavior without Spring or MySQL. |
| Controller mapping, validation, HTTP response, security, or `ProblemDetail` | `@WebMvcTest` with MockMvc | The HTTP boundary and serialization contract. |
| Custom JPA query, index-sensitive behavior, mapping, locking, or constraint | `@DataJpaTest` or focused database test | Actual persistence semantics. |
| Transaction, event listener, security chain, migration, async job, or several real layers | Focused integration test | Cross-layer behavior that a unit test cannot prove. |
| Provider protocol or API contract | Fake/stub plus contract/schema test | The boundary without calling a real provider. |

Do not start Spring or MySQL for JSON parsing, schema validation, deterministic scoring, or other pure logic. Conversely, do not mock the database when the behavior being claimed is a JPA query, lock, or database constraint.

## Project Test Environment

The full test suite uses the `test` profile and a MySQL test database. CI starts MySQL and runs Flyway migrations before the suite. Follow the existing profile and base-test conventions in the feature you are changing; do not add ad hoc database setup to individual tests.

Use `BaseIntegrationTest` only when the test genuinely needs the full Spring context. Keep unit and MVC tests independent of application startup.

### Shared MySQL Schema Isolation

The `test` and `test-mysql` profiles share CI MySQL schemas. A Spring Boot or JPA test that uses either schema must be marked `@Tag("db-serial")`. Maven Surefire runs these tests in the dedicated `tests-db-serial` lane with JUnit parallel execution disabled; all other tests stay in the parallel lane. This is required because several legacy integration tests use Hibernate `create` or `create-drop`, which changes the complete shared schema.

- Plain JUnit and Mockito tests remain eligible for parallel execution. Do not disable JUnit parallelism globally to address a database failure.
- Add the tag to tests using `@SpringBootTest` or `@DataJpaTest`; `@WebMvcTest` normally does not create a database context and should remain in the parallel lane unless it deliberately connects to MySQL.
- Do not use `@Execution(CONCURRENT)` on a `db-serial` test. Data cleanup, transactions, and `@DirtiesContext` keep one serial test independent from the next; the tag prevents destructive schema lifecycles from overlapping.
- `DatabaseTestExecutionTagTest` enforces this policy against compiled test classes, including nested Spring contexts.
- Preserve the existing `@DirtiesContext`, transaction, and cleanup conventions in the test being changed. The serial lane protects schema lifecycle, not test data that a scenario intentionally commits.

### Canonical Verification Gate

The release-quality command is `./mvnw verify`. It runs the bounded parallel lane, the serial MySQL lane, JaCoCo reporting and threshold checks, then packages the tested application. CI runs this same command; `test` alone is not a release gate.

- Builds require JDK 17. Maven fails during `validate` with a clear message on another JDK. Set `JAVA_HOME` to a JDK 17 installation before running a build.
- The non-database JUnit lane uses a fixed pool of four workers. Do not change it to unlimited workers or disable parallelism globally. The `db-serial` lane remains one-at-a-time because legacy tests change the shared MySQL schema.
- Default and CI verification are offline with respect to third-party providers. Tests must use fakes, stubs, WireMock, or local test infrastructure; a nonblank environment variable must never be enough to authorize a paid provider call.
- CI preloads Maven dependencies before removing network access. The JUnit Platform provider is declared as a Surefire plugin dependency using Surefire's managed version. Because `dependency:go-offline` does not discover compiler `annotationProcessorPaths` or fully initialize the provider's runtime closure, CI then runs only `MavenVerificationContractTest`. This plain JUnit file/POM/workflow contract uses no Spring context, database, or external provider client; executing it resolves the exact provider engine, launcher, and commons artifacts selected by Maven. CI then runs `clean verify` in Maven offline mode inside a Linux network namespace with no default route, rebuilding and executing the complete suite without provider access. Only namespace loopback port `3306` is bridged to the CI MySQL service. The Actions runner remains online so logs and artifacts can still be uploaded.
- Every test capable of contacting OpenAI, Stripe, Stripe CLI, or another live provider must use the test-only `@RealProviderTest` annotation. It assigns the `db-serial` and `real-provider` tags and disables direct IDE/class execution unless the manual Maven profile sets the explicit opt-in property.
- `live-provider-tests` is an explicit human-only Maven profile for tagged provider smoke tests. It is never enabled in CI and requires non-production credentials supplied securely in the local environment. It is not required for normal verification and must not use production user content.
- Test profiles disable automatic Stripe pack synchronization. A test that needs pack synchronization must provide a fake `StripePackSyncService` or explicitly opt into the live-provider lane; a real-looking key must never activate background network access.
- When CI fails, download the `surefire-reports` artifact. It includes Surefire/Failsafe reports, fork dumps, and JaCoCo output, subject to the workflow retention policy.

## Unit Tests

Use real input values and narrow collaborator doubles. A unit test should describe a rule in observable terms, for example:

```java
@Test
void calculateSchedule_whenGradeIsAgain_resetsRepetitionAndUsesMinimumEase() {
    // Given
    // When
    // Then
}
```

- Cover the happy path, validation boundary, and important failure semantics.
- Use parameterized tests for a compact matrix of equivalent rules or boundary values.
- Use builders or small factory methods for readable fixture creation; keep fixtures local unless they are genuinely shared.
- Mock only a real collaborator boundary. Do not invent impossible collaborator output merely to reach defensive code.
- Inject a fixed `Clock` rather than using sleeps or timing-sensitive assertions.

## MVC And API Contract Tests

Use `@WebMvcTest` or standalone MockMvc when testing controller behavior.

- Verify status, response DTO shape, validation errors, and relevant `ProblemDetail` fields.
- Include `.with(csrf())` for state-changing routes when CSRF is enabled for that test path.
- Use `@WithMockUser` or the project security test support for protected endpoints.
- Assert negative authorization paths where applicable: unauthenticated, missing permission, wrong owner, wrong organization, and private resource.
- Mock service interfaces, not their implementations. Controller tests are not service tests.

## Repository And Migration Tests

Use a real test database for custom queries and persistence invariants.

- Assert owner/tenant scoping, pagination ordering, soft-delete filtering, and null behavior.
- Test unique constraints, optimistic locking, and migration-backed invariants when they protect data integrity.
- Test fetch plans where an endpoint needs relationships. Use multiple parent rows and observe generated SQL or assert a bounded query count; the count must not increase linearly with parent count. Avoid claiming that N+1 is fixed from a functional assertion or annotation alone.
- Prefer deterministic fixtures and explicit ordering. Tests must not depend on execution order or left-over data.

Before task handoff, repeat this N+1 check for every touched JPA-backed list, aggregate, mapper, and serialization path. Record the command/test and result, or state that N+1 is not applicable because no relationship-loading read path changed.

## Integration Tests

Reserve integration tests for behavior that crosses real boundaries: service transactions, `AFTER_COMMIT` listeners, migration wiring, security configuration, serialization, or asynchronous job orchestration.

- Keep each scenario narrow and name the cross-layer contract it verifies.
- For asynchronous behavior, invoke a synchronous seam, use a controlled executor, or wait on a deterministic signal. Do not use arbitrary sleeps.
- Verify idempotency, retries, and rollback/partial-failure behavior where the feature uses them.

## External Systems And AI

Automated tests must never call real OpenAI, Stripe, email, storage, transcription, or other paid/remote services.

- Define project-owned ports/interfaces around providers.
- Use fakes or stubs that model valid provider responses and meaningful failures.
- Mark deliberate human-run provider smoke tests with `@RealProviderTest`; do not rely on filenames, environment-variable assumptions, or conditional skips to keep them out of default verification.
- Validate AI payloads at the structured-client or parser boundary.
- Test the application service only with collaborator output that the real collaborator can validly produce.
- Treat prompts, schemas, examples, and backward-compatible payload variants as contract assets; test them directly when they matter to generation.

## OpenAPI, Compatibility, And Security

For a changed public contract, add focused tests for:

- the endpoint's OpenAPI group and `/api/v1/api-summary` discovery;
- named request/response schemas, examples, enums, and expected RFC 7807 errors;
- request validation and authentication/permission behavior;
- old payloads or persisted data that must remain supported;
- the changed payload or response fields that the frontend relies on.

## Naming And Structure

Use names that identify the use case and observable outcome:

```text
methodOrRoute_whenCondition_thenExpectedOutcome
```

Examples:

```text
createQuiz_whenTitleIsBlank_thenReturnsBadRequest
parseFillGap_whenOptionsAreMissing_thenPreservesLegacyTypingMode
findDueEntries_whenEntryBelongsToAnotherUser_thenDoesNotReturnIt
```

Use Given/When/Then sections where they make a test easier to read. One test may have several assertions when they prove the same outcome.

## Commands

Run the narrowest relevant checks first:

```bash
./mvnw test -Dtest=QuestionServiceImplTest
./mvnw test -Dtest=QuestionControllerTest,QuestionSchemaServiceTest
./mvnw verify
git diff --check
```

Run the Maven command from the repository root. Use the live-provider profile only for a deliberate, owner-run smoke test after securely configuring test-mode provider credentials:

```bash
./mvnw -Plive-provider-tests test -Dtest=RealAiQuizGenerationIntegrationTest
./mvnw -Plive-provider-tests test -Dtest=RealStripeApiIntegrationTest
./mvnw -Plive-provider-tests test -Dtest=RealAwsSesE2ETest
```

Never use production Stripe credentials, production OpenAI data, or a production database for these smoke tests. Without `-Plive-provider-tests`, provider suites must be excluded rather than reported as skipped.

Report what ran, what did not run, and any residual risk. A passing test that asserts the wrong thing is not useful coverage.
