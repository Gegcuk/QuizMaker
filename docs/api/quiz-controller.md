# Quiz Controller Integration Guide

This document summarizes every public HTTP endpoint exposed by `QuizController` along with the DTO contracts, required permissions, rate limits, and possible error responses. It is intended to help frontend engineers integrate against the quiz APIs confidently.

## Overview

`QuizController` is mounted at the base path `/api/v1/quizzes`. All responses are JSON unless noted otherwise and errors follow the shared `ProblemDetail` structure produced by `GlobalExceptionHandler`.

Authentication is provided by the platform's bearer token. Additional authorization checks are expressed through the custom `@RequirePermission` annotation and Spring Security's `@PreAuthorize` rules inside the controller.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L71-L141】【F:src/main/java/uk/gegc/quizmaker/shared/api/advice/GlobalExceptionHandler.java†L1-L200】

## Permission Matrix

| Capability | Endpoint(s) | Required Permission | Notes |
| --- | --- | --- | --- |
| Create quizzes | `POST /api/v1/quizzes` | `QUIZ_CREATE` | Non moderators are limited to `PRIVATE`/`DRAFT` visibility by the service layer.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L96-L110】 |
| Update quizzes | `PATCH /api/v1/quizzes/{id}`<br>`PATCH /api/v1/quizzes/bulk-update`<br>Question/tag/category association endpoints | `QUIZ_UPDATE` | Ownership or moderator checks enforced by the service layer.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L142-L222】 |
| Delete quizzes | `DELETE /api/v1/quizzes/{id}`<br>`DELETE /api/v1/quizzes?ids=` | `QUIZ_DELETE` | Applies to both single and batch deletes.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L200-L221】 |
| Visibility changes | `PATCH /api/v1/quizzes/{id}/visibility` | `QUIZ_UPDATE` **or** `QUIZ_MODERATE` **or** `QUIZ_ADMIN` | Owner may only toggle PRIVATE; moderators/admins can make PUBLIC.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L285-L322】 |
| Status changes | `PATCH /api/v1/quizzes/{id}/status` | `QUIZ_UPDATE` **or** `QUIZ_MODERATE` **or** `QUIZ_ADMIN` | Service guards illegal transitions (e.g., owner publishing PUBLIC).【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L324-L356】 |
| Moderation | `POST /api/v1/quizzes/{id}/submit-for-review` | `QUIZ_UPDATE` | Requires authenticated quiz owner; service transitions to `PENDING_REVIEW`.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L402-L411】 |
| Generation (AI) | `POST /generate-from-document`<br>`POST /generate-from-upload`<br>`POST /generate-from-text`<br>`DELETE /generation-status/{jobId}` | `QUIZ_CREATE` | Additional per-minute rate limits enforced server-side.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L413-L546】【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L747-L788】 |
| Generation job admin | `POST /generation-jobs/cleanup-stale`<br>`POST /generation-jobs/{jobId}/force-cancel` | `QUIZ_ADMIN` | Intended for ops tooling.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L828-L874】 |
| Attempt analytics | `GET /{quizId}/attempts*` | Authenticated | Additional ownership check inside service layer.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L248-L283】 |

## Rate Limits

The controller enforces request-per-minute quotas using `RateLimitService`. Exceeding these limits raises `RateLimitExceededException` → HTTP 429 with a `Retry-After` hint.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L120-L137】【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L467-L479】【F:src/main/java/uk/gegc/quizmaker/shared/rate_limit/RateLimitService.java†L11-L40】

| Operation | Limit | Key | Notes |
| --- | --- | --- | --- |
| List (authenticated) | 120/min | Client IP | Applies to `GET /api/v1/quizzes` and honors `If-None-Match` caching.|【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L112-L139】 |
| List (public) | 120/min | Client IP | `GET /api/v1/quizzes/public` also emits weak ETags.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L358-L398】 |
| Generation start | 3/min | Auth username | Applies to `/generate-from-document`, `/generate-from-upload`, `/generate-from-text`.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L458-L502】 |
| Generation cancel | 5/min | Auth username | `DELETE /generation-status/{jobId}`.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L762-L788】 |

## DTO Catalogue

### Core Quiz DTOs

