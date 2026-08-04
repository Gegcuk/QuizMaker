# Manual Test: Durable Quiz-Generation Entitlement (#440)

## Purpose

Verify that a generated quiz becomes available only after its billing
settlement is durable, while cancellation and failed finalization leave no
partially delivered content. This procedure preserves the current frontend
workflow: poll a generation job and fetch the quiz only after `COMPLETED`.

## Preconditions

- Use a non-production environment with a test account that has sufficient
  billing-token balance.
- Keep the normal reservation TTL and finalization recovery settings unless a
  controlled test environment is explicitly configured otherwise.
- Do not call a real AI provider solely to test failure handling. The automated
  MySQL test uses a deterministic fake billing port for that condition.

## 1. Normal generation

1. Start a document generation request with at least one question type.
2. Poll `GET /api/v1/quizzes/generation-jobs/{jobId}` until it returns
   `status: COMPLETED`.
3. Before `COMPLETED`, confirm that the frontend does not offer generated quiz
   content and that `GET /api/v1/quizzes/generated-quiz/{jobId}` is not treated
   as available content.
4. After `COMPLETED`, open the generated quiz and confirm it belongs to the
   account that started the job.
5. Confirm the generation reservation was committed or released exactly once
   according to the existing tariff policy. The job's billing state must no
   longer be `RESERVED`.

Expected result: the quiz is available only after the job is `COMPLETED`, and
the completed job has a settled billing state.

## 2. Cancellation race

1. Start another generation and cancel it while its status is `PENDING` or
   `PROCESSING`.
2. Confirm the job becomes `CANCELLED` and its reservation is released.
3. Refresh the generation-job list and attempt to fetch the generated quiz.

Expected result: no generated quiz is available for the cancelled job. If
cancellation reaches the locked job first, it wins; if completion already
committed first, cancellation is rejected as a terminal job. Neither outcome
creates a duplicate debit or duplicate quiz.

## 3. Controlled settlement failure

1. In a local or dedicated test environment, run the focused automated test:

   ```text
   ./mvnw test -Dtest=QuizGenerationEntitlementMySqlIntegrationTest
   ```

2. Confirm the `Settlement failure rolls back the quiz and leaves the job
   non-completed` case passes.

Expected result: when the fake billing port throws, no quiz row persists, the
job remains non-completed until recovery marks it failed, and no real provider
or payment API is called.

## 4. Recovery visibility

1. In a non-production environment, inspect application logs after a controlled
   finalization failure or use the focused facade test:

   ```text
   ./mvnw test -Dtest=QuizGenerationFacadeImplBillingTest
   ```

2. Confirm recovery logs a stalled finalization only when it actually finds one.
3. Confirm a recovered job is `FAILED`, has no generated quiz, and its
   reservation is no longer `RESERVED` once release succeeds.

Expected result: recovery never publishes content, never creates another quiz,
and uses the same idempotent reservation-release key on retries.

## 5. API compatibility and Swagger

1. Open [Swagger UI](https://www.quizzence.com/swagger-ui/index.html).
2. Check the quiz generation job status endpoint and generated quiz endpoint.
3. Confirm existing status enum values remain `PENDING`, `PROCESSING`,
   `COMPLETED`, `FAILED`, and `CANCELLED`.
4. Confirm the descriptions say that `COMPLETED` means the quiz and billing
   settlement are durably finalized.

Expected result: no frontend migration is required. Existing clients only need
to keep their current rule of rendering generated content after `COMPLETED`.
