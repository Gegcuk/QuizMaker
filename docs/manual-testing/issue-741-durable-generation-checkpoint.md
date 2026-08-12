# Manual Test: Durable Generation Output Checkpoint (#741)

## Purpose

Verify that validated AI-generated questions survive the handoff to quiz
finalization, while duplicate delivery, cancellation, corrupt data, and restart
recovery cannot create a duplicate quiz or debit. This feature is internal: the
existing frontend and iOS request, polling, status, cancellation, and generated
quiz flows remain unchanged.

## Safety rules

- Run failure and restart simulations locally or in a dedicated test
  environment. Do not interrupt production workers to exercise this feature.
- Automated tests use fake AI and billing collaborators. They do not call
  OpenAI, Stripe, email, storage, or another remote provider.
- Never select, print, copy, or log the checkpoint `payload`; it contains
  private generated questions and correct answers.
- Do not update generation, checkpoint, quiz, or billing rows manually.
- No document content is copied into or hashed for the checkpoint.

## 1. Focused local verification

Run these commands from the repository root on the local machine. A local MySQL
test database is required only for commands 2 and 3.

1. Run the plain unit and service-boundary tests:

   ```bash
   ./mvnw clean test -Dtest=QuizGenerationCheckpointCodecTest,QuizGenerationCheckpointEventListenerTest,QuizGenerationCheckpointServiceImplTest,QuizGenerationFinalizationRecoverySchedulerTest,QuizGenerationFacadeImplBillingTest,QuizGenerationFacadeImplComplexFlowsTest,QuizGenerationFacadeImplTest,QuizServiceImplBillingDelegationTest,QuizServiceDatabaseFailureScenariosTest,AiQuizGenerationFailureScenariosTest
   ```

2. Run restart, concurrency, cancellation, rollback, cleanup, and N+1 coverage
   against MySQL:

   ```bash
   ./mvnw test -Dtest=QuizGenerationEntitlementMySqlIntegrationTest
   ```

3. Validate the additive V72 schema and cascade behavior against MySQL:

   ```bash
   ./mvnw test -Dtest=QuizGenerationOutputCheckpointSchemaMigrationTest
   ```

Expected result: every command reports `BUILD SUCCESS`. The tests prove that
recovery reuses the checkpoint without invoking AI, concurrent scanners create
one quiz and one settlement, rollback retains the checkpoint until failure
compensation, cancellation wins under the job lock, and malformed output never
becomes visible.

## 2. Normal client compatibility

Use an existing development generation workflow; do not make a paid provider
call solely for this check.

1. Start generation using the current frontend or existing API request.
2. Poll the existing generation-job endpoint until it reaches a terminal state.
3. Confirm `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, and `CANCELLED` retain
   their current meanings and JSON shapes.
4. Confirm a generated quiz is available only after `COMPLETED`.
5. Confirm there is no checkpoint field, recovery control, or new status exposed
   to the client.

Expected result: no frontend or iOS change is required. Successful users see the
same quiz; failed or cancelled users see no partial generated content.

## 3. Swagger compatibility

1. Open [production Swagger UI](https://www.quizzence.com/swagger-ui/index.html).
2. Inspect the quiz generation start, status, cancellation, and generated-quiz
   operations.
3. Confirm their request/response schemas and status enums are unchanged.

Expected result: V72 and checkpoint events remain private implementation
details and do not alter OpenAPI.

## 4. Read-only production verification after deployment

Run commands 1 and 2 over SSH on the Droplet from
`/var/www/quizmaker-backend`. They do not mutate application data.

1. Confirm MySQL and the backend are healthy:

   ```bash
   docker compose --env-file .env ps
   ```

2. Review only checkpoint/recovery outcome logs from the current deployment:

   ```bash
   docker compose --env-file .env logs --since 30m quizmaker-backend | grep -E 'Checkpointed [0-9]+ generated questions|Scanning bounded quiz-generation recovery batch|Reconciled [0-9]+ stalled quiz-generation|checkpointed quiz-generation recovery failed|Unable to recover stalled quiz-generation' || true
   ```

3. In the existing read-only MySQL console, verify metadata without selecting
   payloads:

   ```sql
   SELECT column_name, column_type, is_nullable
   FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'quiz_generation_output_checkpoints'
   ORDER BY ordinal_position;

   SELECT COUNT(*) AS checkpoint_count
   FROM quiz_generation_output_checkpoints;

   SELECT j.status, j.finalization_state, j.billing_state, COUNT(*) AS jobs
   FROM quiz_generation_output_checkpoints c
   JOIN quiz_generation_jobs j ON j.id = c.job_id
   GROUP BY j.status, j.finalization_state, j.billing_state;
   ```

Expected result: both containers are healthy, V72 columns exist, checkpoint
rows are transient, and no terminally completed, failed, or cancelled job keeps
a checkpoint. An empty log result is normal when no generation or recovery ran
in the selected interval.

## 5. Operational and performance checks

1. Confirm Micrometer contains
   `quiz.generation.checkpoint.operations` and
   `quiz.generation.finalization.recovery.runs` in the environment's existing
   metrics backend.
2. Confirm tags are bounded outcomes only; no job ID, username, document value,
   reservation ID, generated content, or payload size is a tag.
3. Review the focused MySQL test named `Recovery candidate scan executes four
   bounded queries without per-job relationship loading`.

Expected result: the candidate scan uses four bounded ID queries independent of
candidate count. Per-job locked operations occur only after IDs are selected,
so there is no relationship-loading N+1 in the scan.

## Rollback and forward fix

V72 is additive. If the new application version must be rolled back, deploy the
previous application image and leave the Flyway history and checkpoint table in
place; the old application does not read that table. Do not delete the table,
edit Flyway history, or roll back rows manually. A subsequent fixed application
can reuse the same schema. Broader durable provider dispatch and worker leases
remain tracked by #443.