| DTO | Type | Purpose | Key Fields |
| --- | --- | --- | --- |
| `QuizDto` | Response | Canonical quiz representation returned by GET/UPDATE endpoints. | `id`, `title`, `description`, `visibility` (`PUBLIC`/`PRIVATE`), `difficulty` (`EASY`/`MEDIUM`/`HARD`), `status` (`DRAFT`, `PUBLISHED`, etc.), timing options, `tagIds`, timestamps.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/QuizDto.java†L1-L49】【F:src/main/java/uk/gegc/quizmaker/features/quiz/domain/model/Visibility.java†L1-L5】【F:src/main/java/uk/gegc/quizmaker/features/question/domain/model/Difficulty.java†L1-L5】【F:src/main/java/uk/gegc/quizmaker/features/quiz/domain/model/QuizStatus.java†L1-L8】 |
| `CreateQuizRequest` | Request | Payload for new quiz creation. | Validates title (3–100 chars), optional description, defaults visibility=`PRIVATE`, difficulty=`MEDIUM`, empty tags, includes timer fields and category reference.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/CreateQuizRequest.java†L1-L49】 |
| `UpdateQuizRequest` | Request | Partial update body for single/bulk edits. | All fields optional; validation mirrors creation constraints; booleans are boxed so omitted values stay unchanged.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/UpdateQuizRequest.java†L1-L60】 |
| `BulkQuizUpdateRequest` | Request | Update multiple quizzes in one call. | Requires `quizIds` (non-empty list) + embedded `UpdateQuizRequest` applied to each.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/BulkQuizUpdateRequest.java†L1-L22】 |
| `BulkQuizUpdateOperationResultDto` | Response | Summarizes successes/failures per quiz ID after bulk update. | `successfulIds`, `failures` map (UUID → reason).【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/BulkQuizUpdateOperationResultDto.java†L1-L16】 |
| `VisibilityUpdateRequest` | Request | Toggle for public/private visibility. | Mandatory boolean `isPublic` flag.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/VisibilityUpdateRequest.java†L1-L14】 |
| `QuizStatusUpdateRequest` | Request | Change lifecycle status. | Mandatory `status` (enum `QuizStatus`).【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/QuizStatusUpdateRequest.java†L1-L12】 |
| `QuizSearchCriteria` | Request (query model) | Optional filters for list endpoints. | Category/tag names (arrays), `authorName`, `search` term, `difficulty`. Values read from query string parameters.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/QuizSearchCriteria.java†L1-L35】 |

### Generation DTOs

| DTO | Type | Purpose | Highlights |
| --- | --- | --- | --- |
| `GenerateQuizFromDocumentRequest` | Request | Start AI job using an already processed document. | Requires `documentId`, `questionsPerType` (map of `QuestionType` → count 1–10), `difficulty`, optional scope hints (chunk indices, chapter/section data), optional metadata overrides, default scope=`ENTIRE_DOCUMENT`, default language=`en`. Validation enforces scope-specific requirements.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/GenerateQuizFromDocumentRequest.java†L1-L115】【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/QuizScope.java†L1-L15】【F:src/main/java/uk/gegc/quizmaker/features/question/domain/model/QuestionType.java†L1-L5】 |
| `GenerateQuizFromUploadRequest` | Request | Upload + generate convenience flow (multipart). | Accepts document chunking preferences (`chunkingStrategy`, `maxChunkSize`), scope options, quiz metadata, same `questionsPerType` rules; defaults ensure strategy `CHAPTER_BASED`, `maxChunkSize` 250 000, language `en`. Includes converters to downstream requests.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/GenerateQuizFromUploadRequest.java†L1-L138】【F:src/main/java/uk/gegc/quizmaker/features/document/api/dto/ProcessDocumentRequest.java†L1-L120】 |
| `GenerateQuizFromTextRequest` | Request | Generate from raw text (server chunks text first). | Requires non-empty `text` ≤ 300 000 chars, optional language and chunking overrides, same scope + question map semantics as other generation flows.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/GenerateQuizFromTextRequest.java†L1-L133】 |
| `QuizGenerationResponse` | Response | Returned immediately after job start. | `jobId`, `status` (initially `PROCESSING`), friendly `message`, optional `estimatedTimeSeconds`; helper factory methods `started`/`failed` exist for service use.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/QuizGenerationResponse.java†L1-L36】 |
| `QuizGenerationStatus` | Response | Polling model for async job state. | Contains progress counters, timing estimates, generated quiz ID, error text, plus billing metadata when available. Derived from `QuizGenerationJob` entity; includes helpers `isTerminal`, `isCompleted`, `isFailed`, `isActive`.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/QuizGenerationStatus.java†L1-L126】 |

