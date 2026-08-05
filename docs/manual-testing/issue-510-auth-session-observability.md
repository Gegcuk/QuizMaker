# Manual Test: Authentication Session Observability (#510)

## Purpose

Verify that authentication-session telemetry is useful without exposing
credentials or identity data, and that concurrent refresh attempts remain
serialized. This change does not alter frontend requests, responses, token
field names, or normal login/logout behavior.

## Preconditions

- Use a non-production account and two separate browser profiles or REST
  clients.
- Open [Swagger UI](https://www.quizzence.com/swagger-ui/index.html) to obtain
  a current access/refresh pair through `POST /api/v1/auth/login`.
- Use only an operator-controlled metrics endpoint or monitoring system. Do
  not expose actuator metrics publicly and do not put JWTs in dashboards,
  tickets, shell history, or shared chat.

## 1. Lifecycle metrics

1. Record the current values for the following counters in the private
   monitoring system: `auth.sessions.issued`,
   `auth.sessions.refresh.succeeded`, and
   `auth.sessions.logout.succeeded`.
2. Log in once, refresh the returned refresh token once, then log out with the
   returned access token.
3. Refresh the monitored counters.

Expected result: each corresponding counter increases. The login and refresh
responses still contain only `accessToken`, `refreshToken`,
`accessExpiresInMs`, and `refreshExpiresInMs`.

## 2. Rejection and privacy metrics

1. Send the refresh token as the bearer token to `GET /api/v1/auth/me`.
2. In the private monitoring system, inspect `auth.sessions.rejected`.
3. Inspect the metric tags and the application logs produced during the test.

Expected result: `/me` returns `401`. The rejection counter has only bounded
`operation` and `reason` tags. Neither those tags nor the related log entries
contain a username, user ID, session ID, JWT, refresh verifier, or redirect
URL.

## 3. Concurrent refresh protection

1. Log in again and copy the current refresh token into two isolated clients.
2. Send `POST /api/v1/auth/refresh` from both clients at nearly the same time.
3. Use the replacement access token returned to the successful client on
   `GET /api/v1/auth/me`.

Expected result: one refresh returns `200` with a replacement token pair. The
other returns `401`, because it presented refresh material that was rotated by
the first transaction. The successful replacement access token is also denied
after the replay because the session fails closed. The monitoring system shows
a bounded refresh rejection with reason `replayed_token`.

## 4. Store-failure signal

Do not interrupt the production database to perform this check. In a controlled
non-production environment, use the existing operational fault-injection or
temporary database-unavailability procedure while requesting a protected route
with a valid bearer token.

Expected result: the request is not authenticated, no credential is logged,
and `auth.sessions.store.failures` increases. Restore database connectivity
using the environment's normal recovery procedure, then verify a newly issued
session can authenticate.
