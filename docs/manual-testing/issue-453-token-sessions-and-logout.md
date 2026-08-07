# Manual Test: Token Purpose and Session Logout (#453)

## Purpose

Verify that access and refresh JWTs have different server-enforced purposes,
that logout revokes the current session immediately, and that refresh-token
rotation fails closed on replay. This is an additive API rollout: token response
field names stay unchanged.

## Rollout effect

This release intentionally forces a one-time reauthentication. JWTs issued
before the release have no server session ID and must receive `401` on protected
requests and refresh. Users sign in once to receive a new session-bound pair.

Current configured lifetimes are a 12-hour access token and a 7-day absolute
refresh-session lifetime. Refresh rotates the token but does not extend that
seven-day session lifetime. Existing session-bound tokens without the newer
internal `jti` claim remain compatible and gain a unique `jti` at their next
rotation; response fields are unchanged.

## Preconditions

- Use a non-production account and browser profile.
- Open [Swagger UI](https://www.quizzence.com/swagger-ui/index.html) or use a
  REST client that can retain the login response locally.
- Do not paste tokens into tickets, logs, analytics, or shared chat channels.

## 1. Login and normal access

1. Call `POST /api/v1/auth/login` with valid credentials.
2. Keep the returned `accessToken` and `refreshToken` only in the client.
3. Call `GET /api/v1/auth/me` with `Authorization: Bearer <accessToken>`.

Expected result: login and `/me` return `200`. The token response continues to
contain `accessToken`, `refreshToken`, `accessExpiresInMs`, and
`refreshExpiresInMs`.

## 2. Token-purpose enforcement

1. Call `GET /api/v1/auth/me` with `Authorization: Bearer <refreshToken>`.
2. Call `POST /api/v1/auth/refresh` with the access token in the request body.

Expected result: both calls return RFC 7807 `401 Unauthorized`. The refresh
token never authenticates a protected endpoint, and the access token never
creates a new token pair.

## 3. Rotation and replay protection

1. Call `POST /api/v1/auth/refresh` with the current refresh token.
2. Replace both locally stored tokens with the returned pair.
3. Repeat the refresh call with the old refresh token from step 1.
4. Call `/me` with the access token returned in step 1.

Expected result: step 1 returns `200` with a replacement pair. Step 3 returns
`401`; the replay invalidates the whole session. Step 4 also returns `401`.
When a client cannot tell whether a refresh request reached the server, it must
delete its local credentials and require login rather than retrying the old
refresh token.

## 4. Concurrent single-use enforcement

1. Log in again and place the same current refresh token in two isolated REST
   clients.
2. Send `POST /api/v1/auth/refresh` from both clients at nearly the same time.
3. Use the replacement access and refresh tokens returned by the successful
   request.

Expected result: exactly one request returns `200` and one returns the same
generic `401` used for invalid refresh material. Both replacement tokens from
the `200` response are denied after the replay, proving that whole-session
revocation was committed rather than rolled back with the `401`.

## 5. Logout and safe retry

1. Log in again to obtain a fresh pair.
2. Call `POST /api/v1/auth/logout` with `Authorization: Bearer <accessToken>`.
3. Call `/me` with that access token.
4. Call `/refresh` with that refresh token.
5. Repeat the same logout call once.

Expected result: logout returns `204 No Content`; `/me` and `/refresh` return
`401`; the repeated logout still returns `204`. Logout is safe to retry once
when the client did not receive the first response. The client should delete
both local tokens immediately before or while sending the request.

## 6. Session-store failure

Do not interrupt the production database. In a disposable local environment,
use the existing database fault-injection procedure while calling
`POST /api/v1/auth/refresh` with a valid current refresh token.

Expected result: refresh returns RFC 7807 `503 Authentication Temporarily
Unavailable` with `Retry-After: 3`; it does not return `401` or claim that the
session was revoked. The response and logs contain no database host, username,
session ID, token, or verifier. The `auth.sessions.store.failures` counter
increases. Restore the database before issuing or testing another session.

## 7. Swagger contract

1. In Swagger, inspect `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout`.
2. Confirm the refresh description requires `type=refresh` and explains
   single-use rotation, whole-session replay revocation, and retryable `503`.
3. Confirm logout requires an access bearer header, returns `204` on success,
   and documents `401` for invalid or missing input.

Expected result: no frontend request or response field migration is needed.
The frontend only needs to handle `401` by clearing credentials and showing the
existing login flow.
