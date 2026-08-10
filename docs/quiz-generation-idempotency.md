# Quiz Generation Idempotency

Quiz-generation starts are asynchronous and can reserve billing tokens. Clients should make every user-initiated start idempotent so reconnects, double taps, and app restarts cannot create a second job or affect another job's reservation.

OpenAPI contract: `GET /v3/api-docs/quizzes` and Swagger UI at `http://localhost:8080/swagger-ui.html` in local development. Production clients should discover the Quizzes group from `GET /api/v1/api-summary`.

## Endpoints

The optional `Idempotency-Key` request header is supported by:

- `POST /api/v1/quizzes/generate-from-document`
- `POST /api/v1/quizzes/generate-from-upload`
- `POST /api/v1/quizzes/generate-from-text`

The key is an opaque, trimmed value of 1-128 characters. It is scoped to the authenticated user and source operation type. A blank or oversized supplied value returns `400`.

During the additive transition, requests without the header remain supported for existing clients. They use a one-off legacy operation and therefore do not receive replay guarantees. New clients must send the header; a later minimum-client release can make it required after usage has been measured.

## Existing Frontend Compatibility

The current frontend requires no change to keep working. All three endpoint paths, request fields, `202 Accepted` status, and `QuizGenerationResponse` shape are unchanged. When the frontend omits `Idempotency-Key`, the backend creates a fresh one-off legacy operation for that request and follows the existing generation path.

This preserves the previous behaviour, including separate starts for separate clicks. It does not make repeated legacy requests replay-safe. A frontend change is only needed to opt into the stronger retry guarantee described below; it must be additive and must continue tolerating requests without a stored key during the migration window.

## Client Behaviour

Create one UUID when the user confirms a generation command, persist it with the pending local action, and send it unchanged on every retry until the command has reached a terminal outcome. Do not reuse the UUID for a new command, another user, or a different generation flow.

```http
POST /api/v1/quizzes/generate-from-document
Authorization: Bearer <access token>
Idempotency-Key: 7d9bf2f0-4e62-4d90-b3e2-2b33a4d3cd21
Content-Type: application/json
```

- An exact retry returns `202 Accepted` with the existing `QuizGenerationResponse` and job ID. A replay may report a terminal job status when the original job has already completed or failed.
- `409 Conflict` means the key was already used for a materially different command. Do not retry it. Ask the user to confirm the changed command, then create a new key.
- `503 Service Unavailable` with `Retry-After: 3` means the matching upload/text request is still being initialized or cannot yet be resumed safely. Retry later with the same key.
- `429 Too Many Requests` is the generation-start rate limit. Retry after the normal client backoff using the same key.
- `401`, `403`, and source `404` errors must not be retried until authentication, permission, or ownership is corrected.

Persist the key across iOS reconnects and app restarts. Remove it only after the client has stored the terminal result or the user explicitly abandons the command. Server retention is 30 days; after that window a retry is a new command and must use a new key.

## Material Command Fields

The server binds a key to a versioned canonical command. It normalizes unordered selections so equivalent map/set ordering remains a replay. Material changes include:

- source operation type and document ID for document generation;
- quiz scope, selected chunks, chapter/section inputs, title, and description;
- question-type/count matrix, difficulty, estimated time, category, tags, and language;
- upload/text document-processing strategy and maximum chunk size;
- actual upload bytes or exact UTF-8 text through a one-way SHA-256 source digest; and
- the canonicalization version.

For privacy, raw upload/text source content is never persisted in idempotency metadata or written to logs. The source digest contributes to the outer canonical command hash, so the operation row contains only a 64-character command hash rather than the raw source or its standalone digest. Different same-size upload bytes and different same-length text therefore return `409` before document processing, reservation, or job creation. Never reuse a key for newly selected content.

## Tariff Snapshot

Every new operation captures the complete current customer tariff version, base tokens, and per-thousand-character rate when the key is first claimed. The snapshot is stored separately from the client-command hash because pricing is server-owned operation context, not client input.

An exact retry continues with the stored tariff even if deployment configuration changes before document processing or estimation finishes. A new key captures the newly configured tariff. The generated job receives the same immutable snapshot, and the reservation is calculated from it; the server does not silently reprice an existing command.

Operations created before source-digest canonicalization remain compatible. A legacy operation that already has a job can still return that job after deployment. An ambiguous legacy operation that has not started cannot begin under changed, unprovable source content and must use a new key.

## Server Guarantees

An operation record links the authenticated user, canonical request fingerprint, immutable tariff snapshot, reservation, and generation job. Concurrent exact retries share one operation. Concurrent different commands using one key produce one operation and one `409`, without a reservation for the rejected command. A different key creates an independent operation even when the estimate is the same. A failed job start rolls back its reservation/job linkage, and cleanup never releases a reservation belonging to another operation.

Operation metadata expires after 30 days and is purged by a scheduled cleanup. It contains no source content and no full key is written to logs.
