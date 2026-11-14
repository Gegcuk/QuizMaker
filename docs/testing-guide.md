# QuizMaker Testing Guide

This guide explains which test style to use, what behavior to prove, and how to keep tests trustworthy. It applies to human contributors and AI agents.

## V2 READ_COMMITTED contract (#905)

V2 standalone start/answer/submit/draft/navigation and participant reads explicitly own RC.
Tests composing them must set outer RC explicitly; DEFAULT on a server-default RR database
is intentionally rejected. `hibernate.connection.isolation=2` was ineffective with the configured
pool and has been removed from both test profiles. Do not replace it with a global test RC default:
that would hide missing transaction ownership and weaken legacy RR coverage.

Run scoped boundaries first, using the existing isolated MySQL launcher and fake external ports:

```bash
./mvnw test -Dtest=AttemptTransactionsTest,CommandReceiptMySqlIntegrationTest,ResponseAcceptanceMySqlIntegrationTest
./mvnw test -Dtest=AttemptStartMySqlIntegrationTest,AttemptDeliveryMySqlIntegrationTest,ParticipantAttemptViewMySqlIntegrationTest,DraftCommandMySqlIntegrationTest,NavigationMySqlIntegrationTest,AttemptSubmissionMySqlIntegrationTest,AutomaticResultMySqlIntegrationTest,ImmediateResultReleaseMySqlIntegrationTest,ParticipantViewLegacyHttpIntegrationTest
```

The rejection matrix covers DEFAULT-on-RR, explicit RR, READ_UNCOMMITTED, SERIALIZABLE,
and read-only write callers before identity/domain SELECTs or reservations. Read-only RC reads
remain permitted. NOT_FOUND still makes joined work rollback-only; post-read review INACTIVE
still does not. Real JPA composition explicitly flushes inputs, refreshes JDBC outputs and
keeps unrelated pending changes, with commit and outer rollback controls. Existing real deadlock,
unknown-commit, authorization, sanitization and independent-participant tests remain.
Only obsolete successful target-RR cases are withdrawn; lower-level and legacy RR tests remain.

Workers retain independent RC read/write transactions: hold an outer RR snapshot, run expiry,
grading or release, roll back the caller, and verify the worker's committed evidence in a fresh
transaction. Validate actual connection isolation, not annotations. The receipt suite uses
Performance Schema's per-thread statement history and a real Hikari pool for driver overhead,
restoration/reuse, DEFAULT validation once across 500 calls, and suspension/resumption cleanup.
The measured overhead on MySQL 8.0.46 and 8.4.11 is +3 server commands for standalone RC over RR,
+1 over an RC connection, and one check for a DEFAULT participating transaction.
Use disposable MySQL 8.0 and 8.4 servers; the statement-history consumer must be enabled (the
images enable it by default). Keep this observation separate from bounded application SELECTs.
Suites change only their own pool default. Never change GLOBAL isolation inside a shared worker:
other cached pools could reconnect during the change. A server-default RC comparison uses a
separate disposable server and the receipt suite, followed by deletion of that server.

After focused checks, run the unchanged full `verify` and evidence gate with four workers.
Post-merge CI/CD checks remain enabled. Multi-parent fixtures with 1/20 items retain bounded
query counts: view 66/66, delivery 60/60, draft save 123/123, draft delete 126/126, and
navigation plus view JDBC 186/186 and JPA 15/15. Legacy view remains 6/6. These are measured
application query counts across three parents; they do not count driver isolation commands.
RC is not evidence of reduced whole-quiz rows/bytes,
removal of all contention, or activation readiness; #906 B, #629/#907 and #609 retain those gates.

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

