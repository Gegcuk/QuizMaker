# Issue 748 Manual Testing: Typed Generation Coverage

## Purpose

Verify that the exact quantity decision from issue #747 is durably stored before terminal handling and is exposed as optional typed data through the existing quiz-generation status endpoints.

The existing frontend remains compatible without changes. It may ignore the new nullable field and must continue to use `status == COMPLETED`, not coverage outcome, to decide that a quiz is available.

Read the client and operator contract in [Quiz Generation Coverage](../quiz-generation-coverage.md) before testing.

## Deterministic Offline Verification

The focused tests use in-memory decisions, Mockito collaborators, Spring MVC, and local MySQL. They do not call OpenAI, Stripe, email, storage, or any other remote provider.

Run every command locally from the repository root.

1. Select JDK 17:

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ```

2. Run the policy handoff, immutable snapshot, persistence-service, status mapping, authorization, and OpenAPI tests:

   ```bash
   ./mvnw -Dtest=GenerationCoverageSnapshotTest,QuizGenerationCoverageServiceImplTest,QuizGenerationCoverageEventListenerTest,QuizGenerationStatusTest,QuizQueryServiceImplTest,AiQuizGenerationFailureScenariosTest,QuizGenerationStatusSecurityTest,QuizOpenApiContractTest test
   ```

3. With the repository's existing `quizmaker_test` and `quizmaker_test_mysql` databases available on `localhost:3306`, run the migration, transaction, concurrency, cancellation-race, cascade, and N+1 tests:

   ```bash
   ./mvnw -Dtest=QuizGenerationCoverageSchemaMigrationTest,QuizGenerationCoverageMySqlIntegrationTest test
   ```

4. Confirm both commands finish with `BUILD SUCCESS`. Do not add `-DskipTests` and do not enable a live-provider profile.

The focused suite covers complete, eligible-partial, and failed-threshold outcomes; deterministic type order; aggregate validation; immutable retry and conflict behavior; missing jobs; persistence failures; caller rollback after coverage commit; fresh persistence contexts; concurrent identical and conflicting writes; cancellation racing persistence; schema checks; physical cascade; legacy-null mapping; unauthenticated and wrong-owner access; OpenAPI examples; and one batch query for a status page.

## Swagger Contract Check

1. Start the backend locally with the normal development profile.
2. Open [local Swagger UI](http://localhost:8080/swagger-ui/index.html).
3. Select the `Quizzes` group.
4. Inspect `GET /api/v1/quizzes/generation-status/{jobId}` and `GET /api/v1/quizzes/generation-jobs`.
5. Confirm `QuizGenerationStatus.coverage` is optional and references the named `GenerationCoverage` schema.
6. Confirm `GenerationCoverage.types` references `GenerationTypeCoverage` and is required only when coverage itself is present.
7. Confirm examples cover `COMPLETE`, `PARTIAL`, `FAILED_THRESHOLD`, and legacy `coverage: null`.
8. Confirm the descriptions say job status remains authoritative for quiz availability.
9. After deployment, repeat the documentation inspection in [production Swagger UI](https://www.quizzence.com/swagger-ui/index.html). Do not submit a production generation merely to inspect documentation.

## Existing Frontend Flow

Use a non-production account and non-sensitive test text if an end-to-end local check is needed.

1. Start generation through the existing frontend without changing its request.
2. Read the existing `<JOB_ID>` from the accepted response.
3. Poll the existing endpoint:

   ```bash
   curl --silent --show-error \
     --header "Authorization: Bearer <ACCESS_TOKEN>" \
     "http://localhost:8080/api/v1/quizzes/generation-status/<JOB_ID>"
   ```

4. Before reconciliation, confirm `coverage` is null or absent from a client model that ignores unknown fields.
5. If coverage appears while `status` is `PROCESSING`, confirm the frontend continues polling and does not open a quiz.
6. On success, confirm `status` becomes `COMPLETED`, `generatedQuizId` is non-null, aggregate counts equal the sum of `types`, and each type has `missing == requested - accepted`.
7. On a quantity-threshold failure, confirm `status` is `FAILED`, `coverage.outcome` is `FAILED_THRESHOLD`, no generated quiz ID is exposed, and the existing UI still handles the failure without requiring the new field.
8. Poll a historical job created before this deployment and confirm it remains readable with `coverage: null`.

Never place access tokens, source content, prompts, generated questions, answers, filenames, or user identifiers in screenshots, logs, or issue comments.

## Persistence And Query Check

With a local job that has reached reconciliation, inspect only bounded facts:

```sql
SELECT BIN_TO_UUID(job_id), outcome, threshold_percent,
       requested_count, accepted_count, missing_count, discarded_count
FROM quiz_generation_coverage
WHERE job_id = UUID_TO_BIN('<JOB_ID>');

SELECT question_type, requested_count, accepted_count, missing_count
FROM quiz_generation_type_coverage
WHERE job_id = UUID_TO_BIN('<JOB_ID>')
ORDER BY question_type;
```

Confirm there is one aggregate row and one row for each requested positive-count type. No table or log entry should contain document text, a document hash, a prompt, a provider response, question content, an answer, or a filename.

The automated Hibernate statistics assertion is the authoritative N+1 check: loading coverage for a multi-job status page executes one aggregate-and-types query, not one query per job.

## Rollback Signal

Rollback the application commit if legacy jobs become unreadable; an existing client cannot ignore `coverage`; a failed-threshold job publishes a quiz or commits billing; conflicting coverage replaces the first fact; cancellation leaves partial child rows; another user can read coverage by guessing a job ID; or status-page query count grows with the number of jobs.

The migration is additive. An older application version ignores the new tables, so rollback must leave them in place and use a forward fix rather than dropping recorded coverage.
