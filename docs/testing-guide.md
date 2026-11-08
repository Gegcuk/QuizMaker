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

`AttemptSubjectSchemaMySqlIntegrationTest` compares Flyway/`validate` and controlled `create-drop`
in separate, generated `quizmaker_535_schema_<id>` catalogs. It never drops shared test catalogs.
Its test-only administrative connection uses `TEST_MYSQL_PORT` and optional
`TEST_MYSQL_ADMIN_USERNAME`/`TEST_MYSQL_ADMIN_PASSWORD`; defaults match the disposable MySQL
service in CI. Those credentials need catalog create/drop privileges. This is independent of the
application datasource credentials. Each catalog is created exclusively and removed in `finally`,
including after a failing migration/validation. The fixture uses real `User`, `Role`, `Permission`
and `AttemptSubjectEntity` mappings without starting the application or provider clients.

### Canonical Verification Gate

The release-quality command is `./mvnw verify`. It runs the bounded parallel lane, the serial MySQL lane, JaCoCo reporting and threshold checks, then packages the tested application. CI runs this same command; `test` alone is not a release gate.

- Builds require JDK 17. Maven fails during `validate` with a clear message on another JDK. Set `JAVA_HOME` to a JDK 17 installation before running a build.
- The non-database JUnit lane uses a fixed pool of four workers. Do not change it to unlimited workers or disable parallelism globally. The `db-serial` lane remains one-at-a-time because legacy tests change the shared MySQL schema.
- Default and CI verification are offline with respect to third-party providers. Tests must use fakes, stubs, WireMock, or local test infrastructure; a nonblank environment variable must never be enough to authorize a paid provider call.
- CI preloads Maven dependencies before removing network access. The JUnit Platform provider is declared as a Surefire plugin dependency using Surefire's managed version. Because `dependency:go-offline` does not discover compiler `annotationProcessorPaths` or fully initialize the provider's runtime closure, CI then runs only `MavenVerificationContractTest`. This plain JUnit file/POM/workflow contract uses no Spring context, database, or external provider client; executing it resolves the exact provider engine, launcher, and commons artifacts selected by Maven. CI then runs `clean verify` in Maven offline mode inside a Linux network namespace with no default route, rebuilding and executing the complete suite without provider access. Only namespace loopback port `3306` is bridged to the CI MySQL service. The Actions runner remains online so logs and artifacts can still be uploaded.
- The isolated Maven process receives the job's captured environment through NUL-delimited stdin and `run_with_ci_environment.py` after returning to the runner user. This preserves the JDK 17 selected by `setup-java`, `HOME`, `PATH` and test settings even when `sudo` ignores `-E`. Values are neither shell-evaluated nor placed in command arguments or diagnostic output. An invalid capture or missing `JAVA_HOME` fails before Maven; Maven's exit status still propagates through the verification log pipeline. Python regression tests exercise the actual workflow command with simulated environment loss; Linux namespace execution remains covered by CI.
- Every test capable of contacting OpenAI, Stripe, Stripe CLI, or another live provider must use the test-only `@RealProviderTest` annotation. It assigns the `db-serial` and `real-provider` tags and disables direct IDE/class execution unless the manual Maven profile sets the explicit opt-in property.
- `live-provider-tests` is an explicit human-only Maven profile for tagged provider smoke tests. It is never enabled in CI and requires non-production credentials supplied securely in the local environment. It is not required for normal verification and must not use production user content.
- Test profiles disable automatic Stripe pack synchronization. A test that needs pack synchronization must provide a fake `StripePackSyncService` or explicitly opt into the live-provider lane; a real-looking key must never activate background network access.
- When CI fails, download the `surefire-reports` artifact. It includes Surefire/Failsafe reports, fork dumps, and JaCoCo output, subject to the workflow retention policy.

### Immediate Result Release

Run the focused #556 checks with Java 17 and the existing test MySQL configuration:

```bash
./mvnw test -Dtest=ImmediateResultReleaseMySqlIntegrationTest,PracticeReviewProjectorTest,ParticipantAttemptViewOpenApiContractTest,ParticipantAttemptViewActivationTest
./mvnw test -Dtest=ImmediateResultReleaseMySqlIntegrationTest -Dresult.test.isolation=TRANSACTION_READ_COMMITTED
```