### Analytics DTOs

| DTO | Type | Purpose | Highlights |
| --- | --- | --- | --- |
| `QuizResultSummaryDto` | Response | Aggregated stats for a quiz. | `attemptsCount`, `averageScore`, best/worst scores, `passRate`, per-question stats (`QuestionStatsDto`).【F:src/main/java/uk/gegc/quizmaker/features/result/api/dto/QuizResultSummaryDto.java†L1-L13】【F:src/main/java/uk/gegc/quizmaker/features/result/api/dto/QuestionStatsDto.java†L1-L10】 |
| `LeaderboardEntryDto` | Response | Leaderboard row. | `userId`, `username`, `bestScore`. Returned as list sorted descending by score.【F:src/main/java/uk/gegc/quizmaker/features/result/api/dto/LeaderboardEntryDto.java†L1-L13】 |
| `AttemptDto` | Response | Attempt summary for owner view. | Contains IDs, `startedAt`, `status`, `mode` (enum `AttemptMode`: `ONE_BY_ONE`, `ALL_AT_ONCE`, `TIMED`).【F:src/main/java/uk/gegc/quizmaker/features/attempt/api/dto/AttemptDto.java†L1-L22】【F:src/main/java/uk/gegc/quizmaker/features/attempt/domain/model/AttemptStatus.java†L1-L6】【F:src/main/java/uk/gegc/quizmaker/features/attempt/domain/model/AttemptMode.java†L1-L6】 |
| `AttemptStatsDto` | Response | Fine-grained attempt analytics. | Durations, counts, accuracy, nested `QuestionTimingStatsDto` entries capturing per-question timing, correctness, difficulty.【F:src/main/java/uk/gegc/quizmaker/features/attempt/api/dto/AttemptStatsDto.java†L1-L24】【F:src/main/java/uk/gegc/quizmaker/features/attempt/api/dto/QuestionTimingStatsDto.java†L1-L27】 |

## Endpoint Reference

### CRUD & Listing

1. **Create quiz** – `POST /api/v1/quizzes`
   * Body: `CreateQuizRequest`
   * Success: `201 Created` with `{ "quizId": UUID }`
   * Errors: validation failures (`400`), missing auth (`401`), lacking permission (`403`).【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L96-L110】

2. **List quizzes** – `GET /api/v1/quizzes`
   * Query: pagination (`page`, `size`, `sort`), filters from `QuizSearchCriteria`, `scope` (`public` default, `me`, `all`).
   * Success: `200 OK` with `Page<QuizDto>` (weak ETag header). `304 Not Modified` when `If-None-Match` matches.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L112-L139】
   * Errors: `429` on rate limit, `401/403` via security.

3. **Get quiz** – `GET /api/v1/quizzes/{quizId}`
   * Success: `200 OK` `QuizDto`.
   * Errors: `404` if quiz missing or not accessible.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L141-L151】

4. **Update quiz** – `PATCH /api/v1/quizzes/{quizId}`
   * Body: `UpdateQuizRequest`
   * Success: `200 OK` `QuizDto`.
   * Errors: `400` validation, `404` missing quiz, `403` unauthorized.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L153-L168】

5. **Bulk update** – `PATCH /api/v1/quizzes/bulk-update`
   * Body: `BulkQuizUpdateRequest`
   * Success: `200 OK` `BulkQuizUpdateOperationResultDto` (mix of successes/failures).
   * Errors: `400`, `403`. Individual failures are reported per-ID inside payload.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L170-L188】

6. **Delete quiz** – `DELETE /api/v1/quizzes/{quizId}`
   * Success: `204 No Content`.
   * Errors: `404`, `403`, `401`.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L190-L200】

7. **Bulk delete** – `DELETE /api/v1/quizzes?ids=`
   * Query: `ids` list (repeat parameter supported).
   * Success: `204 No Content`.
   * Errors: `400` invalid IDs, `404`, `403`.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L202-L221】

