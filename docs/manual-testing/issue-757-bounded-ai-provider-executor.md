# Issue 757: Bounded AI Provider Executor

## Purpose

Verify that provider-bound chunk work uses a dedicated bounded executor, fails
closed at capacity, and preserves the existing one-request-per-type batching,
coverage, billing, and client contracts. Automated verification is offline and
does not call OpenAI or require MySQL.

Read [AI Provider Execution](../runbooks/ai-provider-execution.md) for the runtime
contract and configuration units.

## Deterministic Offline Verification

Run locally from the repository root.

1. Select JDK 17:

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ```

2. Run only the focused tests:

   ```bash
   ./mvnw -Dtest=ExecutorAiProviderTaskSchedulerTest,AiProviderExecutorConfigTest,DeploymentAiProviderExecutorConfigurationContractTest,AiQuizGenerationTaskSchedulingTest,AiQuizGenerationServiceProviderUsageTest test
   ```

3. Confirm `BUILD SUCCESS` with twenty passing tests and no MySQL tests.

The tests prove:

- active work never exceeds the configured worker maximum;
- one worker plus one queued task causes the next task to fail with
  `AiProviderCapacityException` without caller execution;
- executed tasks use an `ai-provider-*` thread and not `ForkJoinPool`;
- task failure does not poison reusable capacity;
- a queued future cancelled before execution cannot invoke its task;
- shutdown drains accepted work and rejects later submissions;
- invalid startup bounds fail deterministically;
- Spring wiring binds the application scheduler port to the named provider executor;
- packaged defaults, deployment properties, Compose forwarding, and the production
  environment example stay aligned;
- a request for three questions of one type produces one structured-client call
  whose `questionCount` remains three;
- a scheduler rejection invokes no structured client.
- provider-usage persistence failure still bypasses every generation fallback
  after crossing the scheduler boundary.

Do not use `-DskipTests`, a live-provider profile, or real provider credentials.

## Static Checks

1. Confirm production code has no implicit CompletableFuture pool submission:

   ```bash
   rg -n 'CompletableFuture\.(supplyAsync|runAsync)' src/main/java
   ```

   Expected: no matches.

2. Confirm provider execution does not use caller-runs:

   ```bash
   rg -n 'aiProviderTaskExecutor|AbortPolicy|CallerRunsPolicy' src/main/java/uk/gegc/quizmaker/shared/config/AsyncConfig.java
   ```

   Expected: `aiProviderTaskExecutor` uses `AbortPolicy`; existing orchestration
   or general executor policy is outside this issue.

3. Confirm the service submits one task at the chunk boundary:

   ```bash
   rg -n 'aiProviderTaskScheduler.submit' src/main/java/uk/gegc/quizmaker/features/ai/application/impl/AiQuizGenerationServiceImpl.java
   ```

## Optional Local Runtime Check

This check is observational and does not require deliberately saturating a
provider or spending tokens.

1. Start the backend locally with its normal configuration.
2. Confirm startup contains one bounded configuration line beginning with
   `AI Provider Task Executor configured`.
3. If you independently run a normal fake-provider generation, inspect a thread
   dump while the fake is blocked and confirm the task thread begins with
   `ai-provider-`.
4. Do not use a real OpenAI call merely to create a thread for this check.

## Compatibility

- Existing generation endpoints, request/response schemas, `202` acceptance,
  polling, permissions, ownership, and frontend behavior are unchanged.
- Existing generated/manual questions and legacy jobs are unchanged.
- Type, difficulty, language request propagation, fallback count,
  redistribution, the strict coverage threshold, valid-question billing, and
  finalization remain owned by their existing services.
- All questions requested for one type and chunk remain in one provider request;
  the executor does not fan out by question.

## Failure And Rollback

At worker-plus-queue capacity, rejected work performs no provider call and does
not run on an orchestration or HTTP thread. Existing coverage determines whether
other valid chunk output is sufficient; insufficient output follows the existing
zero-charge failure/release path.

Rollback requires reverting the application commit and removing optional
`async.ai.provider.*` overrides. There is no migration, backfill, or data repair.

## Privacy, N+1, And Document Content

- Rejection logging contains one bounded reason and no payload or identifier.
- No repository, entity graph, relationship, or status-page query changed; N+1
  behavior is unchanged and no new query exists.
- No document is copied, reread, fingerprinted, or hashed for scheduling.
- No automated test calls OpenAI, Stripe, a document parser, MySQL, or the network.
