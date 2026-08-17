# Issue 762: Shared AI Provider Attempt Budget

## Purpose

Verify that one chunk/question-type generation operation has a single bounded
provider-dispatch budget across the structured client's retries and the
service's normal and reduced-count fallback attempts.

The default budget is five actual provider dispatches. Prompt rendering,
request validation, and pre-dispatch cancellation do not consume the budget.

## Deterministic Offline Verification

Run locally from the repository root. These tests use mocks and fake provider
responses; they do not call OpenAI or require MySQL.

1. Select JDK 17:

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ```

2. Run the focused tests:

   ```bash
   ./mvnw -Dtest=ProviderAttemptBudgetTest,SpringAiStructuredClientAttemptBudgetTest,AiQuizGenerationServiceFallbackTest,DeploymentAiAttemptBudgetConfigurationContractTest test
   ```

3. Confirm the command ends with `BUILD SUCCESS` and reports no failures.

The tests prove:

- a non-positive budget is rejected;
- concurrent acquisition cannot exceed the configured limit;
- each real provider dispatch consumes one permit;
- successful generation consumes only the permit it uses;
- provider failures stop after the shared limit;
- prompt construction and cancellation consume no permit;
- normal and reduced-count attempts receive the same budget instance;
- requests that do not supply a shared budget retain the structured client's
  existing `max-retries` behavior;
- packaged, production-template, deployed, Compose, and environment-example
  defaults remain aligned at five.

Do not use `-DskipTests`, live-provider test profiles, or real provider
credentials for this verification.

## Configuration Inspection

1. Check the application and deployment settings:

   ```bash
   rg -n "max-attempts-per-task|AI_RATE_LIMIT_MAX_ATTEMPTS_PER_TASK" src/main/resources server/backend
   ```

2. Confirm the effective defaults are:

   ```text
   ai.rate-limit.max-attempts-per-task=5
   AI_RATE_LIMIT_MAX_ATTEMPTS_PER_TASK=5
   ```

3. Confirm production may override the value through
   `AI_RATE_LIMIT_MAX_ATTEMPTS_PER_TASK`, while an absent override keeps the
   default of five.

## Optional Local Failure Check

Use only a development provider key and non-sensitive sample text.

1. Set `AI_RATE_LIMIT_MAX_ATTEMPTS_PER_TASK=2` in the local environment.
2. Configure an intentionally invalid development model name so dispatches fail.
3. Start the application and request one question type for one small chunk.
4. Confirm logs contain the fixed `Provider attempt budget exhausted` category
   after at most two provider dispatches for that chunk/type operation.
5. Remove the invalid model and the temporary budget override after the check.

Do not perform this failure injection in production.

## Compatibility

- Generation URLs, request/response schemas, OpenAPI, authorization, ownership,
  frontend behavior, and stored question formats are unchanged.
- All requested questions of one type in one chunk are still batched into one
  provider request; this change does not create one request per question.
- Same-type, same-difficulty retries and reduced-count fallback remain available
  while budget permits remain.
- Direct structured-client callers that omit the optional in-memory budget keep
  the existing configured retry count.
- Coverage, billing, provider usage persistence, cancellation, and question
  validation rules are unchanged.

## Failure And Rollback

Budget exhaustion stops further provider dispatches for that logical
chunk/question-type fallback operation and returns control to the existing
coverage/failure handling. It does not persist partial retry state or change
database data.

An emergency operational reduction or increase can use
`AI_RATE_LIMIT_MAX_ATTEMPTS_PER_TASK`, which must remain at least one. Full
rollback is a code/config revert; no migration, data repair, or frontend rollback
is required.

## Security, N+1, And Document Content

- Logs contain bounded type, chunk, and attempt counts. The budget adds no
  prompt, response, document, credential, or provider exception content.
- No repository, entity relationship, mapper, or query changed. N+1 behavior is
  not applicable to this change.
- The budget is a bounded in-memory counter. It does not hash, copy, persist, or
  compare document content.
- Automated verification calls no OpenAI, Stripe, storage, email, database, or
  other external service.