8. **Add/remove question** – `POST` / `DELETE /api/v1/quizzes/{quizId}/questions/{questionId}`
   * Success: `204 No Content`.
   * Errors: `404` quiz/question missing, `403` ownership, `409` on duplicates (service-level).
   * Notes: Service ensures referential integrity.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L223-L252】

9. **Add/remove tag** – `POST` / `DELETE /api/v1/quizzes/{quizId}/tags/{tagId}` (same semantics as question association).【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L254-L288】

10. **Change category** – `PATCH /api/v1/quizzes/{quizId}/category/{categoryId}`
    * Success: `204 No Content`.
    * Errors: `404` quiz/category missing, `403` unauthorized.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L290-L305】

11. **Public listing** – `GET /api/v1/quizzes/public`
    * No auth required.
    * Rate limited 120/min per IP; returns paged `QuizDto` with weak ETags.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L358-L398】

### Analytics & Attempts

12. **Quiz results summary** – `GET /api/v1/quizzes/{quizId}/results`
    * Success: `200 OK` `QuizResultSummaryDto`.
    * Errors: `404` if quiz or attempts missing, `403` unauthorized.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L307-L319】

13. **Leaderboard** – `GET /api/v1/quizzes/{quizId}/leaderboard?top=10`
    * Success: `200 OK` List of `LeaderboardEntryDto`.
    * Errors: `404`, `403` as appropriate.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L321-L334】

14. **Owner attempts list** – `GET /api/v1/quizzes/{quizId}/attempts`
    * Requires authentication (owner only).
    * Success: `200 OK` List of `AttemptDto`.
    * Errors: `403` if not owner, `404` on missing quiz.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L336-L349】

15. **Attempt stats** – `GET /api/v1/quizzes/{quizId}/attempts/{attemptId}/stats`
    * Success: `200 OK` `AttemptStatsDto`.
    * Errors: `404` (attempt not linked to quiz), `403` (not owner).【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L351-L357】

### Visibility & Status

16. **Toggle visibility** – `PATCH /api/v1/quizzes/{quizId}/visibility`
    * Body: `VisibilityUpdateRequest`
    * Success: `200 OK` `QuizDto`.
    * Errors: `400` invalid transition, `403`, `404`.
    * Notes: Setting `isPublic=true` demands moderator/admin role; owners can only go PRIVATE.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L285-L322】

17. **Change status** – `PATCH /api/v1/quizzes/{quizId}/status`
    * Body: `QuizStatusUpdateRequest`
    * Success: `200 OK` `QuizDto`.
    * Errors: `400` illegal transition (e.g., publish without prerequisites), `403`, `404`.
    * Notes: Publishing to PUBLIC requires moderator/admin privilege.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L324-L356】

18. **Submit for review** – `POST /api/v1/quizzes/{quizId}/submit-for-review`
    * Success: `204 No Content` (implemented via `ResponseEntity.noContent`).
    * Errors: `403` if not owner, `404` missing quiz.
    * Notes: Resolves authenticated user ID even if principal is username/email.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L400-L411】【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L914-L930】

### AI Generation Lifecycle

19. **Generate from document** – `POST /api/v1/quizzes/generate-from-document`
    * Body: `GenerateQuizFromDocumentRequest`
    * Success: `202 Accepted` `QuizGenerationResponse`.
    * Errors: `400` invalid input/validation; `404` document not found; `409` job already active; `401/403` security; `429` rate limit.
    * Notes: Uses same rate-limit key as other start endpoints; job ID used for polling.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L413-L469】

20. **Generate from upload** – `POST /api/v1/quizzes/generate-from-upload` (multipart)
    * Parts/fields: `file` (binary), optional chunking params, metadata, `questionsPerType` (JSON string), `difficulty`, etc. Controller validates upload via `DocumentValidationService` then converts into DTOs.
    * Success: `202 Accepted` `QuizGenerationResponse`.
    * Errors: `400` invalid file (empty, huge, bad chunk strategy) or JSON parse issues; `415` unsupported file type (raised as `UnsupportedFileTypeException` → ProblemDetail 400), `422` downstream document processing failure, `429` rate limit.
    * Notes: On unexpected error the controller wraps and rethrows `RuntimeException`, resulting in `500`.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L471-L537】【F:src/main/java/uk/gegc/quizmaker/features/document/application/impl/DocumentValidationServiceImpl.java†L15-L74】

