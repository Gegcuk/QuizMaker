# Health Probes and Operator Diagnostics

## Contract

Product clients, including iOS, must not use Actuator endpoints as application
APIs. Only liveness is exposed on the public application listener.

| Endpoint | Listener | Access | Purpose | Response |
| --- | --- | --- | --- | --- |
| `GET /actuator/health/liveness` | Public application port `8080` | Public | Process and routing smoke check | Status only |
| `GET /api/v1/health` | Public application port `8080` | Public | Backward-compatible liveness alias | Status only |
| `GET /actuator/health/liveness` | Container loopback `127.0.0.1:8081` | Container/operator | Internal liveness group | Status only |
| `GET /actuator/health/readiness` | Container loopback `127.0.0.1:8081` | Container/operator | Docker traffic/deployment decision | Status only |
| `GET /actuator/health/startup` | Container loopback `127.0.0.1:8081` | Container/operator | Bootstrap diagnosis | Status only |
| `GET /actuator/health` | Container loopback `127.0.0.1:8081` | `SYSTEM_ADMIN` | Aggregate diagnosis | Components and details |
| `GET /actuator/health/<component>` | Container loopback `127.0.0.1:8081` | `SYSTEM_ADMIN` | Contributor diagnosis | Restricted details |

The management listener is bound inside the container and is not published by
Docker Compose or proxied by Nginx. Operators reach it with `docker exec` over
SSH. Requests for readiness, startup, or diagnostics on the public listener are
denied or have no route. A regular authenticated user cannot read diagnostics,
even through the private listener.

Status-only responses contain exactly `{"status":"UP"}` or
`{"status":"DOWN"}`. They never contain component names, exception text,
hosts, paths, capacities, account identifiers, request identifiers, or provider
details. `HEAD` is not part of the probe contract; use `GET`.

Actuator paths remain outside public OpenAPI. The legacy `/api/v1/health`
operation remains in the `admin` OpenAPI group for compatibility.

## Probe semantics

Liveness answers whether this JVM should continue running. It does not include
the database, email, AI, Stripe, object storage, or another remote dependency.
A dependency outage must not create a restart loop.

Readiness answers whether this instance can serve normal API traffic. It
includes `readinessState`, `db`, and `diskSpace`; a database or writable-disk
failure returns HTTP `503`. Required Spring health indicators convert checked
failures to `DOWN`, while the readiness group suppresses their details.

Startup includes `ping` and `db`. It is available for private diagnosis and is
not the ongoing container health check.

AWS SES and other optional internet services are diagnostics only. A rejected
or unavailable SES diagnostic uses a bounded reason category and does not make
readiness fail. Do not broaden IAM permissions merely to make aggregate health
green. The generic Spring Mail health contributor is disabled because the
production provider is SES and the inherited SMTP transport is unused; if
production moves to SMTP, add or enable a provider-specific diagnostic as part
of that migration.

## Deployment behavior

The image and Compose health checks run inside each container and call:

```text
http://127.0.0.1:8081/actuator/health/readiness
```

Candidate and replacement containers must reach Docker `healthy` before the
handoff succeeds. Each curl attempt has a 10-second timeout; four failed checks
after the start period make the container unhealthy. The CD smoke check then
verifies that Nginx can route to the public application listener at:

```text
https://www.quizzence.com/actuator/health/liveness
```

The external liveness check is not a substitute for readiness. It runs only
after the workflow has accepted Docker's private readiness result.

## Incident triage

Run these commands over SSH on the Droplet from
`/var/www/quizmaker-backend`.

1. Resolve the active backend container:

   ```bash
   BACKEND_CONTAINER=$(docker compose --env-file .env ps -q quizmaker-backend)
   test -n "$BACKEND_CONTAINER"
   ```

2. Check private readiness:

   ```bash
   docker exec "$BACKEND_CONTAINER" curl --request GET --silent --show-error --include http://127.0.0.1:8081/actuator/health/readiness
   ```

3. Check public liveness separately:

   ```bash
   curl --request GET --silent --show-error --include https://www.quizzence.com/actuator/health/liveness
   ```

4. If liveness is `UP` and readiness is `DOWN`, read a `SYSTEM_ADMIN` token
   without putting it in shell history:

   ```bash
   read -r -s -p "SYSTEM_ADMIN access token: " SYSTEM_ADMIN_ACCESS_TOKEN; printf '\n'
   ```

5. Request restricted aggregate diagnostics through container stdin:

   ```bash
   printf 'header = "Authorization: Bearer %s"\n' "$SYSTEM_ADMIN_ACCESS_TOKEN" | docker exec -i "$BACKEND_CONTAINER" curl --request GET --silent --show-error --include --config - http://127.0.0.1:8081/actuator/health
   ```

6. Clear the token:

   ```bash
   unset SYSTEM_ADMIN_ACCESS_TOKEN
   ```

Restore the failing required dependency rather than restarting a live JVM. Do
not publish restricted diagnostic output without removing sensitive operational
data.
