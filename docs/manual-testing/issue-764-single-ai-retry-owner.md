# Issue 764: Single AI Retry Owner Manual Verification

## Purpose

Verify that one application provider attempt produces exactly one low-level OpenAI request. The existing application retry loop and shared five-attempt budget remain responsible for retries, cancellation checks, provider-usage handling, and fallback behavior.

No public API, database schema, billing rule, question batching, frontend behavior, or future attempt-policy contract changes.

## Automated Verification

Run locally from the repository root:

1. `./mvnw -Dtest=AiRetryOwnershipTest,DeploymentAiRetryOwnershipConfigurationContractTest test`

Expected result: three focused tests pass. The transient-failure test verifies exactly one low-level `OpenAiApi` invocation, the success test verifies unchanged one-call behavior, and the deployment contract verifies every runtime properties file fixes Spring AI's inner retry count at one.

No OpenAI key, network access, MySQL, Docker, or paid provider call is required.

## Local Configuration Check

Run locally from the repository root:

1. `rg -n "spring\.ai\.retry\.max-attempts" src/main/resources/application.properties src/main/resources/application-prod.properties.example server/backend/application-prod.properties`

Expected result: each file contains `spring.ai.retry.max-attempts=1` exactly once.

Do not increase this value to tune quiz-generation retries. Use `ai.rate-limit.max-attempts-per-task` for the application-owned aggregate limit so cancellation and usage accounting remain effective for every real dispatch.

## Production Verification

After the human owner has pushed, merged, and deployed the branch:

1. Over SSH on the Droplet, run `cd /var/www/quizmaker-backend`.
2. Over SSH on the Droplet, run `docker compose --env-file .env exec quizmaker-backend sh -lc "grep '^spring.ai.retry.max-attempts=1$' /app/config/application-prod.properties"`.
3. Locally, run `curl --silent --show-error https://www.quizzence.com/actuator/health/liveness`.

Expected result: the container configuration prints the one-attempt setting and liveness returns `{"status":"UP"}`.

Do not force a provider outage or make repeated paid requests in production. CI's model-boundary test supplies deterministic evidence for the failure path.

## User Compatibility Check

Optionally generate one small quiz through the existing UI after deployment.

Expected result: submission, progress, generated question types, billing, and final quiz behavior are unchanged. This check is only a successful-path smoke test; it is not required to prove retry counts.

## Rollback

This change has no data migration or persistent-state effect. If an unexpected provider compatibility issue appears, redeploy the previous healthy backend image using the established deployment rollback procedure. Do not raise Spring AI's inner retry count as an emergency workaround; that would bypass the shared provider-attempt budget.

## N+1 Evidence

Not applicable. The change does not execute JPA queries, load relationships, map persistent collections, or serialize entities.
