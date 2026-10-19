# Shared authentication admission

Authentication admission uses MySQL/InnoDB through `AuthThrottleService`. The database owns one shared state across replicas and restarts. Limits are versioned profile A defaults, not an external security standard or a complete defence against distributed denial of service.

## Request behaviour

Every applicable allowance must permit a request. New login, signup, and refresh allowances count both successful and failed admitted requests in the preceding rolling window. Success, another replica, process restart, and token rotation do not reset them. All applicable dimensions are charged atomically before password hashing, authentication side effects, or refresh-token rotation. Refresh identity dimensions come only from verified claims; JWT signature/password-version reads do not consume or rotate a token.

Login username and email aliases resolve to the same account bucket. Unknown identifiers have the same public failure messages and admission-query shape. Independent account limits constrain attempts that change networks; account-and-network limits also protect individual connections. Shared-network limits intentionally trade availability under abuse for protection of the backend.

A rejected request has no account, session, verification-email, password, or token-rotation effect. Rejections do not extend an exhausted window or persist new attacker-selected bucket identities. A throttled fresh refresh token remains unconsumed; existing single-use/replay rules still apply to requests that reach session rotation.

Registration checks normalized claimed email and requested username. First provider-created accounts use provider plus opaque subject instead of requested username and share email/network budgets. Existing provider logins and account linking do not become new registrations. OAuth signup admission failures return a bounded `application/problem+json` 429/503 without provider details; other OAuth failures keep their existing redirect behaviour.

## Profile A defaults and configuration

All counts and window lengths can be changed without source edits. Local Spring configuration uses `app.auth.rate-limit.limits.<dimension>.requests` and `.window-seconds`. The environment settings below flow through production Compose and the deployment workflow. Set the same-named **GitHub repository variables** for deployment overrides; absent variables retain the defaults.

| Dimension | Requests | Window seconds | Environment prefix |
|---|---:|---:|---|
| login-account | 10 | 60 | AUTH_LIMIT_LOGIN_ACCOUNT |
| login-account-network | 5 | 60 | AUTH_LIMIT_LOGIN_ACCOUNT_NETWORK |
| login-network | 60 | 60 | AUTH_LIMIT_LOGIN_NETWORK |
| registration-email | 3 | 3600 | AUTH_LIMIT_REGISTRATION_EMAIL |
| registration-identity | 3 | 3600 | AUTH_LIMIT_REGISTRATION_IDENTITY |
| registration-network-burst | 30 | 60 | AUTH_LIMIT_REGISTRATION_NETWORK_BURST |
| registration-network-sustained | 60 | 3600 | AUTH_LIMIT_REGISTRATION_NETWORK_SUSTAINED |
| refresh-session | 10 | 60 | AUTH_LIMIT_REFRESH_SESSION |
| refresh-user | 30 | 60 | AUTH_LIMIT_REFRESH_USER |
| refresh-network | 300 | 60 | AUTH_LIMIT_REFRESH_NETWORK |
| change-password | 3 | 60 | AUTH_LIMIT_CHANGE_PASSWORD |
| forgot-password | 5 | 60 | AUTH_LIMIT_FORGOT_PASSWORD |
| reset-password | 5 | 60 | AUTH_LIMIT_RESET_PASSWORD |
| verify-email | 10 | 60 | AUTH_LIMIT_VERIFY_EMAIL |
| resend-verification | 5 | 60 | AUTH_LIMIT_RESEND_VERIFICATION |
| oauth-code-exchange-ip | 30 | 60 | AUTH_LIMIT_OAUTH_CODE_EXCHANGE_IP |
| oauth-code-exchange-code | 5 | 60 | AUTH_LIMIT_OAUTH_CODE_EXCHANGE_CODE |

Append `_REQUESTS` or `_WINDOW_SECONDS` to a prefix. For example, `AUTH_LIMIT_LOGIN_NETWORK_REQUESTS=90` changes the shared login-network count; `AUTH_LIMIT_LOGIN_NETWORK_WINDOW_SECONDS=120` changes its duration. Changes take effect after the normal reviewed deployment/restart, not through a public runtime API. Configuration rejects zero/negative values, unsupported dimensions, more than 10,000 requests per dimension, or windows longer than one day. It does not allow switching a policy between fixed and rolling semantics.

The seven existing recovery/exchange dimensions retain their first-admitted-request fixed window. Their keys remain user+IP for password change, email+IP for forgot/resend, IP for reset/verify and exchange network admission, and a code digest for exchange identity admission. Logout/revocation and ordinary bearer-authenticated requests do not newly consult the limiter. Permissions, ownership, token lifetimes, and refresh replay protection are unchanged.