21. **Generate from text** – `POST /api/v1/quizzes/generate-from-text`
    * Body: `GenerateQuizFromTextRequest`
    * Success: `202 Accepted` `QuizGenerationResponse`.
    * Errors: `400` invalid input, `409` existing active job, `422` text processing failure, `429` rate limit, auth failures.
    * Notes: Shares same validation defaults as other generation DTOs.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L539-L574】

22. **Poll generation status** – `GET /api/v1/quizzes/generation-status/{jobId}`
    * Success: `200 OK` `QuizGenerationStatus`.
    * Errors: `404` unknown job, `403` not owner, `401` unauthenticated.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L576-L610】

23. **Fetch generated quiz** – `GET /api/v1/quizzes/generated-quiz/{jobId}`
    * Success: `200 OK` `QuizDto` (only after completion).
    * Errors: `404` missing job/quiz, `409` if job incomplete, `403` unauthorized.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L612-L644】

24. **Cancel generation** – `DELETE /api/v1/quizzes/generation-status/{jobId}`
    * Success: `200 OK` `QuizGenerationStatus` (post-cancel state).
    * Errors: `400` canceling non-cancellable job, `404`, `403`, `429` on rate limit.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L646-L788】

25. **List generation jobs** – `GET /api/v1/quizzes/generation-jobs`
    * Query: pageable, default sort by `startedAt` desc.
    * Success: `200 OK` `Page<QuizGenerationStatus>`.
    * Errors: `401` if unauthenticated.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L790-L811】

26. **Generation stats** – `GET /api/v1/quizzes/generation-jobs/statistics`
    * Success: `200 OK` `QuizGenerationJobService.JobStatistics` (fields defined in service layer).
    * Errors: `401` if unauthenticated.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L813-L826】

27. **Cleanup stale jobs** – `POST /api/v1/quizzes/generation-jobs/cleanup-stale`
    * Success: `200 OK` plain text confirmation.
    * Errors: `401/403` if not admin.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L828-L844】

28. **Force cancel job** – `POST /api/v1/quizzes/generation-jobs/{jobId}/force-cancel`
    * Success: `200 OK` plain text message.
    * Errors: `404` job not found (`ResourceNotFoundException`), `500` on unexpected errors (controller catches and responds with message), `401/403` without admin permission.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L846-L892】【F:src/main/java/uk/gegc/quizmaker/shared/exception/ResourceNotFoundException.java†L1-L6】

### Supporting Behaviors

* **ETag caching:** List endpoints compute weak ETags based on page metadata and honor `If-None-Match`, allowing frontend caching strategies.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L120-L139】【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L360-L394】
* **User resolution:** When submitting for review, the controller resolves the authenticated user's UUID even if the principal string is a username/email by querying `UserRepository`. Missing users trigger `UnauthorizedException` → HTTP 401.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L400-L411】【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L914-L930】【F:src/main/java/uk/gegc/quizmaker/shared/exception/UnauthorizedException.java†L1-L6】
* **File validation:** Upload endpoint delegates to `DocumentValidationService` which enforces size/type checks and throws `IllegalArgumentException` or `UnsupportedFileTypeException`. Treat both as client errors that should be surfaced to users.【F:src/main/java/uk/gegc/quizmaker/features/document/application/impl/DocumentValidationServiceImpl.java†L15-L74】【F:src/main/java/uk/gegc/quizmaker/shared/exception/UnsupportedFileTypeException.java†L1-L22】
* **JSON parsing:** `questionsPerType` form field is parsed via `ObjectMapper.readValue`. Invalid JSON raises `IllegalArgumentException` with descriptive message. Frontend should send stringified JSON map (e.g., `{"MCQ_SINGLE":3}`) in multipart requests.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L498-L528】

## Error Reference

