# Issue #770 Manual Test Guide: Cancellation-Aware Provider Backoff

## Purpose

Verify that a cancelled quiz-generation job stops a provider retry wait promptly and does not dispatch another OpenAI request.

This change preserves the existing generation contract:

- all requested questions of one type are still generated in one provider request;
- retry classification, `Retry-After` handling, the five-attempt default, and the `60s` delay cap remain unchanged;
- cancellation still returns the existing empty response with `Generation cancelled by user` and zero reported tokens;
- the already-failed provider request remains counted against the shared attempt budget;
- direct or legacy callers without a cancellation checker retain one uninterrupted retry wait;
- API, frontend, database, billing, coverage, fallback, and finalization behavior remain unchanged.

This issue does not abort a provider HTTP request that is already running. The configured transport deadline still bounds that request. Total generation deadlines, queued-task removal, admission fairness, and executor tuning remain separate #442/#455 work.

## Automated Offline Verification

Run locally with Java 17:

```bash
JAVA_HOME=/path/to/java-17 ./mvnw \
  -Dtest=SpringAiStructuredClientCancellationAwareRetryTest,SpringAiStructuredClientCancellationTest,SpringAiStructuredClientProviderRetryTest,SpringAiStructuredClientAttemptBudgetTest \
  test
```

Expected result: all tests pass.

The focused tests prove:

- cancellation during a simulated five-second backoff is observed after one bounded one-second slice;
- exactly one provider request is made and exactly one shared attempt permit is consumed;
- a non-cancelled 2.5-second wait completes as `1000ms + 1000ms + 500ms` before the retry succeeds;
- a request without a cancellation checker retains one `2500ms` wait;
- interruption restores the thread interrupt flag and suppresses the retry;
- existing before-attempt cancellation, provider retry classification, and attempt-budget behavior remain compatible.

These tests replace sleeping with an in-memory recorder and use a mocked `ChatClient`. They do not call OpenAI, require an API key, connect to MySQL, or start Docker.

## Code Inspection

Run locally from the repository root:

```bash
rg -n "CANCELLATION_CHECK_INTERVAL_MS|waitForRetry|cancelledResponse" \
  src/main/java/uk/gegc/quizmaker/features/ai/application/impl/SpringAiStructuredClient.java
```

Expected result:

- the cancellation-check interval is `1000ms`;
- only positive retry delays with an existing cancellation checker are divided into slices;
- callers without a checker still call the existing sleep method once;
- the same cancelled response is used before an attempt and during backoff.

## Functional Verification

Use a local or non-production environment with a fake provider that can return one retryable `429` or `503` response with a positive `Retry-After`. Do not deliberately rate-limit or exhaust the paid production account.

1. Start one quiz generation through the existing frontend.
2. Make the fake provider return the retryable response for the first provider request.
3. While the backend is waiting, cancel the job through the existing frontend action or cancellation endpoint.
4. Verify the backend emits one `Generation cancelled during retry wait` log within approximately one second after the persisted cancellation becomes visible.
5. Verify the fake provider received exactly one request for that chunk and question type.
6. Verify the job follows the existing cancelled lifecycle and no new questions are persisted after cancellation.
7. Start a second generation without cancelling it and let the fake provider succeed on the retry.
8. Verify the second job completes normally and still sends one batch request per question type attempt.

The exact delay between the user's click and the backend log also includes the existing cancellation endpoint/database update latency. The new wait itself checks at most once per second.

## Production Verification

After a human-reviewed branch is merged and deployed, verify public liveness:

```bash
curl --fail --silent --show-error https://www.quizzence.com/actuator/health/liveness
```

Expected result: `{"status":"UP"}`.

Create one small normal quiz through the current frontend. Expected result:

- acceptance and polling work exactly as before;
- a normal generation completes;
- all requested questions of one type remain batched in one OpenAI request;
- existing billing and coverage behavior is unchanged.

Do not force a provider failure in production. If a natural retry and user cancellation coincide, the new structured-client message contains only the attempt ordinal, requested count, and question type. Existing job-lifecycle correlation logs are unchanged. Logs must not contain a prompt, document content, provider response, API key, or filename.

## Performance And Query Check

The cancellation checker already performs the server-side job-status lookup. During a positive provider backoff, it is now invoked at most once per second per waiting provider task, plus the existing boundary checks. It is time-bounded and independent of document size, chunk count, or number of related database rows.

N+1 is not applicable: this issue changes no entity relationship, collection mapper, serialization path, repository list query, or document read. No document content is copied or hashed.

## Rollback

Redeploy the previous healthy image. There is no migration, stored-data, API, frontend, billing, or question-format rollback.

After rollback, cancellation is still checked before every provider attempt, but cancellation during a positive retry delay may again leave the worker waiting for the rest of that delay.

## References

- [Issue #770](https://github.com/Gegcuk/QuizMaker/issues/770)
- [Parent issue #442](https://github.com/Gegcuk/QuizMaker/issues/442)
- [Policy-Driven Quiz Execution Architecture](../architecture/policy-driven-quiz-execution.md)
