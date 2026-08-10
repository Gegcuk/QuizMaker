# Manual Test Guide: Issue 464 - Deterministic Offline Verification

## Purpose

Verify that the backend release gate uses JDK 17, keeps database tests serial, bounds non-database test workers, does not permit provider calls by default, and produces useful failure artifacts in CI.

## Prerequisites

- Run all terminal commands locally from the repository root.
- Use a local MySQL test environment configured for the existing `test` and `test-mysql` profiles when running the full verification command.
- Do not configure a real OpenAI or Stripe key for the normal checks below.
- The Linux network-namespace check runs in GitHub Actions. Local macOS verification proves Maven selection and stable execution but does not reproduce Linux namespace plumbing.

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
   OPENAI_API_KEY=sk-local-offline-not-real \
   STRIPE_SECRET_KEY=sk_test_local_offline_not_real \
   STRIPE_WEBHOOK_SECRET=whsec_local_offline_not_real \
   AWS_ACCESS_KEY_ID=local-offline-not-real \
   AWS_SECRET_ACCESS_KEY=local-offline-not-real \
   RUN_REAL_SES_TESTS=true \
   TEST_SES_RECIPIENT_EMAIL=local-offline@example.invalid \
   ./mvnw verify
   ```

   Verification: both Surefire lanes complete, JaCoCo checks run, and `target/QuizMaker-*.jar` is produced. No real OpenAI, Stripe, Stripe CLI, or AWS SES report is present under `target/surefire-reports`; the run must not invoke a remote provider.

2. Remove `target`, repeat the same command, and compare the two Surefire summaries.

   Verification: both runs succeed with stable executed/skipped counts and no JVM fork abort. Record both summaries in the pull request or issue comment. Do not retry a failed run merely to obtain a green result.

3. In GitHub Actions, open the `CI` workflow for the pull request or `master` commit.

   Verification: dependencies are resolved before the gate, the JUnit Platform provider is declared under the Surefire plugin, and only the plain `MavenVerificationContractTest` runs while the Actions runner is online. That test uses no Spring context, database, or external provider client; it compiles test sources and initializes the provider's complete runtime closure. `Verify backend offline release gate` then creates `quizmaker-offline` without a default route, bridges only MySQL on namespace loopback, runs `./mvnw -o ... clean verify`, rebuilds and executes the complete suite, rejects any live-provider Surefire report, restores the namespace, and uploads the tested JAR only after success. A pre-test Maven failure is shown under `Maven/build failures before Surefire` rather than producing an empty test-error summary.

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
   ./mvnw test -Dtest=RealProviderTestExecutionContractTest,MavenVerificationContractTest
   ```

   Verification: all contract tests pass without a network call. They verify the complete known OpenAI, Stripe, and AWS SES provider-suite inventory, composed tags and system-property guard, default Maven exclusion, fake CI credentials, disabled background Stripe synchronization, and network-namespace gate.

2. Inspect the default Surefire reports after a normal verification run.

   Verification: live-provider classes are absent rather than conditionally skipped. A report for an outer or nested provider class is a gate failure even when every contained test says `skipped`.

3. Do not run `live-provider-tests` during normal development or CI. It is reserved for a deliberate owner-run smoke test with non-production content, a disposable local database, Stripe test mode, and securely supplied provider credentials.

4. For a deliberate OpenAI smoke test, run locally:

   ```bash
   OPENAI_API_KEY=<NON_PRODUCTION_OPENAI_KEY> \
   ./mvnw -Plive-provider-tests test -Dtest=RealAiQuizGenerationIntegrationTest
   ```

   Verification: the selected provider suite executes rather than being excluded. Stop if the database or content is not disposable.

5. For a deliberate Stripe smoke test, run locally:

   ```bash
   STRIPE_SECRET_KEY=<STRIPE_TEST_SECRET_KEY> \
   STRIPE_WEBHOOK_SECRET=<STRIPE_TEST_WEBHOOK_SECRET> \
   ./mvnw -Plive-provider-tests test -Dtest=RealStripeApiIntegrationTest
   ```

   Verification: only Stripe test-mode resources are created. Never use live-mode credentials.

6. For a deliberate AWS SES sandbox smoke test, run locally:

   ```bash
   RUN_REAL_SES_TESTS=true \
   AWS_ACCESS_KEY_ID=<NON_PRODUCTION_AWS_ACCESS_KEY> \
   AWS_SECRET_ACCESS_KEY=<NON_PRODUCTION_AWS_SECRET_KEY> \
   TEST_SES_RECIPIENT_EMAIL=<VERIFIED_SANDBOX_RECIPIENT> \
   ./mvnw -Plive-provider-tests test -Dtest=RealAwsSesE2ETest
   ```

   Verification: the selected suite runs only against an SES sandbox account and a verified test recipient. Never use production credentials or a real user's email address.

## Failure Diagnostics

1. In a failed GitHub Actions run, download the `surefire-reports` artifact.

   Verification: it contains Surefire/Failsafe reports and, where present, JVM fork dumps and JaCoCo output. Artifacts must not contain secrets or raw user content.

## Rollback

No production database, public API, or runtime configuration changes are made by this issue. Revert the application commit locally and rerun `./mvnw verify` on JDK 17 before proposing a replacement.