| Exception | Typical HTTP Status | Trigger |
| --- | --- | --- |
| `RateLimitExceededException` | 429 Too Many Requests | Breaching per-minute rate limits on search or generation operations.【F:src/main/java/uk/gegc/quizmaker/shared/exception/RateLimitExceededException.java†L1-L20】 |
| `IllegalArgumentException` | 400 Bad Request | Validation failures inside controller/DTO constructors (e.g., malformed scope, missing chunk indices, invalid UUID format, bad file metadata).【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/GenerateQuizFromDocumentRequest.java†L49-L111】【F:src/main/java/uk/gegc/quizmaker/features/document/application/impl/DocumentValidationServiceImpl.java†L25-L64】 |
| `UnsupportedFileTypeException` | 400 Bad Request | Uploading unsupported MIME types/extensions during quiz generation from upload.【F:src/main/java/uk/gegc/quizmaker/shared/exception/UnsupportedFileTypeException.java†L1-L22】 |
| `ResourceNotFoundException` | 404 Not Found | Force-cancel admin endpoint when the job ID is unknown; also surfaced by service methods when quizzes/jobs/documents are missing.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L846-L874】 |
| `UnauthorizedException` | 401 Unauthorized | When the authenticated principal cannot be resolved to a `User` entity during submit-for-review flow.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L914-L930】 |
| Generic `RuntimeException` | 500 Internal Server Error | Unexpected failures during multipart generation (caught and rethrown with contextual message) or admin force-cancel catch-all block (returns 500 with text body).【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L520-L535】【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java†L864-L892】 |

## Enumerations

For convenience, the enums referenced by the DTOs are summarised below.

| Enum | Values |
| --- | --- |
| `Difficulty` | `EASY`, `MEDIUM`, `HARD`.【F:src/main/java/uk/gegc/quizmaker/features/question/domain/model/Difficulty.java†L1-L5】 |
| `Visibility` | `PUBLIC`, `PRIVATE`.【F:src/main/java/uk/gegc/quizmaker/features/quiz/domain/model/Visibility.java†L1-L5】 |
| `QuizStatus` | `PENDING_REVIEW`, `REJECTED`, `PUBLISHED`, `DRAFT`, `ARCHIVED`.【F:src/main/java/uk/gegc/quizmaker/features/quiz/domain/model/QuizStatus.java†L1-L8】 |
| `GenerationStatus` | `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `CANCELLED` (with helper flags).【F:src/main/java/uk/gegc/quizmaker/features/quiz/domain/model/GenerationStatus.java†L1-L52】 |
| `QuizScope` | `ENTIRE_DOCUMENT`, `SPECIFIC_CHUNKS`, `SPECIFIC_CHAPTER`, `SPECIFIC_SECTION`.【F:src/main/java/uk/gegc/quizmaker/features/quiz/api/dto/QuizScope.java†L1-L15】 |
| `QuestionType` | `MCQ_SINGLE`, `MCQ_MULTI`, `OPEN`, `FILL_GAP`, `COMPLIANCE`, `TRUE_FALSE`, `ORDERING`, `HOTSPOT`, `MATCHING`.【F:src/main/java/uk/gegc/quizmaker/features/question/domain/model/QuestionType.java†L1-L5】 |
| `AttemptStatus` | `IN_PROGRESS`, `COMPLETED`, `ABANDONED`, `PAUSED`.【F:src/main/java/uk/gegc/quizmaker/features/attempt/domain/model/AttemptStatus.java†L1-L6】 |
| `AttemptMode` | `ONE_BY_ONE`, `ALL_AT_ONCE`, `TIMED`.【F:src/main/java/uk/gegc/quizmaker/features/attempt/domain/model/AttemptMode.java†L1-L6】 |

## Implementation Notes for Frontend

* Always pass an `Authorization` header except for `/public` listing.
* Respect `Retry-After` headers when receiving 429 responses—back off before retrying.
* For multipart upload, encode the `questionsPerType` map as a JSON string (e.g., `{"MCQ_SINGLE":3,"TRUE_FALSE":2}`) and send lists such as `chunkIndices`/`tagIds` as repeated form fields or JSON arrays, depending on your HTTP client.
* Use the returned job ID from generation endpoints to poll `/generation-status/{jobId}` until `status` becomes `COMPLETED` or terminal. Afterwards call `/generated-quiz/{jobId}` to fetch the assembled quiz.
* Cache paginated list responses using the weak ETag to avoid unnecessary data transfers when the listing is unchanged.
* Handle `ProblemDetail` bodies generically—fields include `type`, `title`, `status`, `detail`, and `instance`.