# Issue #768 Manual Test Guide: Provider Retry Classification

## Purpose

Verify that QuizMaker retries temporary OpenAI HTTP failures at a safe bounded time and stops permanent failures immediately.

This change keeps the existing generation contract:

- one provider request still asks for the full requested batch for one question type;
- the shared provider-attempt budget and five-attempt default remain unchanged;
- the maximum accepted wait remains `60s`;
- successful response parsing, fallback, coverage, billing, finalization, and polling remain unchanged.

It does not add the separate total deadline, active in-flight cancellation, cancellation-aware backoff, queue fairness, or provider metrics tracked by #442/#455/#465.

## Automated Offline Verification

Run locally with Java 17:

```bash
JAVA_HOME=/path/to/java-17 ./mvnw \
  -Dtest=OpenAiProviderResponseErrorHandlerTest,OpenAiProviderHttpConfigurationTest,SpringAiStructuredClientProviderRetryTest \
  test
```

Expected result:

- all tests pass;
- temporary HTTP 408, 409, 429, and 5xx failures are retryable;
- documented credit, spend, usage, and legacy quota 429 codes are terminal;
- delta-seconds and RFC 1123 `Retry-After` values are parsed;
- malformed, negative, past, overflowing, oversized, and sensitive inputs remain bounded and private;
- a provider delay within `60s` is honored before one more dispatch;
- a provider delay above `60s` causes no sleep and no early retry;
- every additional provider dispatch consumes one shared attempt permit;
- invalid generated-question JSON retains its existing immediate retry behavior;
- Spring AI uses exactly one project-owned response error handler.

These tests use mocks and in-memory HTTP responses. They do not call OpenAI, require an API key, connect to MySQL, or start Docker.

Run the focused compatibility checks locally:

```bash
JAVA_HOME=/path/to/java-17 ./mvnw \
  -Dtest=SpringAiStructuredClientAttemptBudgetTest,SpringAiStructuredClientRequestContractTest,SpringAiStructuredClientCancellationTest,SpringAiStructuredClientProviderUsageTest,SpringAiStructuredClientPromptPrivacyTest,AiRetryOwnershipTest \
  test
```

Expected result: all existing attempt-budget, request-contract, cancellation, usage, privacy, and single-retry-owner tests pass.

## Configuration Inspection

Run locally from the repository root:

```bash
rg -n "max-retries|max-delay-ms|max-attempts-per-task" \
  src/main/resources/application.properties \
  src/main/resources/application-prod.properties.example \
  server/backend/application-prod.properties
```

Expected result: the existing application-owned limits remain in place. This issue does not increase the provider dispatch count or the `60000ms` maximum delay.

## Deterministic Failure Semantics

The automated tests are the preferred way to verify failure handling because inducing provider errors against a paid production account is unsafe.

Expected behavior by response:

| Provider response | Expected action |
| --- | --- |
| `429` temporary limit, `Retry-After: 3` | Wait at least 3 seconds, then use one remaining attempt permit for one dispatch |
| `429` temporary limit, missing or invalid header | Use bounded exponential backoff with jitter |
| `429`, `Retry-After: 61` | Stop; do not wait 61 seconds and do not retry before the provider minimum |
| `429` with a known credit/spend/usage/quota code | Stop immediately; operator action is required |
| `400`, `401`, `403`, `404`, or `422` | Stop immediately |
| `408`, `409`, or `5xx` | Retry with bounded backoff while attempts remain |
| Interrupted backoff | Restore the thread interrupt flag and stop |

Provider response bodies and provider messages must never appear in application exceptions or QuizMaker logs. Only stable categories, status, attempt number, and bounded delay may be recorded.

## Production Verification

After a human-reviewed branch is merged and deployed, first verify public liveness:

```bash
curl --fail --silent --show-error https://www.quizzence.com/actuator/health/liveness
```

Expected result: `{"status":"UP"}`.

Create one small normal quiz through the existing frontend and request more than one question of one type. Expected result:

- the job is accepted and polled exactly as before;
- all requested questions of that type are still generated as one provider batch, not one request per question;
- valid output completes normally;
- existing coverage and billing behavior remains unchanged.

Do not deliberately exhaust credits, submit invalid credentials, or force a long provider failure in production. Use the offline tests for those paths.

Inspect recent container logs over SSH on the Droplet:

```bash
docker compose --env-file .env logs --since=10m quizmaker-backend
```

Expected result: normal generation logs contain no prompt, source content, provider response body, API key, token, user ID, document ID, or filename. A naturally occurring failure may contain only its stable category, attempt ordinal, and bounded delay.

## Rollback

The preferred rollback is to redeploy the previous healthy image. No database, stored job, question, billing, API, or frontend rollback is required because this issue changes no persisted or public contract.

After rollback, verify container readiness and public liveness before accepting new generation traffic.

## Compatibility And Safety Evidence

- No public API, OpenAPI schema, frontend contract, database schema, entity, migration, billing rule, prompt, model option, question schema, coverage threshold, fallback sequence, or batching behavior changes.
- Existing questions, jobs, reservations, and legacy clients remain compatible.
- Error-body reads are capped at 16 KiB and retain only bounded `error.code` / `error.type` identifiers for classification.
- No automated test contacts a real provider or uses production credentials.
- No document content is read, copied, or hashed.
- N+1 is not applicable because no repository, entity relationship, mapper, serialization, or persistence read path changes.

## References

- [OpenAI rate-limit guidance](https://developers.openai.com/api/docs/guides/rate-limits)
- [OpenAI error-code guidance](https://developers.openai.com/api/docs/guides/error-codes)
- [Policy-Driven Quiz Execution Architecture](../architecture/policy-driven-quiz-execution.md)