The `test` and `test-mysql` profiles share schemas within one test JVM. A Spring Boot or JPA test that uses either schema must be marked `@Tag("db-serial")`. Without `context-forks`, Maven Surefire runs these tests in the dedicated `tests-db-serial` lane with JUnit parallel execution disabled. With `context-forks`, manifest-selected suites run sequentially within each isolated worker, each with its own MySQL server. The tag does not serialize different workers globally. Several legacy integration tests use Hibernate `create` or `create-drop`, which changes the complete shared schema within their worker.

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
- CI preloads Maven dependencies before removing network access. The JUnit Platform provider is declared as a Surefire plugin dependency using Surefire's managed version. Because `dependency:go-offline` does not discover compiler `annotationProcessorPaths` or fully initialize the provider's runtime closure, CI then runs only `MavenVerificationContractTest`. This plain JUnit file/POM/workflow contract uses no Spring context, database, or external provider client; executing it resolves the exact provider engine, launcher, and commons artifacts selected by Maven. CI then runs `clean verify` in Maven offline mode inside a Linux network namespace with no default route, rebuilding and executing the complete suite without provider access. Namespace loopback port `3306` reaches the baseline MySQL service; accelerated runs also bridge the isolated workers' loopback ports `33901`–`33904`. The Actions runner remains online so logs and artifacts can still be uploaded.
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
paid provider is called. Fixture/pool isolation defaults to `REPEATABLE READ`; v2 command/read
owners explicitly use RC. Release retains its independent RC transaction. The second command
also checks pool defaults at RC; a suspended RR caller remains RR.

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

### Flat Navigation

Run #525 checks with JDK 17 and the existing local test MySQL configuration:

```bash
./mvnw test -Dtest=FlatNavigationTest,NavigationPolicyTest,NavigationOpenApiTest,NavigationActivationTest,NavigationMySqlIntegrationTest
```

`NavigationMySqlIntegrationTest` creates and removes its own `flat_navigation_<id>` catalog using
`TEST_MYSQL_PORT` and the existing optional test-admin credentials. It uses the real publication,
authorized internal start, navigation, answer/draft commands, receipts, transactions and participant
readers. External image storage is a fail-on-use fake for these text-only fixtures; the legacy
delegate is outside this target-flow fixture. No paid/remote provider is needed.

The navigation counter is separate from lifecycle state. Tests must distinguish a successful
cursor move from accepting an answer, and exercise successful controls for answer-before-next,
skip/return, readback and read-only access. The complete skip A → answer B → return A → answer A →
continue C route is checked with accepted revisit both off and on. Hidden accepted neighbours do
not block progress, while available questions cannot be jumped over. Concurrent navigation uses
both MySQL isolation levels;
reached SQL failures and lost commit responses use fresh reads and recreated services. Query
measurements include multiple attempts, different form sizes and participant DTO serialization.
Policy golden fixtures retain the historical FREE bytes/hash. Run `./mvnw verify` after scoped
checks for existing API, training, migration and shared-reader compatibility.

## Unit Tests

### Immutable revision sections (#551)

With JDK 17 and the existing disposable test MySQL configuration, run:

```bash
./mvnw test -Dtest=QuizRevisionSectionsTest,QuizRevisionStructureServiceTest,ParticipantFormProjectorTest
./mvnw test -Dtest=QuizRevisionSectionsMySqlIntegrationTest,QuizRevisionMySqlIntegrationTest,QuizRevisionAuthoringMySqlIntegrationTest,FixedFormMaterializerMySqlIntegrationTest
```

The new MySQL suite creates/drops only its own generated `revision_sections_<id>` catalog. It
migrates through V109, inserts a real flat revision, then applies V110 and checks repeat migration
without backfill. Real repository/adapter calls cover exact structured round trips, a later
revision with edited section metadata, wrong-owner absence, legacy classification without SQL,
immutable database guards, foreign membership, and incomplete-evidence rejection. Faults occur
after actual section/member inserts; fresh transactions verify whole-aggregate rollback before
the same input succeeds. Two independent publication contenders synchronize before the root
insert and require one successful complete winner. RC/RR old-snapshot fixtures use committed
worker writes and real current reads. These are internal storage/adapter proofs, not author HTTP
or section-runtime conformance.

Real JDBC section-read faults also cross the actual service/repository transaction proxies with
transaction logging enabled at TRACE. Single/batch regressions require bounded outward failures
and no driver message, SQL, nested cause or suppressed diagnostic in transaction logs. They prove
rollback-only after a caught read failure, fresh-transaction absence of writes made before/after
that failure, and successful recovery through the same read path. Sanitization must occur inside
the repository, before its transaction interceptor can log the exception; a mock repository does
not establish this boundary.