The MySQL class creates its own `immediate_release_<id>` catalog through `TEST_MYSQL_PORT`
and optional `TEST_MYSQL_ADMIN_USERNAME`/`TEST_MYSQL_ADMIN_PASSWORD`. It uses the real internal
writers through publication, start, acceptance, submission, grading and release. No remote or
paid provider is called. Fixture and participant-read isolation defaults to `REPEATABLE READ`;
the publication command alone explicitly uses `READ_COMMITTED` in its `REQUIRES_NEW` transaction.
The second command also checks the surrounding fixture/read paths at `READ COMMITTED`.

Tests distinguish a missing result from a committed release, verify structural absence of
protected fields, current RESULT authority, deterministic frozen answer identifiers, concurrent
successful releases, failures after actual writes and commit-response loss. Fresh transactions
inspect rollback, and recreated services reconcile the same durable release identity. Real
HTTP reads cover include flags, wrong participants and lost permission. Multiple parents with
1 versus 20 items are serialized while measuring both JDBC and JPA queries. OpenAPI checks
cover full/grouped schemas, examples, expected errors and exclusive legacy/target envelopes.
The independent-submission race waits after both real missing-publication reads and requires
two successful publications, stable replays and one release event per result. A separate test
observes the publisher's RC transaction, the unchanged server default, and the resumed RR caller
and participant reader, including a genuinely old nonlocking snapshot.

### Documentation-Only Pull Requests

CI inspects the complete PR diff before starting `build-and-test`. It skips that job, including MySQL and Maven, only when every changed path is documentation: root `.md`/`.rst`/`.adoc` files; those formats plus `.txt`, `.png`, `.jpg`, `.jpeg`, `.gif`, `.svg`, `.webp`, and `.pdf` under `docs/`; or Markdown issue/PR templates under `.github/`. Other paths, including application prompts/resources, build files, workflows, and executable files under `docs/`, require the usual tests. Moving code into documentation still requires tests because the old code path is included in the diff.

The workflow still starts and the existing `build-and-test` check is marked skipped, so required checks do not remain pending. An empty diff or a failed change detector cannot disable tests. Pushes to `master`/`main` always run full verification; production deployment still consumes the tested JAR from the successful `master` push. For example, a PR editing only `docs/testing-guide.md` skips the expensive job, while one also editing `pom.xml` runs it.

Run the detector's Git-fixture regression tests without Java or MySQL:

```bash
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
```

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

The focused contract, fixed-form, and policy-snapshot MySQL suites migrate their owned tables once in `@BeforeAll` and drop them in `@AfterAll`. Before and after each scenario they clear committed rows in foreign-key order, restore legacy fixture markers, and reset fault hooks and SQL counters. Keep foreign keys enabled and preserve the scenarios' real JDBC/JPA transactions; an outer test rollback is not a substitute for this cleanup. Rollback assertions must inspect durable state before cleanup runs.

`QuestionRevisionContractsMigrationMySqlIntegrationTest` owns the scenario that deliberately reapplies a migration. Include it when running the contract suite. Snapshot corruption scenarios restore both the original value and CHECK enforcement in `finally`; cleanup verifies that every migrated CHECK is present with its original enforcement state.

```bash
./mvnw test '-Dtest=QuestionRevisionContracts*MySqlIntegrationTest,FixedFormMySqlIntegrationTest,PolicySnapshotMySqlIntegrationTest'
```

Before task handoff, repeat this N+1 check for every touched JPA-backed list, aggregate, mapper, and serialization path. Record the command/test and result, or state that N+1 is not applicable because no relationship-loading read path changed.

Immutable publication has a pure canonical-content suite and a real MySQL composition suite:

```bash
./mvnw test -Dtest=PublicationContentTest,QuizPublicationMySqlIntegrationTest
```

