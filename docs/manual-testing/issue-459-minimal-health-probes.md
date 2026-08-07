# Manual Verification: Issue 459 Private Readiness Boundary

## Purpose

Verify that only status-only liveness is public, readiness and startup remain
inside the backend container, required dependency failures affect readiness but
not liveness, and detailed diagnostics require `SYSTEM_ADMIN`. No database
migration or product API change is involved.

## Prerequisites

- JDK 17.
- Docker and Docker Compose.
- A disposable environment started from this branch.
- A test `SYSTEM_ADMIN` access token and a regular-user access token for the
  authorization checks. Do not use production tokens for local verification.

## Automated checks

1. Run locally from `<LOCAL_REPOSITORY_PATH>`:

   ```bash
   JAVA_HOME=<JDK_17_HOME> ./mvnw test -Dtest=PublicApiRoutesTest,AwsSesHealthIndicatorTest,UtilityControllerTest,HealthEndpointSecurityTest,ProductionHealthConfigurationTest,DeploymentHealthConfigurationContractTest,UtilityHealthOpenApiContractTest
   ```

2. Verify Maven reports zero failures and zero errors. The real Actuator test
   starts two ephemeral loopback listeners and does not require MySQL or a
   remote provider.

## Public boundary

1. Run locally against the backend application port:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/liveness
   ```

2. Verify HTTP `200`, body `{"status":"UP"}`, and no `components`, `details`,
   exception, host, path, capacity, account, or request identifier.

3. Verify the legacy alias has the same status-only shape:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/api/v1/health
   ```

4. Verify readiness and startup are not public:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/readiness
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/startup
   ```

5. Verify each request returns HTTP `401` with no health data. When Nginx uses
   the repository setup template, it may reject these paths as `404` before
   they reach Spring; either result is intentionally non-public.

## Private probes and permissions

Run these commands locally from the directory containing `.env` and
`server/backend/docker-compose.yml`.

1. Resolve the backend container:

   ```bash
   BACKEND_CONTAINER=$(docker compose --env-file .env --file server/backend/docker-compose.yml ps -q quizmaker-backend)
   test -n "$BACKEND_CONTAINER"
   ```

2. Verify private readiness and startup are status-only:

   ```bash
   docker exec "$BACKEND_CONTAINER" curl --request GET --silent --show-error --include http://127.0.0.1:8081/actuator/health/readiness
   docker exec "$BACKEND_CONTAINER" curl --request GET --silent --show-error --include http://127.0.0.1:8081/actuator/health/startup
   ```

3. Verify aggregate diagnostics reject an anonymous request with HTTP `401`:

   ```bash
   docker exec "$BACKEND_CONTAINER" curl --request GET --silent --show-error --include http://127.0.0.1:8081/actuator/health
   ```

4. Read a regular-user token without adding it to shell history:

   ```bash
   read -r -s -p "Regular access token: " REGULAR_ACCESS_TOKEN; printf '\n'
   ```

5. Verify a regular user receives HTTP `403`:

   ```bash
   printf 'header = "Authorization: Bearer %s"\n' "$REGULAR_ACCESS_TOKEN" | docker exec -i "$BACKEND_CONTAINER" curl --request GET --silent --show-error --include --config - http://127.0.0.1:8081/actuator/health/db
   unset REGULAR_ACCESS_TOKEN
   ```

6. Read a test operator token and request diagnostics:

   ```bash
   read -r -s -p "SYSTEM_ADMIN access token: " SYSTEM_ADMIN_ACCESS_TOKEN; printf '\n'
   printf 'header = "Authorization: Bearer %s"\n' "$SYSTEM_ADMIN_ACCESS_TOKEN" | docker exec -i "$BACKEND_CONTAINER" curl --request GET --silent --show-error --include --config - http://127.0.0.1:8081/actuator/health
   unset SYSTEM_ADMIN_ACCESS_TOKEN
   ```

7. Verify HTTP `200` and useful component details. Confirm no JWT, raw AWS
   message, ARN, account identifier, or request identifier appears.

## Database failure drill

Use only a disposable local environment.

1. Stop MySQL locally:

   ```bash
   docker compose --env-file .env --file server/backend/docker-compose.yml stop mysql
   ```

2. Wait for the database health check to observe the outage, then check private
   readiness from the still-running backend container:

   ```bash
   docker exec "$BACKEND_CONTAINER" curl --request GET --silent --show-error --include http://127.0.0.1:8081/actuator/health/readiness
   ```

3. Verify readiness returns HTTP `503` with only `{"status":"DOWN"}`.

4. Verify public liveness remains independent:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/liveness
   ```

5. Verify liveness remains HTTP `200` and `UP`.

6. Restore MySQL:

   ```bash
   docker compose --env-file .env --file server/backend/docker-compose.yml start mysql
   ```

7. Wait for recovery and verify private readiness returns HTTP `200` and `UP`.
   Do not fill a real filesystem to test disk exhaustion; the focused automated
   test injects a controlled disk contributor failure.

## Post-deployment verification

1. From a local machine, verify public liveness:

   ```bash
   curl --request GET --silent --show-error --include https://www.quizzence.com/actuator/health/liveness
   ```

2. Verify HTTP `200`, `{"status":"UP"}`, and no diagnostic fields.

3. Verify public readiness, startup, and aggregate diagnostics are denied or
   unreachable:

   ```bash
   curl --request GET --silent --show-error --include https://www.quizzence.com/actuator/health/readiness
   curl --request GET --silent --show-error --include https://www.quizzence.com/actuator/health/startup
   curl --request GET --silent --show-error --include https://www.quizzence.com/actuator/health
   ```

4. Verify none returns health component data.

5. Over SSH on the Droplet, repeat the private readiness and operator checks
   from `docs/runbooks/health-probes.md`.

6. In the CD SSH step, verify `Candidate backend is healthy` and `Replacement
   backend is healthy` appear before the public liveness smoke check and release
   completion. Reverify issue `#421` against the deployed commit before closing
   `#459`.
