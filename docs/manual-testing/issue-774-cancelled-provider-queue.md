# Issue #774 Manual Test Guide: Cancelled Provider Queue Work

## Purpose

Verify that provider tasks belonging to a cancelled quiz-generation job stop occupying the bounded executor queue when they have not started.

This change preserves the existing generation contract:

- all requested questions of one type remain batched in one provider request per attempt;
- the deployed provider limits remain 8 core workers, 16 maximum workers, and 50 queued tasks;
- capacity rejection, retry classification, provider-attempt accounting, usage persistence, coverage, finalization, and billing behavior remain unchanged;
- persisted `QuizGenerationJob.status = CANCELLED` remains the cancellation authority;
- the cancellation endpoint remains responsible for reservation release;
- current frontend, API, OpenAPI, polling, and direct/jobless generation behavior remain unchanged.

This change does not interrupt an OpenAI request that has already started. Such a request remains bounded by the configured provider transport timeout and existing cooperative cancellation boundaries.

## Automated Offline Verification

Run locally with Java 17:

```bash
JAVA_HOME=/path/to/java-17 ./mvnw \
  -Dtest=ExecutorAiProviderTaskSchedulerCancellationTest,AiQuizGenerationCancellationOrchestrationTest \
  test
```

Expected result: all focused tests pass.

The focused tests prove:

- cancelling queued work removes its wrapper immediately and makes the queue slot reusable;
- a cancelled supplier never runs, including with a generic executor that cannot physically remove its wrapper;
- cancellation after executor dequeue but before the atomic queued-to-running claim prevents provider invocation;
- cancelling a future whose supplier already started does not interrupt the provider thread;
- executor rejection remains the existing typed capacity failure;
- when one chunk propagates cancellation, all incomplete sibling chunk futures are cancelled and no coverage, completion, or worker-side billing-release action occurs.
- cancellation reported by a later chunk wakes and cancels an earlier blocked future without waiting in submission order, while an already-completed sibling keeps its result.
- a generic cancelled future cannot fan cancellation out unless the persisted job is already `CANCELLED`; the database remains authoritative.

The tests use local executors, latches, mocks, and controlled futures. They do not call OpenAI, Stripe, MySQL, email, storage, Docker, or another network service.

## Local Functional Verification

1. Start the backend and frontend with the normal local configuration.
2. Start a quiz from a document that produces several chunks and requests several question types.
3. Cancel the generation while provider work is active or queued.
4. Confirm the existing job-status response remains `CANCELLED` and never returns to `PROCESSING`, `FAILED`, or `COMPLETED`.
5. Confirm no quiz is finalized and no queued provider request starts after the worker observes persisted cancellation.
6. Start a small unrelated quiz immediately after cancellation.
7. Confirm the unrelated quiz can use released executor capacity and follows the normal generation flow.
8. Inspect logs for one clean worker-cancellation message and no later fallback, coverage, completion, or uncaught asynchronous error for the cancelled job.
9. Confirm the cancellation endpoint owns the existing billing release and the generation worker creates no second release or commit.

An OpenAI request that was already in flight may finish after the cancellation click and may retain its provider-usage audit record. That is expected. The result must not be finalized, and this issue does not claim immediate network-request abortion.

## Production Verification

After a human-reviewed branch is merged and deployed, verify liveness:

```bash
curl --fail --silent --show-error \
  https://www.quizzence.com/actuator/health/liveness
```

Expected result: `{"status":"UP"}`.

Create one small normal quiz through the existing frontend and confirm it completes. A low-cost multi-chunk quiz may then be cancelled through the same frontend; confirm it remains `CANCELLED` and that another small generation can start normally. Do not deliberately exhaust production queue capacity or force paid provider failures.

Logs must not contain prompts, document content, filenames, provider responses, credentials, usernames, or raw billing data.

## Performance And Query Check

`ThreadPoolExecutor.remove` performs a bounded linear `O(n)` scan of the in-memory provider queue. The queue is already bounded at 50 entries, so cancellation work is bounded. Orchestration inspects only the futures already owned by that generation and adds no polling or normal-path repository query; bounded cancellation-status lookups may confirm an exceptional generic cancelled-future path.

The scheduler uses one atomic queued-to-running claim. Cancellation that claims the queued state first prevents supplier invocation even if an executor already dequeued the wrapper. A task that claims the running state first keeps the existing non-interrupting behavior and remains bounded by provider transport and cooperative cancellation checks.

Chunk futures are linked by an in-memory cancellation group. A cancellation observed from any chunk cancels every incomplete sibling immediately, independent of the order in which orchestration is waiting for results. Futures that already completed are not changed.

N+1 is not applicable: this issue adds no JPA relationship traversal, collection repository query, entity mapper, or serialization path. No document bytes or extracted text are copied or hashed.

## Rollback

Redeploy the previous healthy image. No database, stored-data, API, frontend, billing, executor-limit, or question-format rollback is required.

After rollback, cancelled queued wrappers still skip their suppliers when they eventually reach a worker, but they may occupy bounded queue slots until earlier tasks drain.

## References

- [Issue #774](https://github.com/Gegcuk/QuizMaker/issues/774)
- [Parent issue #442](https://github.com/Gegcuk/QuizMaker/issues/442)
- [Clean cancellation issue #771](https://github.com/Gegcuk/QuizMaker/issues/771)
- [Policy-Driven Quiz Execution Architecture](../architecture/policy-driven-quiz-execution.md)
