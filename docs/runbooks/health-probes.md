# Health Probes and Operator Diagnostics

## Contract

Health endpoints have separate routing and disclosure purposes. Product clients,
including iOS, must not use actuator endpoints as application APIs.

| Endpoint | Access | Purpose | Contributors | Response |
| --- | --- | --- | --- | --- |
| `GET /actuator/health/liveness` | Public | Restart decision | `livenessState`, `ping` | Status only |
| `GET /actuator/health/readiness` | Public | Traffic and deployment decision | `readinessState`, `db`, `diskSpace` | Status only |
| `GET /actuator/health/startup` | Public | Bootstrap diagnosis | `ping`, `db` | Status only |
| `GET /api/v1/health` | Public | Backward-compatible liveness alias | Application liveness | Status only |
| `GET /actuator/health` | `SYSTEM_ADMIN` | Aggregate operator diagnosis | All registered contributors | Components and details |
| `GET /actuator/health/<component>` | `SYSTEM_ADMIN` | One component diagnosis | Selected contributor | Restricted details |

Public responses contain only `{"status":"UP"}` or
`{"status":"DOWN"}`. They must not contain component names, exception text,
hosts, paths, capacities, account identifiers, request identifiers, or provider
details. Spring Security adds no-cache headers. `HEAD` is not part of the public
probe contract; monitoring must use `GET`.

Actuator paths are operational endpoints and remain outside public OpenAPI. The
legacy `/api/v1/health` operation is documented in the `admin` OpenAPI group so
its existing status field remains discoverable.

## Probe semantics

Liveness answers only whether this JVM should continue running. Do not add a
database, email, AI, Stripe, object storage, or other remote dependency to this
group. A remote outage must not cause a restart loop.

Readiness answers whether this instance can serve normal API traffic. The
database and writable disk are required, so either can make readiness return
HTTP `503`. Email and other optional external providers are deliberately
excluded. Docker health and deployment gates use readiness because they are
ongoing traffic decisions, not one-time process-start checks.

Startup is retained as a small bootstrap diagnostic for the application and
database. It is not the Docker health check after startup.

## AWS SES diagnosis

AWS SES account inspection calls `ses:GetAccount`. A least-privilege sender may
be able to send email without that diagnostic permission. Therefore a `403`
from this check is reported as `UNKNOWN` with the bounded reason
`diagnostic-permission-denied`; it does not make readiness fail. Other rejected
or unavailable provider responses report bounded reason categories. Raw AWS
messages, ARNs, account identifiers, and request identifiers are never returned
by the health indicator.

An `UNKNOWN` diagnostic does not prove that email delivery works. Validate email
through the normal provider-specific operational procedure when delivery is in
question. Do not add broader IAM permissions merely to make aggregate health
green without reviewing least privilege.

## Deployment behavior

The image and Compose health checks call
`http://localhost:8080/actuator/health/readiness`. Candidate and replacement
containers must become healthy before handoff completes. The external smoke
check calls the canonical URL directly:

```text
https://www.quizzence.com/actuator/health/readiness
```

Do not check `https://quizzence.com` without redirect handling. A `301` is a
successful HTTP response to plain `curl --fail` and can otherwise be mistaken
for backend health without reaching the application.

## Incident triage

1. Run locally or over SSH on the Droplet:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/liveness
   ```

2. Run locally or over SSH on the Droplet:

   ```bash
   curl --request GET --silent --show-error --include http://localhost:8080/actuator/health/readiness
   ```

3. If liveness is `UP` and readiness is `DOWN`, use a bearer token belonging to
   a `SYSTEM_ADMIN` account over SSH on the Droplet. Do not paste the token into
   shell history; provide it through a temporary protected environment variable:

   ```bash
   curl --request GET --silent --show-error --include --header "Authorization: Bearer <SYSTEM_ADMIN_ACCESS_TOKEN>" http://localhost:8080/actuator/health
   ```

4. Investigate only the failing required contributor. Restore database or disk
   capacity before replacing healthy JVMs. An optional provider failure is an
   application feature incident, not a readiness failure.

5. Verify the public canonical route from a local machine:

   ```bash
   curl --request GET --silent --show-error --include https://www.quizzence.com/actuator/health/readiness
   ```

Never publish or attach restricted diagnostic output without removing sensitive
operational data.
