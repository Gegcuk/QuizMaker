# Issue #766 Manual Test Guide: OpenAI Transport Deadlines

## Purpose

Verify that every non-streaming Spring AI/OpenAI HTTP call has a finite connection and response deadline, while normal generation behavior and application-owned retries remain unchanged.

The defaults are:

- blocking HTTP client: JDK client (pinned to prevent classpath-driven drift);
- connection establishment: `10s`;
- one blocking provider response: `180s`;
- environment overrides: `AI_PROVIDER_CONNECT_TIMEOUT` and `AI_PROVIDER_READ_TIMEOUT`.

This slice does not add the separate total deadline across all retries and fallbacks.

## Automated Offline Verification

Run locally with Java 17:

```bash
JAVA_HOME=/path/to/java-17 ./mvnw \
  -Dtest=DeploymentAiProviderHttpTimeoutConfigurationContractTest,OpenAiTransportTimeoutTest,AiRetryOwnershipTest \
  test
```

Expected result:

- all tests pass;
- the delayed loopback provider call ends at the configured test deadline;
- the fast loopback provider response succeeds exactly once;
- malformed duration configuration fails closed;
- Spring AI still makes one low-level dispatch for each application-owned attempt.

The tests use a loopback HTTP server and a fake API key. They do not call OpenAI, require MySQL, start Docker, or use production credentials.

## Configuration Inspection

Run locally from the repository root:

```bash
rg -n "spring\.http\.client\.(factory|(connect|read)-timeout)" \
  src/main/resources/application.properties \
  src/main/resources/application-prod.properties.example \
  server/backend/application-prod.properties
```

Expected result: all three files pin the JDK client and contain the same `10s` connect and `180s` read defaults with the same environment-variable overrides.

Optional local override:

```bash
export AI_PROVIDER_CONNECT_TIMEOUT=5s
export AI_PROVIDER_READ_TIMEOUT=120s
```

Use positive Spring `Duration` values such as `500ms`, `10s`, `2m`, or `PT2M`. An invalid duration prevents configuration binding instead of disabling the limit.

## Production Verification

After the human-reviewed branch is merged and deployed, run over SSH on the Droplet from the deployment directory:

```bash
docker compose --env-file .env exec quizmaker-backend \
  sh -lc 'grep "^spring.http.client." /app/config/application-prod.properties'
```

Expected result:

```text
spring.http.client.factory=jdk
spring.http.client.connect-timeout=${AI_PROVIDER_CONNECT_TIMEOUT:10s}
spring.http.client.read-timeout=${AI_PROVIDER_READ_TIMEOUT:180s}
```

Then verify public liveness:

```bash
curl --fail --silent --show-error https://www.quizzence.com/actuator/health/liveness
```

Expected result: `{"status":"UP"}`.

No real-provider failure should be induced in production. Normal quiz generation remains a batched request per chunk/question type and should retain its existing job polling, coverage, and billing behavior.

## Failure Semantics

- A connection that cannot be established within `10s` fails the current provider attempt.
- A connected non-streaming call that does not complete within `180s` fails the current provider attempt.
- Existing QuizMaker retry and shared-attempt-budget logic decides whether another provider attempt is allowed.
- Spring AI does not add a hidden retry, so one budget permit still corresponds to one real provider call.
- A timed-out or partial HTTP response is never parsed as generated questions.
- Immediate cancellation of an already-running HTTP call and a total deadline across retries remain follow-up work under #442/#455.

## Rollback

The preferred rollback is to redeploy the previous healthy image. For a temporary configuration-only rollback, set larger positive durations in the deployment environment and redeploy; do not remove both limits during an outage.

```bash
AI_PROVIDER_CONNECT_TIMEOUT=20s
AI_PROVIDER_READ_TIMEOUT=300s
```

Verify container readiness and public liveness after any rollback.

## Compatibility And Safety Evidence

- No public API, OpenAPI schema, frontend contract, database schema, entity, billing rule, prompt, model option, question schema, coverage threshold, or batching behavior changes.
- No document content is hashed or reread.
- Logs and tests contain no source text, prompt, model response from a real provider, API key, user ID, document ID, or production credential.
- N+1 is not applicable because no repository, entity relationship, mapper, or persistence read path changes.
