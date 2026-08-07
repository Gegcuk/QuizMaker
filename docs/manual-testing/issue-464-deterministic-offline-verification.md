# Manual Test Guide: Issue 464 - Deterministic Offline Verification

## Purpose

Verify that the backend release gate uses JDK 17, keeps database tests serial, bounds non-database test workers, does not permit provider calls by default, and produces useful failure artifacts in CI.

## Prerequisites

- Run all terminal commands locally from the repository root.
- Use a local MySQL test environment configured for the existing `test` and `test-mysql` profiles when running the full verification command.
- Do not configure a real OpenAI key for the normal checks below.

## JDK Guard

1. Run locally:

   ```bash
   java -version
   ```

   Verification: the active runtime reports Java 17.

2. Run locally:

   ```bash
   ./mvnw validate
   ```

   Verification: Maven succeeds on Java 17. Running the same command under another JDK must fail before compilation with the JDK 17 guidance.

3. If another JDK is installed locally, run from the repository root:

   ```bash
   JAVA_HOME=<UNSUPPORTED_JDK_HOME> ./mvnw validate
   ```

   Verification: Maven fails during validation and reports that QuizMaker requires JDK 17. Do not run this command with a production environment file.

## Offline Release Gate

1. Run locally:

   ```bash
   ./mvnw verify
   ```

   Verification: both Surefire lanes complete, JaCoCo checks run, and `target/QuizMaker-*.jar` is produced. The run must not invoke OpenAI, Stripe, email, storage, or another remote provider.

2. In GitHub Actions, open the `CI - Build and Test` workflow for the pull request or `master` commit.

   Verification: the step is named `Verify backend offline release gate`, executes `verify`, and the tested backend JAR is uploaded only after that gate succeeds.

## Bounded Parallelism And Serial Database Tests

1. Inspect `pom.xml` locally.

   Verification: `tests-parallel` excludes `db-serial,real-provider` and uses a fixed JUnit parallelism and maximum pool size of `4`; `tests-db-serial` includes `db-serial`, excludes `real-provider`, and disables JUnit parallel execution.

2. Run locally, with the test MySQL schemas available:

   ```bash
   ./mvnw test -Dtest=QuizGenerationJobRepositoryTaskProgressTest
   ```

   Verification: `incrementCompletedTasks preserves every simultaneous transaction` passes. It starts five concurrent transactions at a barrier and verifies that all five atomic increments and version changes persist.

## Provider-Call Guard

1. Run locally without enabling any Maven profile:

   ```bash
   ./mvnw test -Dtest=RealAiQuizGenerationIntegrationTestExecutionContractTest,MavenVerificationContractTest
   ```

   Verification: both contract tests pass without a network call. They verify the live-AI tag and the default Maven exclusion.

2. Do not run `live-provider-tests` during normal development or CI. It is reserved for a deliberate owner-run smoke test with non-production content and a securely supplied real provider key.

## Failure Diagnostics

1. In a failed GitHub Actions run, download the `surefire-reports` artifact.

   Verification: it contains Surefire/Failsafe reports and, where present, JVM fork dumps and JaCoCo output. Artifacts must not contain secrets or raw user content.

## Rollback

No database, public API, or production configuration changes are made by this issue. Revert the application commit locally and rerun `./mvnw verify` on JDK 17 before proposing a replacement.
