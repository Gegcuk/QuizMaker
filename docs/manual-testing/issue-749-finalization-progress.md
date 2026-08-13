# Issue 749 Manual Testing: Durable Completion Progress

## Purpose

Verify that quiz generation reports at most 99% until quiz creation, billing settlement, and job finalization commit successfully. Exactly 100% now means `status == COMPLETED` and the generated quiz ID is available.

This is a semantic tightening of the existing numeric field. It does not add a request or response field, enum value, database column, migration, permission, or frontend requirement.

## Expected Behaviour

- `PENDING` and `PROCESSING` jobs report `0..99`, including when all task and chunk counters are complete.
- Redistribution, coverage reconciliation, checkpointing, assembly, and billing remain below 100.
- `FAILED` and `CANCELLED` jobs retain their last bounded progress and never report successful 100% completion.
- A successful finalization transaction publishes `COMPLETED`, `progressPercentage: 100.0`, and `generatedQuizId` together.
- A billing or finalization rollback leaves the job non-completed, at most 99%, with no visible generated quiz.
- Historical completed rows still read as 100. Historical non-completed rows stored at 100 read as 99 without a destructive backfill.
- Clients must continue to decide completion from `status == COMPLETED`, not from percentage alone.

## Deterministic Offline Verification

These tests use domain fixtures, Mockito stubs, and local MySQL. They cannot call OpenAI, Stripe, email, storage, or another remote provider.

Run every command locally from the repository root.

1. Select JDK 17:

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ```

2. Run domain, mapper, worker-progress, monitoring, query-count, cancellation, and OpenAPI tests:

   ```bash
   ./mvnw -Dtest=QuizGenerationJobTaskProgressTest,GenerationProgressTest,QuizGenerationStatusTest,QuizGenerationProgressInvariantMonitorTest,QuizQueryServiceImplTest,QuizGenerationFacadeImplComplexFlowsTest,QuizOpenApiContractTest test
   ```

3. With the repository's existing `test-mysql` database available on `localhost:3306`, run the atomic update and optimistic-lock tests:

   ```bash
   ./mvnw -Dtest=QuizGenerationJobRepositoryTaskProgressTest test
   ```

4. Run the real transaction-boundary tests with fake billing collaborators:

   ```bash
   ./mvnw -Dtest=QuizGenerationEntitlementMySqlIntegrationTest test
   ```

5. Verify each command ends with `BUILD SUCCESS`. Do not add `-DskipTests` or enable a live-provider profile.

The focused tests prove active and unsuccessful-terminal caps, completed legacy compatibility, invalid/non-finite normalization, monotonic counters, concurrent increments, stale-save rejection, late terminal-update rejection, finalization rollback, successful atomic completion, bounded monitoring labels, and OpenAPI examples.

## API And Swagger Check

1. Start the backend locally with the normal development profile.
2. Open [local Swagger UI](http://localhost:8080/swagger-ui/index.html).
3. Select the `Quizzes` group.
4. Inspect `GET /api/v1/quizzes/generation-status/{jobId}` and `QuizGenerationStatus.progressPercentage`.
5. Confirm the description says `0..99` is non-completed work and 100 is reserved for durable `COMPLETED` status.
6. Confirm examples exist for processing at 99, failed at 99, cancelled below 100, and completed at 100 with a quiz ID.
7. After deployment, repeat against [production Swagger UI](https://www.quizzence.com/swagger-ui/index.html) without submitting a production mutation just to inspect documentation.

## Existing Frontend Flow

No frontend change is required. To verify the existing polling flow:

1. Start a generation through the current frontend using a non-production account and test content.
2. Read the returned `<JOB_ID>` from the existing accepted response.
3. Poll locally with the same authenticated endpoint:

   ```bash
   curl --silent --show-error \
     --header "Authorization: Bearer <ACCESS_TOKEN>" \
     "http://localhost:8080/api/v1/quizzes/generation-status/<JOB_ID>"
   ```

4. While `status` is `PENDING` or `PROCESSING`, confirm `progressPercentage` never exceeds `99.0`. It is valid for `completedTasks` to equal `totalTasks` at 99 while finalization continues.
5. On success, confirm one response exposes `status: COMPLETED`, `progressPercentage: 100.0`, and a non-null `generatedQuizId` together.
6. On failure or cancellation, confirm the terminal status is accurate and progress remains below 100.

Do not record access tokens, prompts, document text, generated questions, answers, filenames, or user identifiers in screenshots or issue comments.

## Compatibility And Query Audit

- Existing generation endpoints, status paths, fields, enum values, authorization, ownership checks, and polling cadence are unchanged.
- No migration or backfill is introduced. Legacy completed and active rows are normalized on read and on their next update.
- Status-page mapping performs one existing page fetch. The invariant monitor is an in-memory check per returned entity and performs no repository call; `QuizQueryServiceImplTest` verifies one job-service page call regardless of item count.
- Atomic progress updates remain one SQL update each. They now advance the optimistic-lock version and ignore late writes after a terminal state.
- No document content is hashed or reread, and no relationship-fetching path was added.

## Observability

The bounded metric `quiz.generation.progress.invariant.violations` uses only the fixed `reason` values `invalid_percentage`, `completed_below_100`, and `non_completed_at_100`. It has no job, user, document, path, prompt, or content tag.

## Rollback Signal

Rollback the application commit if an active, failed, or cancelled job returns 100; a completed job returns less than 100; a billing rollback leaves a visible quiz; status polling performs an additional query per item; or the unchanged response shape cannot be parsed by the existing frontend.
