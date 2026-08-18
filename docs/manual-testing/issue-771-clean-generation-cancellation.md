# Issue #771 Manual Test Guide: Clean Generation Cancellation

## Purpose

Verify that a persisted `CANCELLED` status stops the complete quiz-generation workflow cleanly. The worker must not continue through question-type fallbacks, redistribution, coverage reconciliation, quiz creation, or stale progress writes after cancellation wins.

This change preserves the existing external contract:

- the current cancellation endpoint, authorization, polling response, and frontend behavior are unchanged;
- all requested questions of one type are still sent in one provider request per attempt;
- non-cancelled jobs keep the existing retry, reduced-count fallback, redistribution, coverage, and finalization behavior;
- the cancellation endpoint remains responsible for releasing the billing reservation; the generation worker does not commit or release it again;
- questions and provider-usage records produced before cancellation remain auditable, but they are not finalized into a quiz;
- direct or legacy generation without a job ID keeps its existing behavior;
- no API, OpenAPI, database, Flyway, question-format, or frontend change is required.

This issue does not interrupt an OpenAI request already in flight or remove queued executor tasks. Transport deadlines and the existing cancellation checks bound those cases.

## Automated Offline Verification

Run locally with Java 17:

```bash
JAVA_HOME=/path/to/java-17 ./mvnw \
  -Dtest=AiQuizGenerationCancellationOrchestrationTest \
  test
```

Expected result: six tests pass.

The focused tests prove:

- a job cancelled before worker startup is not returned to `PROCESSING`;
- cancellation observed at the provider boundary stops every remaining fallback and question type;
- a cancelled-first race during synchronous coverage persistence returns normally without completion or failure publication;
- a genuine coverage persistence error still follows the failed-job and reservation-release path;
- stale progress updates do not mutate a terminal job;
- direct generation without a job ID still succeeds through the legacy path.

The tests use mocks, deterministic in-process scheduling, and fake structured questions. They do not call OpenAI, Stripe, MySQL, email, storage, or another network service.

## Local Functional Verification

1. Start the backend and frontend with the normal local configuration.
2. Start a quiz with several question types so generation lasts long enough to cancel.
3. Wait until at least one provider batch has completed, then cancel through the existing frontend action.
4. Keep polling the existing job-status endpoint or view the current frontend status.
5. Confirm the job remains `CANCELLED`; it must never return to `PROCESSING`, `FAILED`, or `COMPLETED`.
6. Confirm no quiz is created for the cancelled job and no later question-type provider request is dispatched.
7. Confirm the billing reservation has the result produced by the existing cancellation endpoint, normally `RELEASED`; the worker must not create a second commit or release operation.
8. Inspect backend logs for the job ID. Expect at most one stable message: `Generation worker stopped because cancellation won for job ...`.
9. Confirm there are no later `all strategies failed`, redistribution failure, failed-coverage, or uncaught asynchronous stack traces for that cancellation.
10. Start one small quiz and do not cancel it. Confirm it completes with the existing fallback, coverage, billing, and finalization behavior.

Cancellation can race an already-running provider response. That completed request may still have an auditable usage record, but no additional provider request may start after persisted cancellation is observed.

## Data Inspection

Use the existing administration/query tooling and inspect only bounded job and billing metadata. Do not read or hash document content.

For the cancelled job, verify:

- `quiz_generation_jobs.status = CANCELLED`;
- finalization remains in its existing cancelled terminal state;
- progress counters and current-stage text stop changing after cancellation;
- no successful quiz finalization is linked to the job;
- billing is not committed by the worker after cancellation;
- no coverage record is inserted if cancellation committed before coverage persistence.

The exact billing state can remain reconciliation/error-oriented if the cancellation endpoint's release failed. That existing failure remains visible and must not be hidden or retried as a worker-side second release.

## Production Verification

After a human-reviewed branch is merged and deployed, verify liveness:

```bash
curl --fail --silent --show-error \
  https://www.quizzence.com/actuator/health/liveness
```

Expected result: `{"status":"UP"}`.

Create and complete one small normal quiz through the current frontend. Confirm generation, polling, billing, and quiz creation behave as before. A low-cost cancellation check may then be performed through the same frontend; do not deliberately trigger paid provider failures.

Logs must not contain prompts, document content, filenames, provider responses, API keys, usernames, or raw billing data.

## Performance And Query Check

Cancellation checks are bounded point lookups by job ID at orchestration boundaries: before provider work, between fallback attempts and question types, around redistribution, and before coverage/completion publication. They do not load relationship collections or scale with the number of rows behind a job.

N+1 is not applicable: this issue adds no JPA relationship traversal, list repository query, aggregate mapper, or serialization path. No document bytes or extracted text are copied or hashed.

## Rollback

Redeploy the previous healthy image. No database, stored-data, API, frontend, billing-tariff, or question-format rollback is required.

After rollback, terminal-state guards still protect the persisted cancellation, but the worker may again traverse fallback and coverage bookkeeping and emit misleading failure logs after cancellation.

## References

- [Issue #771](https://github.com/Gegcuk/QuizMaker/issues/771)
- [Provider-backoff cancellation issue #770](https://github.com/Gegcuk/QuizMaker/issues/770)
- [Parent provider-concurrency issue #442](https://github.com/Gegcuk/QuizMaker/issues/442)
- [Policy-Driven Quiz Execution Architecture](../architecture/policy-driven-quiz-execution.md)
