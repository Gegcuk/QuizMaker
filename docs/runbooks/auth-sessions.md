# Authentication Sessions

## Security contract

Every JWT issued after migration `V66__create_auth_sessions.sql` has:

- `type=access` or `type=refresh`.
- `uid`, the signed user identifier.
- `sid`, the opaque server-side session identifier.

Only `type=access` tokens authenticate protected routes. Only
`type=refresh` tokens can call the refresh operation. A valid signature alone
is insufficient: the session must exist, belong to the signed user, be
unrevoked, and be unexpired.

The database stores a fixed-length HMAC fingerprint of the current refresh JWT,
never a raw access or refresh token. The fingerprint changes on every successful
refresh. Reusing an older refresh token revokes the session so concurrent or
uncertain retries fail closed.

## Client lifecycle

- Login and OAuth success return the existing token-response fields.
- Store access and refresh tokens separately.
- Refresh replaces both values atomically in client storage.
- If a refresh request has an unknown outcome, clear local credentials and
  require login. Retrying an old refresh token can revoke the session.
- Logout clears both local credentials immediately. A single logout retry is
  safe because the server treats an already-revoked session as successful.
- A protected-route `401` requires reauthentication; clients must not keep
  retrying the same credentials offline.

## Lifetime and cleanup

The current configuration is a 12-hour access token and a 7-day absolute
refresh-session lifetime. Refresh rotation does not extend the seven-day
deadline. The hourly session cleanup task removes rows after that deadline.

Do not manually modify `auth_sessions`, token claims, or the Flyway history.
For a suspected token incident, rotate the JWT signing secret through the normal
secret-management/deployment process. That invalidates all signed tokens and
requires users to log in again.

## Deployment and recovery

`V66` is additive and creates `auth_sessions` with a cascading user foreign key
and expiry indexes. It does not alter user rows or JWT response fields. Tokens
issued before the migration lack `sid` and deliberately fail closed; this is the
one approved forced-reauthentication event.

There is no destructive rollback. If an application rollback is necessary after
the migration, keep the table in place and deploy a forward fix. If the session
store cannot be read, bearer authentication fails closed and protected routes
return `401`; investigate database availability before retrying requests.

Operational logs must never include a JWT, refresh verifier, or session ID.
The application emits only bounded events for session revocation, replay
rejection, validation failures, and expired-session cleanup.