The real flat materializer, baseline response/scoring contracts and persistence provide a
successful control; the same flat command shape cannot reinterpret a structured revision or
persist a partial form. Five revisions with multiple sections are read and serialized while
counting physical JDBC statements: five per structured batch, three for flat-only reads. MySQL
`EXPLAIN` verifies indexed parent/membership lookup. No JPA associations or external providers
are substituted in these new tests. Run the full `./mvnw verify` gate for existing API, training,
publication, authorization and OpenAPI compatibility before handoff.

### Immutable revision cases (#552)

With the existing offline fake environment, JDK 17 and disposable test MySQL:

```bash
./mvnw test -Dtest=QuizRevisionCasesTest,QuizRevisionCaseServiceTest,QuizRevisionSectionsTest,QuizRevisionStructureServiceTest
./mvnw test -Dtest=QuizRevisionCasesMySqlIntegrationTest,QuizRevisionSectionsMySqlIntegrationTest,QuizRevisionMySqlIntegrationTest,QuizRevisionAuthoringMySqlIntegrationTest,FixedFormMaterializerMySqlIntegrationTest
```

The case suite uses its own generated `revision_cases_<id>` catalog. It first persists real
format-1/2 revisions at V110, then upgrades to V111 without changing those rows or inventing
cases. It asserts exact case/stimulus-reference/member/atomicity round trips, read-only replay,
later revision B with a retained FK reference to A, fresh-transaction/restarted-component reads,
ownership denial, database immutability and invalid/missing/foreign/overlapping membership.
Administrator-created partial rows test corruption refusal separately from real publication.

Write faults occur after real case/member inserts and assert the checkpoint was reached.
Fresh transactions check absence of roots, sections, cases, members and newly inserted question
evidence before a successful retry. A consumer-reference write and subsequent outer failure
prove shared transaction rollback. Concurrent contenders synchronize before the real root
insert, require a complete successful winner and replay it after replacing the components.
RC/RR fixtures compare current shared reads with an older nonlocking snapshot. Actual nested
transaction proxies with TRACE enabled must not expose injected private JDBC diagnostics.

The measured read fixture serializes five parents with one and twelve cases per section:
seven statements per case-bearing batch, with indexed parent lookups; flat/section-only controls
remain three/five. The real existing flat materializer has a successful flat control and must
reject a case-bearing revision without writing a partial form. The case suite passes on both
the CI MySQL 8.4 line and the Compose MySQL 8.0 line. This is JDBC persistence evidence, not JPA association loading or proof of
participant delivery. Stimulus IDs are fixture references; no real content-source writer,
attempt start, section runtime or external provider is simulated as an integrated capability.
The separately owned temporary upgrade catalog exercises atomic refusal of invalid existing
version data and explicit test-fixture correction/Flyway repair before a successful retry;
it does not provide automatic production migration recovery.

Run the complete `verify` gate using the established launcher below before handoff. The new
MySQL suite is registered as a database companion in the existing context manifest.

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
v1 stays on its legacy reader with the participant-view flag absent, false or true, while v2
requires the flag. Run `./mvnw verify` after these checks for API/family-contract regressions;
real providers remain excluded.

Reserve integration tests for behavior that crosses real boundaries: service transactions, `AFTER_COMMIT` listeners, migration wiring, security configuration, serialization, or asynchronous job orchestration.