The MySQL suite uses `TEST_MYSQL_PORT` and optional `TEST_MYSQL_ADMIN_USERNAME` /
`TEST_MYSQL_ADMIN_PASSWORD` to create an exclusive `publication_<id>` catalog, applies the full
Flyway history and drops only that catalog after the class. It stays in the serial DB lane.
Internal authoring, retained-image, publication and fixed-form services are real; only object
storage is fake. Publication SQL faults occur after verified writes and assertions inspect
committed state before cleanup. Query counters include JDBC and JPA calls through full content
serialization and compare multiple questions/images, not just a single-parent fixture.
Its connection default is `REPEATABLE_READ`; a barrier after the receipt read lets the real
question writer commit a new image before publication locks the question. Assertions require
the new content and its exact retained binding to publish together. Source-authority regressions
exercise the real association/removal commands and pre-fix foreign memberships, before/after
library removal, alongside successful own-source and moderation controls. Detaching or
soft-deleting the original quiz must not grant the remaining foreign association publication.
A barrier after locking two different target quizzes reproduces the former shared-question
deadlock; both publications must commit and replay, with intact retained media.
The association regression also measures five versus ten actor roles and revokes source-question
administration while an OSIV role graph remains cached; the subsequent association must fail.
Legacy-confirmation tests exercise actual current permissions, atomic evidence writes, injected
post-write failure, idempotent confirmation, immutable ownership and reuse after library removal.
`QuestionSourceMigrationMySqlIntegrationTest` upgrades a pre-V94 row without inventing its owner
under a schema-scoped migration user without `SUPER`, and verifies the composite foreign-key
immutability constraint. A lone owner value without its authority row must not grant publication.
Import and generation tests cover trusted
new-source attribution and preservation of unknown legacy authority on import updates.

## Integration Tests

Flat attempt delivery has a focused real-MySQL composition suite and separate HTTP/activation checks:

```bash
./mvnw test -Dtest=AttemptDeliveryMySqlIntegrationTest,AttemptFormOpenApiContractTest,AttemptFormActivationTest,AttemptAccessServiceTest,AttemptAccessMySqlIntegrationTest
```

`AttemptDeliveryMySqlIntegrationTest` uses a generated `attempt_delivery_<id>` catalog via
`TEST_MYSQL_PORT` and the same optional administrative credentials as the publication suite.
It migrates the full schema, invokes real publication/binding/start/access/read services and
drops only its own catalog. Object storage is fake. Persisted-state comparisons surround reads
and injected SQL failures; a lock-wait assertion proves that permission revocation waits for
actual content projection. SQL counts cover multiple parents and complete form serialization.
The HTTP suite checks the real security chain; activation tests keep the new route absent by
default. These tests do not claim answer submission, navigation, completion-command or #609
activation conformance.