For example, 25 different students signing in through one school router fit the 60/minute login-network allowance. This does not promise that 25 verification links fit the separate existing 10/IP/minute verification limit.

## Changing configuration safely

The backend/database owner controls tuning. Choose values from measured workload and MySQL capacity, coordinate identical settings across replicas, and apply them through the existing deployment process. There is no automatic tuning, permissive local fallback, or administrator-facing configuration endpoint.

Changing counts reuses existing bucket identity and history. Decreasing a count may immediately throttle requests until enough previous admissions expire. Increasing a window cannot reconstruct already-expired/deleted history: plan the transition and observe at least the new window before relying on its complete historical protection. Do not mix different settings indefinitely during a rolling rollout. Do not delete limiter rows to make a test or deployment appear healthy.

Storage settings `app.auth.rate-limit.transaction-timeout-seconds` (default 3) and `cleanup-batch-size` (default 100) bound individual database transactions and expiry deletion batches. Connection acquisition also follows the datasource's existing connection timeout; a transaction timeout is not an end-to-end HTTP deadline. Leave sufficient pool capacity for independent admission transactions, including provider signup inside the existing OAuth transaction.

## Privacy, expiry, and proxy boundaries

Migration V77 adds only `auth_rate_limits`: a domain-separated HMAC key, fixed dimension name, bounded admitted timestamps, fixed-window start, and indexed expiry. No raw account/email/IP, credential, source content, request payload, or device trail is stored. Bounded admission timestamp arrays contain at most the supported per-dimension count. An injected database-backed `Clock` makes counters independent of application-node clock skew.

The optional `AUTH_RATE_LIMIT_KEY_SECRET` uses the existing secret-delivery path. When absent, a domain-separated key derives from the existing JWT secret, preserving deployment compatibility without a required new secret. Supply an independent secret through the normal secret store when separate rotation is required. Never put its value in variables, source, logs, tickets, or chat. Coordinate rotation across every replica: changing the key changes bucket identity, and JWT-secret rotation also invalidates existing JWTs.

Expired records become eligible for indexed deletion after their relevant window. Each admitted request deletes at most the configured batch in a separate transaction; expired records can remain while there is no traffic or cleanup is backlogged. Cleanup failure is logged in bounded form and cannot undo an already committed admission. Retain the additive table on application rollback for a forward fix; an old binary ignores it and does not provide shared enforcement.

`app.security.enable-forwarded-headers` and `app.security.trusted-proxies` remain the trust configuration. Use exact proxy IP literals or explicit CIDRs, never textual address prefixes. The utility walks `X-Forwarded-For` from the socket peer through trusted hops, selecting the first untrusted address. It ignores forwarding headers from an untrusted peer, bounds chain size, validates numeric addresses without DNS, and canonicalizes IPv6. Confirm deployed ingress/Tomcat address rewriting matches these configured boundaries before rollout; an incorrect proxy configuration can combine unrelated users into one network budget. IPv6 address changes do not reset an independent account/session allowance; address-only limits do not eliminate all distributed abuse.

## Failures, clients, and operations

Exhaustion returns RFC 7807 `429`, `Cache-Control: no-store`, and `Retry-After` rounded up until every limiting dimension permits another request. Known and unknown accounts receive the same generic admission error. Limiter/canonical-identity storage failure returns generic `503` with no local permissive fallback. A 503 does not promise a retry time.

Authentication validation errors retain field names and messages but redact submitted values. Malformed-request errors return a constant parser diagnostic; passwords and tokens are never repeated in those errors. These responses also use `Cache-Control: no-store`.

Web/iOS clients keep the existing success DTOs, honour Retry-After, and avoid automatic credential replay. The shipped CORS configurations expose Retry-After to allowed browser origins without changing the origin allowlist. OAuth callback admission errors also retain no-referrer protection for provider codes in callback URLs. Authentication requires connectivity; do not queue passwords or refresh operations while offline. A 429 means a refresh token was not consumed. An uncertain refresh outcome remains subject to the existing session recovery contract rather than an automatic retry.

The fixed-cardinality `auth.throttle.requests` counter has only `operation` and `outcome=allowed|throttled|store_error` labels. The backend/database owner investigates sustained store errors, pool/lock pressure, expiry-cleanup warnings, or unexpected throttling. Use existing monitoring to compare these signals with database availability and request volume. Never add account, network, token, code, or resource IDs as metric labels, and do not disable enforcement as an outage workaround.

The authentication OpenAPI group documents 429/503 responses and remains discoverable through `/api/v1/api-summary`. Automated verification uses synthetic identities, provider fakes, MySQL contention/restart/rollback/expiry fixtures, and multi-parent fetch-plan assertions. No live provider is needed.