Immutable response acceptance (#543) has a focused suite:

```bash
./mvnw test -Dtest=StableResponseNormalizerTest,ResponseAcceptanceMySqlIntegrationTest,ResponseAcceptanceOpenApiTest,ResponseAcceptanceActivationTest
```

The MySQL class creates an exclusive `attempt_acceptance_<id>` catalog through `TEST_MYSQL_PORT`
and the existing optional administrative credentials, applies all migrations, and drops only
that catalog. Publication, contract binding, start, access, acceptance, lifecycle, trace and
receipt services are real; unexpected object-storage calls fail the fixture. Five families run
with authored and shuffled option order. Independent transactions at READ_COMMITTED require
an actual acceptance winner, one immutable row/event/receipt, and durable replay through recreated
services. RC callers observe the committed lifecycle version; RR callers reject before work. Faults after each real authoritative write and before/after
the actual commit are followed by fresh reads and successful same-command recovery.
Nested question-revision reads also preserve conflict classification: a real two-connection
InnoDB deadlock with an RC command and a READ_COMMITTED or REPEATABLE_READ blocker must return `RETRYABLE`, roll back the
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
Pool/fixture default is explicitly `REPEATABLE READ`; v2 owners use RC. The second command
covers a READ_COMMITTED pool default. Grading stays independent of suspended caller rollback.

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

## Separate attempt API versions (#904)

The legacy `AttemptController` owns `/api/v1/attempts` regardless of target flags.
`ParticipantAttemptViewController`, form, response, submission, navigation and draft
controllers own `/api/v2/attempts` and retain their existing feature gates. The public
v2 start belongs to #609 and is not introduced here. The #629 batch branch must use
`POST /api/v2/attempts/{attemptId}/responses/batch` when integrated.

Verify both full and grouped OpenAPI: legacy responses are `AttemptDetailsDto` or
`AttemptReviewDto`; target views are `ParticipantAttemptView`. `attempts` and `attempts-v2`
are disjoint groups, both listed by `/api/v1/api-summary`. HTTP version 2 is independent
of persisted schema version 1. Include flags never expand target disclosure.

```bash
./mvnw test -Dtest=ParticipantAttemptViewActivationTest,ParticipantAttemptViewOpenApiContractTest,AttemptFormOpenApiContractTest,ResponseAcceptanceOpenApiTest,AttemptSubmissionOpenApiTest,NavigationOpenApiTest,DraftCommandOpenApiTest,ApiDocumentationTest,ApiDocumentationServiceTest
./mvnw test -Dtest=ParticipantViewLegacyHttpIntegrationTest,ParticipantAttemptViewMySqlIntegrationTest,AttemptDeliveryMySqlIntegrationTest,ResponseAcceptanceMySqlIntegrationTest,AttemptSubmissionMySqlIntegrationTest,NavigationMySqlIntegrationTest,DraftCommandMySqlIntegrationTest,ImmediateResultReleaseMySqlIntegrationTest
```

Keep the real legacy all-family/mode/read/review fixtures and their SQL bounds. The target
fixtures retain old-RR reads, concurrent access, revocation, rollback, restart and released
content checks; #904 does not weaken those contracts. Reject legacy IDs at v2 without
fallback, and prove v1 still returns its own successful envelopes with target enabled.
Wrong-version detail/review/answer-key failures must mark a joined caller transaction
rollback-only even when caught; check the rollback of an unrelated caller write under RR.
Scoped tests precede full `verify`; the isolated context worker launcher and post-merge
verification remain unchanged.

The checked frontend attempt endpoints still use legacy start/answers/complete and
v1 reads. On 13 November 2026 the owner confirmed that no clients/environments consume
the target v1 routes, so #904 introduces no temporary aliases. A future v2 frontend must
retain each attempt's engine/version and must not retry a failed v2 command through v1.

## Question-contract lock intent (#906 phase A)

Use the default `QuestionRevisionContractService.declareScope(owner, revisions)` for READ.
Any composition that will bind must pass `ContractScopeIntent.WRITE` on its first declaration,
before resolving a subset. A read-write transaction is not evidence of binding intent.
Tests deliberately blocking participants behind a binder must declare WRITE explicitly.
Do not silently replace a binding race with concurrent shared reads.

`ResponseAcceptanceMySqlIntegrationTest` holds a real answer transaction open while a different
authorized user answers, reads a form, or starts on the **same** revision. These fixtures set and
assert actual RC on their own connections; no application default is changed. The start fixture
has no pre-existing active attempt for the second user. Require the second operation to commit
before releasing the first and inspect durable data from a fresh transaction. The held reader's
question-record locks are inspected through `ENGINE_TRANSACTION_ID` and `innodb_trx`, not the
lock structure creator's `THREAD_ID`. Current eligibility is owner/full editor, not general students.

`QuestionRevisionContractsMySqlIntegrationTest` keeps the JDBC/JPA and RC/RR matrices for
canonical order, exact bindings, owner scope, retries and rollback. It additionally rejects
READ-to-WRITE upgrades/direct bind, including empty revisions, and proves that savepoints cannot
erase the terminal violation and REQUIRES_NEW restores the outer READ intent. A binder still
waits for an active shared reader. Existing bounded metadata/query and multi-parent response
tests remain; identical query shapes select the same columns in the same batch sizes.

The view/start timing regression retains its RC/RR, read-only/read-write and timed/untimed
matrix. The view must project while the start is paused with shared question locks. RC starts
must then commit with the view transaction still open. RR timed starts can still wait on the
view's missing-timing gap lock; assert the actual waiter/holder, release the completed view,
and require both commits without a deadlock or retry. Phase A does not claim to remove that gap.
Form replay tests synchronize at the unique form-root insert, where contenders now serialize,
instead of expecting participant question scopes to take an exclusive lock.

```bash
./mvnw test -Dtest=QuestionRevisionContractServiceTest,QuestionRevisionContractsMySqlIntegrationTest,QuestionRevisionContractsMigrationMySqlIntegrationTest,PublishedFamilyContractsMySqlIntegrationTest,ResponseAcceptanceMySqlIntegrationTest,AttemptStartMySqlIntegrationTest,FixedFormMaterializerMySqlIntegrationTest,FormOptionOrderMySqlIntegrationTest,ParticipantAttemptViewMySqlIntegrationTest
```

Run full `./mvnw verify` after the scoped suites; keep post-merge verification. Phase A is not
an RR gap-lock fix. #905 narrows target composition to RC with explicit rejection tests;
phase B still owns current-read query simplification. Legacy RR remains unchanged.

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

### Atomic canonical-start allowance (#549)

With JDK 17 and the existing local test MySQL configuration, run:

```bash
./mvnw test -Dtest=AttemptLimitContractTest,AttemptStartMySqlIntegrationTest,AttemptSubmissionMySqlIntegrationTest
```

The start suite uses its exclusive generated catalog and actual canonical publication, receipt,
form, policy, subject, lifecycle, trace and allowance writers. It checks identical/different-key
races at RC, unsupported caller isolation rejection, terminal counting, immutable limits, exact cooldown,
real InnoDB lock timeout, classified storage faults, reached-write rollback, restart/commit
reconciliation and receipt-cleanup independence. The submission suite composes the real #529
submission command with the gate: terminal submission permits a subsequent unlimited/remaining
start, while an exhausted finite allowance remains exhausted. Start-only terminal fixtures use
the real lifecycle writer and do not claim full submission integration on their own.

The V103 SQL fixture recreates only the new tables in its exclusive test catalog after producing
real canonical roots, then proves exact pre-extension binding migration. An unclassified root is
separately denied without inventing publication-version history. Entry-reader doubles supply
versioned permitted/closed/unknown facts only, with a successful real-start control. They do not
prove #836 examination finality or #534 formal assignment behavior.

Existing multi-parent start fixtures measure SQL SELECT counts for 5/10/20-item banks and retained
receipt replay, including new bulk allowance reads; counts must remain constant. Separate races
require two distinct publication guards to be reached together and both starts to commit, with
and without prior history. Historical 1/3/5-start fixtures independently measure bounded bulk
reads. RC fixtures verify current terminal/count evidence after intervening commits.
`AttemptLimitContractTest` plus existing policy golden fixtures check the original unlimited
canonical bytes/hash, exact finite/duration round trips and forbidden participant overrides.
The allowance review regressions also cover empty and partially missing directories with real
retained bindings/lifecycle, finite and unlimited policies, and commits newer than an earlier
RC read. Old-RR callers fail before work. A cardinality mismatch must return ELIGIBILITY_UNAVAILABLE
without a second committed attempt or automatic repair. Restoring the fixture's directory must
restore ordinary active/limit denial or permit a genuinely eligible unlimited start.

TRACE capture targets the actual TransactionInterceptor logger. Faults at scope creation,
history reads, binding insert, directory update and the authoritative census include private
markers in the SQL exception/cause/suppressed/next-exception chain. Tests require a reached fault,
safe timeout/deadlock/storage classification, no marker in transaction messages/throwables,
fresh-transaction rollback assertions and successful retry. A plain unit test separately proves
that removing the repository's transactional proxy cannot permit autonomous JDBC access.

No public canonical-start endpoint is added; the full verification suite retains legacy HTTP,
participant-view, security and OpenAPI compatibility coverage. Run `./mvnw verify` before handoff.

## Isolated context workers (#897)

The optional `context-forks` profile distributes active Spring-context suites and
standalone database companions across reusable JVMs. Each pool JVM has its own MySQL server
on loopback port `33901`–`33904`. Classes remain sequential inside that JVM; threads and
transactions created by individual concurrency tests are unchanged. The default Maven
commands still use the existing fast and shared-database serial executions.

One resource-sensitive suite, `LegacyAnswerMembershipMySqlIntegrationTest`, runs in a
separate JVM after the pool finishes, reusing the first isolated MySQL server. The pool
contains the remaining manifest-selected suites; the exclusive execution retains all 38 scenarios, including concurrent
participants, the 2,048-answer fixture, rollback checks, and original timeouts. This avoids
other suites competing for CPU during the large write-cost scenario: on a four-worker CI
run the independent writer committed successfully in 33.6 seconds but exceeded the test's
20-second wait. This scheduling exception does not serialize the test's own participants.

Within the opt-in profile, `ContextDatabaseLifecycle` closes cached JPA contexts after
each top-level suite, after transaction cleanup. A cached context must not survive while
a different create-drop context deletes its schema. Nested tests finish before this cleanup;
MVC contexts without JPA and the ordinary Maven execution retain their existing lifecycle.

`tools/testing/context-test-manifest.json` records context suites, database companions,
existing exclusions, and classes outside Surefire's default naming patterns. Its active
selection is mirrored by `context-suites.txt` for Surefire. `context-excludes.txt` excludes
both enclosing and nested discovery roots from the fast execution. `ContextTestManifestTest`
reconciles all lists against compiled inherited/meta/nested annotations and programmatic
contexts. Update the lists when adding or renaming a context/database suite. Ordinary
unit tests continue to enter the default selection automatically. Excluded provider suites
and the two existing `*IT` discovery gaps are not enabled by this profile.

The manifest's `exclusive_contexts` subset is mirrored by `context-exclusive-suites.txt`
and `context-exclusive-excludes.txt`: the selected classes run only after the pool.
Separate reports, assignment records, and `jacoco-context/exclusive.exec` are mandatory;
the evidence gate rejects missing or overlapping assignments and missing coverage.

Read `BaseIntegrationTest`, `DatabaseTestExecutionTagTest`, and
`MavenVerificationContractTest` before changing test isolation. Do not remove transaction
annotations, cleanup, or `@DirtiesContext` to improve timings. For example, a change to
Anna's attempt completion must still prove that Boris can write independently and that caller
rollback undoes every participating write. Preserve legacy old-REPEATABLE_READ tests; v2 has
explicit RC participation and unsupported-isolation refusal tests.

### Writing suites that remain reliable with multiple workers

Surefire assigns whole classes dynamically to available JVMs; there is no fixed class-to-worker
mapping. Each JVM runs its classes sequentially, but the predecessor of a class can change.
More workers also change CPU scheduling inside a test's own executors. A locally green run
does not prove independence from either class order or thread timing.

- **Own schema preparation.** Every class must run on an empty disposable server and after
  another class has closed a `create-drop` context. Do not rely on a predecessor's Flyway
  tables or on Hibernate `update` preserving non-entity tables, triggers and constraints.
  Tests needing these objects should use a generated, class-owned catalog with Flyway and
  Hibernate `validate`. Read `NormalizedDocumentOwnershipMySqlIntegrationTest` for dynamic
  datasource wiring and pool-close-before-drop cleanup. Always honor `TEST_MYSQL_PORT`;
  hard-coded port `3306` bypasses worker isolation. Never drop a shared catalog.
- **Prepare every selected scenario.** Apply required migrations in class setup, not in the
  first ordered test. For upgrade tests, capture pre-upgrade rows before migrating and keep
  assertions about preserved rows and repeated migration. `AssignmentSchemaMySqlIntegrationTest`
  demonstrates this while allowing rollback and concurrency methods to run independently.
- **Synchronize the state the assertion requires.** A Mockito invocation count proves that
  a method was entered, not that it returned, read a row, acquired a lock or committed.
  Place bounded latches/hooks after the real relevant event; delegate to the real method
  rather than manufacturing its result. For database lock claims, observe the lock/wait
  or the actual SQL boundary. Sleeps and larger timeouts do not establish ordering.
- **Cover the other valid order too.** In `SubscriptionMutationConcurrencyMySqlIntegrationTest`,
  one scenario waits until the competing command actually observes `IN_PROGRESS`. Another
  pauses after it captured an active provider snapshot, lets cancellation commit, and then
  resumes the stale command. Both must preserve cancellation. Merely delaying cancellation
  until the competitor reaches `WAIT` would hide the stale-snapshot bug exposed by CI.
- **Finish workers before cleanup.** Release all scenario barriers in `finally`, stop the
  executor and assert bounded `awaitTermination` before deleting rows or resetting mocks.
  `shutdownNow()` requests interruption; it does not prove that a worker has stopped.
  Reset hooks/counters between scenarios. Preserve committed fixtures, independent connections,
  actual transaction boundaries and fresh reads proving rollback before cleanup.
- **Keep isolation and assertions.** Do not mask a failure with retries, disabled scenarios,
  weaker expected outcomes, a new global lock or a smaller worker count. First distinguish a
  resource timeout from a broken ordering assumption or a genuine application race. Keep
  authorization negatives, exact-repeat checks and reached-write rollback assertions intact.

For a changed concurrent suite, run the specific failing method on a fresh database, then
the entire class and related feature tests. For a schema-lifecycle change, also run after a
destructive schema class in the same JVM and run relevant methods independently. Repeat the
deterministic interleavings before the full four-worker gate. Do not run concurrent Maven
commands against the same checkout's `target` directory.

```bash
./mvnw test '-Dtest=SubscriptionMutationConcurrencyMySqlIntegrationTest#cancellationRacingUpdateRejectsUpdateAfterTerminalState+cancellationCommittedBeforeUpdateClaimRejectsStaleSnapshot'
./mvnw test -Dtest=SubscriptionMutationConcurrencyMySqlIntegrationTest,SubscriptionMutationServiceImplTest,BillingSubscriptionMutationControllerWebMvcTest
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /tmp/quizmaker-billing-full-verify
```

The scoped commands require the existing disposable test MySQL and fake test environment;
they do not enable `context-forks`. The launcher provisions isolated databases for the full
gate. Compare executed test identities, failures/skips and merged coverage evidence, not
just the green exit code. PR and post-merge `clean verify` remain required; scoped repetitions
are additional diagnostic evidence, not a replacement.

### Local commands

Prerequisites: JDK 17 in `JAVA_HOME`, Python 3, Docker, a locally available MySQL 8.4 image,
and cached Maven dependencies. Dependency/image preparation happens before offline testing.
The local execution launcher currently uses macOS `sandbox-exec` to deny external networking;
Linux CI uses the existing network namespace. The `prepare`, `check`, and `cleanup` commands
are platform-independent. The launcher uses checked-in fake environment values, never the
caller's provider credentials. Set no production connection overrides.

Run from the primary checkout root:

```bash
python3 -B -m unittest discover -s tools/testing -p 'test_*.py' -v
python3 tools/testing/context_benchmark.py inventory --check
python3 tools/testing/context_benchmark.py compare --scope contexts --workers 1,2,4 \
  --repeat-selected 1 --output /tmp/quizmaker-context-comparison
python3 tools/testing/context_benchmark.py verify --workers 1 --serial \
  --output /tmp/quizmaker-full-baseline
python3 tools/testing/context_benchmark.py compare --scope full --workers 1,2,4 \
  --repeat-selected 1 --output /tmp/quizmaker-full-comparison
```

Use a fresh output directory for every invocation. `compare --scope contexts` compiles once
and measures identical compiled tests; it is a scoped benchmark, not a release gate.
`verify` and `compare --scope full` execute complete `clean verify` runs. With the profile,
Maven runs the ordinary unit selection once, then the isolated context/database pool,
then the exclusive suite, merges coverage from every execution, and performs the existing
coverage checks and packaging.
The launcher additionally validates executed suites, unique server identities, and coverage
files before reporting success. Logs and resource ownership records survive `clean` outside
`target` and are copied to the selected output even when a run fails.

Use ordinary `./mvnw test -Dtest=...` for focused feature feedback. Do not combine `-Dtest`
with `context-forks`: Surefire selectors override include/exclude files and could execute
classes twice. The profile rejects this combination and the manual live-provider profile.
Worker counts are restricted to 1, 2, or 4. Provision independent servers before invoking the
profile directly; `-DforkCount=4` against a shared database is unsafe.

The local launcher records separate database setup, Maven execution, and total durations,
exact test identities, source/bytecode identity, and coverage differences. Class timings and
nested-container timings must not be summed as wall-clock time. Compare the same tree and
hardware with JaCoCo enabled. Missing covered lines or branch differences require investigation;
matching totals alone is insufficient. Choose two workers when four exhaust memory or run
slower. No automatic retries or relaxed checks compensate for resource shortages.

### Local measurements, 11 November 2026

Four full `clean verify` runs of this profile, based on `5450e297`, used identical source
and compiled application bytecode. Each run executed the same 9,575 test-case identifiers:
6,131 in the ordinary unit execution and 3,444 in the 243-suite context/database pool.
Every run passed with zero failures, errors, or skips; merged coverage checks and packaging
also passed. These measurements compare this profile at different worker counts, not the
ordinary no-profile execution or a GitHub runner.

| Workers | Complete Maven execution | Including database setup and evidence checks |
|---|---:|---:|
| 1 | 23:05.92 | 23:14.80 |
| 2 | 14:33.02 | 14:43.76 |
| 4 | 11:33.10 | 11:45.02 |
| 4, repeat with fresh databases | 11:26.84 | 11:38.34 |

Four workers reduced the full Maven duration by approximately 50%; two reduced it by 37%.
The total column excludes artifact copying and final resource cleanup. All 11 MySQL
instances had distinct server identities and were removed after their runs. The existing
development MySQL remained running. The launcher/CI helpers also passed 37 Python tests.

Environment: macOS, 10 host CPUs / 32 GiB RAM, JDK 17, JVM `-Xms256m -Xmx2g`,
Docker with 10 CPUs / 7.75 GiB, and MySQL 8.4.11 with 128 MiB buffer pools and server
REPEATABLE_READ. Every run resolved the same local MySQL image. A single observation during
the four-worker run showed approximately 3.4 GiB JVM RSS plus 2.6 GiB MySQL memory; this is
not a peak measurement and excludes Maven and other processes.

No source lines covered by the one-worker baseline were lost. Coverage was not bit-for-bit
identical: per-execution JaCoCo probes localized three scheduling-dependent differences to
the unchanged ordinary unit execution (`GenerationWorkDispatcherImpl:48`,
`ParentProcessMonitor:23,25,29`, and `AiQuizGenerationServiceImpl:223`). The repeated run
missed one baseline branch in the cancellation short-circuit, while all cancellation test
identifiers and assertions remained present. An additional admission-guard branch appeared
at `GenerationWorkServiceImpl:161` in the context execution. These variations are not newly
guaranteed coverage and must not be hidden by comparing rounded percentages alone.

On this machine, use the measured four-worker mode for a complete gate:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /tmp/quizmaker-full-verify
```

Choose a new output path for each run; use two workers on a more constrained machine until
measurements support four. Focused feature feedback still uses ordinary scoped tests.

### CI rollout and recovery

CI retains the serial baseline by default (`CONTEXT_TEST_WORKERS=0`). The workflow's manual
`context_workers` input supports 0/1/2/4 for measurements on the actual GitHub runner. A manual
run never publishes a deployment artifact. After repeated full-run evidence demonstrates an
improvement without lost tests/coverage, the repository variable `CONTEXT_TEST_WORKERS` may
select the measured 1/2/4 configuration for ordinary PR and post-merge runs. Do not infer that
four local workers are optimal on a smaller CI machine.

The accelerated CI path provisions independent MySQL instances, bridges only their loopback
ports into the offline namespace, runs one full Maven `clean verify`, and validates context
evidence before uploading the tested JAR. Required checks, migration smoke, post-merge
verification, and deployment's exact successful-run/SHA artifact binding remain unchanged.
`context-verification` artifacts include assignments, server identities, reports, coverage,
resource ownership/cleanup evidence, and the verification log.

To revert the optimization, set `CONTEXT_TEST_WORKERS` to `0` and use the ordinary full Maven
command locally. No application-data rollback is involved. If cleanup cannot reach Docker,
restore Docker and run `cleanup --output <run-resource-directory>` against the preserved
`resources.json`; it validates names and ownership labels before removing only that run's
containers and anonymous volumes. Never use a global prune for recovery.