Canonical participant views (#526) add the following scoped verification:

```bash
./mvnw test -Dtest=ParticipantAttemptViewMySqlIntegrationTest,ParticipantViewLegacyHttpIntegrationTest,ParticipantAttemptViewOpenApiContractTest,ParticipantAttemptViewActivationTest,FamilyContractV1Test,ParticipantFormProjectorTest,AttemptDeliveryMySqlIntegrationTest,AttemptFormOpenApiContractTest,AttemptFormActivationTest
```

The new view suites use generated `attempt_view_<id>` and `legacy_view_<id>` MySQL catalogs
with Flyway and the same `TEST_MYSQL_PORT`/administrative credential convention. They exercise
actual target start/read and legacy writer/mapper/HTTP paths, access revocation, repeatable-read
snapshots older than publication/start, concurrent reads, rollback of a caller write, and
non-enumerating failures. Bounded-query assertions include serialization for three parents at
one and twenty children. `PARTICIPANT-VIEW-MEDIA` explicitly uses persisted canonical fixtures
and typed disclosure/media reader doubles: it proves projection of available/unavailable/restricted
images after service recreation, not completed-review release, a real storage backend, media
start or timing compensation. Full/grouped OpenAPI validates both legacy and target examples;
default/false activation leaves the existing controller on its legacy reader. Run `./mvnw verify`
after these checks for shared-controller/family-contract regressions; real providers remain excluded.

Reserve integration tests for behavior that crosses real boundaries: service transactions, `AFTER_COMMIT` listeners, migration wiring, security configuration, serialization, or asynchronous job orchestration.

Immutable response acceptance (#543) has a focused suite:

```bash
./mvnw test -Dtest=StableResponseNormalizerTest,ResponseAcceptanceMySqlIntegrationTest,ResponseAcceptanceOpenApiTest,ResponseAcceptanceActivationTest
```

The MySQL class creates an exclusive `attempt_acceptance_<id>` catalog through `TEST_MYSQL_PORT`
and the existing optional administrative credentials, applies all migrations, and drops only
that catalog. Publication, contract binding, start, access, acceptance, lifecycle, trace and
receipt services are real; unexpected object-storage calls fail the fixture. Five families run
with authored and shuffled option order. Independent transactions at READ_COMMITTED and
REPEATABLE_READ require an actual acceptance winner, one immutable row/event/receipt, and
durable replay through recreated command/receipt services. Older caller snapshots must observe
the committed lifecycle version. Faults after each real authoritative write and before/after
the actual commit are followed by fresh reads and successful same-command recovery.
Nested question-revision reads also preserve conflict classification: a real two-connection
InnoDB deadlock at READ_COMMITTED and REPEATABLE_READ must return `RETRYABLE`, roll back the
reservation and all target state, and allow the same command to accept once after the competing
transaction releases its locks. Injected 1205/connection-failure controls retain `IN_PROGRESS`/
`UNAVAILABLE`; ordinary delivery keeps its existing `BUSY`/`UNAVAILABLE` read responses.

Negative controls cover current subject/permission/editor/account checks, foreign real form
items, invalid option IDs, stale versions, terminal state and unknown saved contracts. Future
contract fixtures keep foreign keys enabled and consistent; terminal fixtures do not claim the
future #529 writer. Receipt expiry/cleanup must retain the accepted row. HTTP-to-MySQL tests
exercise real acceptance and replay; the separate MVC/OpenAPI suite verifies the security chain,
strict request parsing, error mapping, named five-family schemas, valid examples, one logical
group, API-summary discovery and default-off activation. Unicode tests distinguish character
and UTF-8 byte bounds. SQL counters compare three parents with one versus twenty items,
including real JDBC/JPA readers and complete participant projection.

Run `./mvnw verify` for compatibility after this suite. In particular, retained legacy answer and
practice-feedback behavior, participant view/delivery readers and frozen family scoring remain
covered by their existing suites. These checks do not prove grading/result, terminal submission,
editable finalization, batch acceptance or #609 activation, which retain their separate owners.

Reuse the Spring context within a class when scenario isolation does not require a restart. `QuestionSoftDeleteTest` and `BalanceEndpointIntegrationTest` retain their test-managed rollback and verify fixture removal in `@AfterTransaction`. `AttemptControllerCurrentQuestionIntegrationTest` and `QuizControllerCachingAndRateLimitTest` retain committed fixtures and real request transactions; they physically remove only their own rows after each scenario, with foreign keys enabled. Rate-limit scenarios use a distinct client IP per scenario and keep one IP for every request within the 120/121 boundary check. ETag scenarios retain direct requests without forwarded headers. These classes still close their contexts after the class and run in the serial database lane.

Avoid adding `@Nested` containers beneath a class-level `@DirtiesContext` solely for display grouping: inherited context invalidation can cause a restart after each nested container. Balance endpoint scenarios use grouped display names in one class instead. Do not globally remove `@DirtiesContext` from shared integration bases, since other suites change the shared schema or bean state.

- Keep each scenario narrow and name the cross-layer contract it verifies.
- For asynchronous behavior, invoke a synchronous seam, use a controlled executor, or wait on a deterministic signal. Do not use arbitrary sleeps.
- Verify idempotency, retries, and rollback/partial-failure behavior where the feature uses them.

### Automatic result facts (#603)

```bash
./mvnw test -Dtest=AutomaticResultTest,AutomaticResultMySqlIntegrationTest
./mvnw test -Dtest=AutomaticResultMySqlIntegrationTest -Dresult.test.isolation=TRANSACTION_READ_COMMITTED
```

Use the existing test MySQL configuration (`TEST_MYSQL_PORT` and optional administrative
credentials). The integration suite creates and drops its own `automatic_result_<id>` catalog,
keeps foreign keys enabled and runs actual publication, contract binding, start, response
acceptance and terminal submission before grading. Only external image storage is a fake.
Default isolation is explicitly `REPEATABLE READ`; the second command covers `READ COMMITTED`.

Coverage includes correct/incorrect golden inputs for all five families, MCQ_MULTI all-or-nothing
scoring, unanswered items, HALF_UP normalization and exact pass boundaries, immutable provenance,
author edits, receipt cleanup, missing deployed scorers through the real registry, corrupt scope/
counts/payloads, unsupported stored grading modes, real uniqueness/CHECK/FK constraints, concurrent
successful consumers and restart recovery. Reached-write faults after result, grades, trace and
outbox inserts prove rollback through fresh transactions. A 501-item fixture proves rollback after
the first real 500-grade batch. Lost commit acknowledgements are reconciled with the same retained
submission ID. Query counters compare three parents with five versus twenty-five items for both
creation and replay, including actual JDBC/JPA composition.
The four result/grade/trace/outbox write-fault scenarios explicitly capture `TransactionTemplate`
DEBUG logs, including throwable causes and suppressed exceptions. They require actual rollback
diagnostics and reject a synthetic private driver marker, while retaining reached-write, fresh-read
rollback and successful-retry assertions. Direct trace tests additionally exercise SQL, known-domain
and unexpected-runtime failures with sensitive nested diagnostics in a participating transaction.
Read-fault coverage exercises lifecycle `lock`, policy `readCurrent`, contract `declareScope` and
`resolve`, plus direct accepted-response reads as a protected control. Each point runs SQL codes
`0`, `1213` and `1205`: fifteen scenarios verify sanitized DEBUG diagnostics, preserved failure/
conflict classification, actual rolled-back completion and one successful same-submission retry.
Well-shaped impossible option/gap references fail closed; positive controls use real accepted
empty MCQ_MULTI/ORDERING/FILL_GAP arrays and preserve their zero-credit accepted identity.

No public endpoint, release, notification, scheduler or offline activation is claimed. Run the
full `./mvnw verify` gate for existing API/training/OpenAPI compatibility after focused checks.

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

Use JDK 17 and run Maven commands from the repository root.

### Local feedback loop

Run the narrowest relevant tests while implementing a change:

```bash
./mvnw test -Dtest=PolicySnapshotTest
./mvnw test -Dtest=PolicySnapshotTest,PolicySnapshotMySqlIntegrationTest
```

Choose tests by changed behavior and affected callers. Changes to shared
security, transactions, configuration or migrations require broader checks
than changes confined to one feature.

Do not run `clean` after every small edit. Use it for final clean verification
or when stale compiled/generated classes are suspected. A focused run is not
a full-suite verification.

### Full verification

Run full verification on the completed change:

```bash
./mvnw clean verify
git diff --check
```

CI continues to run the complete offline `clean verify` gate for code changes
in pull requests and for pushes to `master`/`main`. Existing documentation-only
PR classification remains unchanged.

### Timing diagnostics

To generate a local timing summary, preserve Maven's failure status through
the logging pipeline:

```bash
set -o pipefail
./mvnw -B -Dorg.slf4j.simpleLogger.showDateTime=true clean verify \
  2>&1 | tee /tmp/quizmaker-verify.log
```

Then render the summary:

```bash
python3 -B .github/scripts/summarize_tests.py /tmp/quizmaker-verify.log
```

The summary describes one Maven invocation and is diagnostic only. Missing
or partial timing data is not a passing test result.

SQL and bind-value logging are quiet by default. Enable SQL logging only for
a focused diagnostic run:

```bash
./mvnw test -Dtest=UserRepositoryTest \
  -Dlogging.level.org.hibernate.SQL=DEBUG
```

In CI, a new run for the same PR cancels its outdated run. Push-triggered
verification runs have independent concurrency keys and are not cancelled
by this PR optimization.

### Manual provider smoke tests

Run the Maven command from the repository root. Use the live-provider profile only for a deliberate, owner-run smoke test after securely configuring test-mode provider credentials:

```bash
./mvnw -Plive-provider-tests test -Dtest=RealAiQuizGenerationIntegrationTest
./mvnw -Plive-provider-tests test -Dtest=RealStripeApiIntegrationTest
./mvnw -Plive-provider-tests test -Dtest=RealAwsSesE2ETest
```

Never use production Stripe credentials, production OpenAI data, or a production database for these smoke tests. Without `-Plive-provider-tests`, provider suites must be excluded rather than reported as skipped.

Report what ran, what did not run, and any residual risk. A passing test that asserts the wrong thing is not useful coverage.
