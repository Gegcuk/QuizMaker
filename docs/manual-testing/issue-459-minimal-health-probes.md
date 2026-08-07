# Manual Verification: Issue 459 Minimal Health Probes

## Purpose

Verify that public probes are minimal, required dependency failures affect
readiness but not liveness, detailed diagnostics require `SYSTEM_ADMIN`, and CD
checks the canonical readiness endpoint. No database migration is involved.

## Prerequisites

- A local JDK 17 installation.
- A local Docker and Docker Compose installation.
- A test environment with the backend and MySQL started from this branch.
- A valid access token for a test account with `SYSTEM_ADMIN` permission.
- Do not use a production token for local verification.

## Automated checks

1. Run locally from `<LOCAL_REPOSITORY_PATH>`:

   ```bash
   JAVA_HOME=<JDK_17_HOME> ./mvnw test -Dtest=PublicApiRoutesTest,AwsSesHealthIndicatorTest,UtilityControllerTest,HealthEndpointSecurityTest,ProductionHealthConfigurationTest,DeploymentHealthConfigurationContractTest,UtilityHealthOpenApiContractTest
   ```

2. Verify Maven reports zero failures and zero errors for the listed classes.

## Public response checks

1. Run locally against the test backend:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/liveness
   ```

2. Verify HTTP `200`, a status-only JSON body, and no `components`, `details`,
   `error`, host, path, capacity, account, or request identifier.

3. Repeat locally for readiness and startup:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/readiness
   ```

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/startup
   ```

4. Verify both responses are status-only and contain no component names.

5. Run locally against the backward-compatible API path:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/api/v1/health
   ```

6. Verify the body is exactly the existing status shape and contains no
   diagnostics: `{"status":"UP"}`.

## Authorization checks

1. Run locally without authentication:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health
   ```

2. Verify HTTP `401` and no health component data.

3. Run locally without authentication for a component:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/awsSes
   ```

4. Verify HTTP `401` and no AWS diagnostic data.

5. Set a temporary test token locally without writing it to a repository file:

   ```bash
   export SYSTEM_ADMIN_ACCESS_TOKEN=<SYSTEM_ADMIN_ACCESS_TOKEN>
   ```

6. Run locally with the authorized token:

   ```bash
   curl --request GET --silent --show-error --include --header "Authorization: Bearer ${SYSTEM_ADMIN_ACCESS_TOKEN}" http://localhost:8080/actuator/health
   ```

7. Verify the operator can see component status. If SES account inspection lacks
   permission, verify it is `UNKNOWN` with
   `reason=diagnostic-permission-denied` and contains no ARN, AWS account ID,
   provider exception message, or request ID.

8. Clear the temporary token locally:

   ```bash
   unset SYSTEM_ADMIN_ACCESS_TOKEN
   ```

## Dependency failure drill

Use only a disposable local test environment.

1. Stop MySQL locally from `<LOCAL_REPOSITORY_PATH>`:

   ```bash
   docker compose --env-file .env --file server/backend/docker-compose.yml stop mysql
   ```

2. Wait for the connection pool health check to observe the outage, then run
   locally:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/readiness
   ```

3. Verify readiness returns HTTP `503` with a status-only body.

4. Run locally:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/liveness
   ```

5. Verify liveness remains HTTP `200` and `UP`.

6. Restore MySQL locally:

   ```bash
   docker compose --env-file .env --file server/backend/docker-compose.yml start mysql
   ```

7. Verify readiness returns HTTP `200` and `UP` again.

## Post-deployment verification

1. Run from a local machine after CD succeeds:

   ```bash
   curl --request GET --silent --show-error --include https://www.quizzence.com/actuator/health/readiness
   ```

2. Verify HTTP `200`, status `UP`, and no diagnostic fields.

3. Run from a local machine:

   ```bash
   curl --request GET --silent --show-error --include https://www.quizzence.com/actuator/health
   ```

4. Verify HTTP `401` and no component details.

5. In GitHub, open the deployment workflow run, expand the SSH deployment step,
   and verify both `Candidate backend is healthy` and `Replacement backend is
   healthy` appear before the deployed release message.

6. Verify the external smoke check did not accept a redirect and the workflow
   completed against `https://www.quizzence.com/actuator/health/readiness`.
