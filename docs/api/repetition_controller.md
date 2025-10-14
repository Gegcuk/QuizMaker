# Repetition Controller API Reference

Complete frontend integration guide for `/api/v1/repetition` REST endpoints. This document is self-contained and lists all DTOs,
validation rules, requirements, and error semantics needed to integrate the spaced repetition workflow without referencing
backend code.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Functional Requirements](#functional-requirements)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Enumerations](#enumerations)
- [Endpoints](#endpoints)
- [Error Handling](#error-handling)
- [Integration Guide](#integration-guide)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/v1/repetition`
* **Authentication**: Required for all endpoints (JWT bearer token in `Authorization` header)
* **Authorization Model**: Ownership-based. Users may only see and mutate their own spaced repetition entries unless they have
  admin override permissions.
* **Content-Type**: `application/json` for requests and responses (except `204` responses)
* **Idempotency**: Review submissions are idempotent per `(entryId, reviewTimestamp)` pair. Resubmitting the same payload
  overwrites the previous review for that timestamp.
* **Rate Limits**:
  * Queue/list endpoints: 60 requests per minute per user
  * Review submissions: 180 requests per minute per user (throttled to prevent accidental loops)
  * Admin search endpoints: 30 requests per minute per user
  Exceeding limits returns HTTP `429 Too Many Requests` with `Retry-After` header.
* **Clock**: All timestamps returned in ISO-8601 UTC (`Instant`). Clients should convert to local time zones when rendering.
* **Background Scheduling**: Entries are created automatically after an attempt completes when the quiz has
  `isRepetitionEnabled = true`. Manual creation endpoints exist for bootstrapping.

---

## Authorization Matrix

| Capability | Endpoint(s) | Authorization Rule | Notes |
| --- | --- | --- | --- |
| View due queue | `GET /queue`, `GET /entries/{entryId}` | Authenticated user owns the entry | Filters by authenticated principal |
| Review card | `POST /entries/{entryId}/reviews`, `POST /entries/reviews/batch` | Entry owner only | Admins may review on behalf of users using impersonation |
| Snooze / reschedule | `PATCH /entries/{entryId}/snooze` | Entry owner only | Quiz must still allow repetition |
| Manually create entry | `POST /entries` | Quiz must allow repetition & user must have read access to question | Admins bypass quiz flag |
| Delete entry | `DELETE /entries/{entryId}` | Entry owner or admin | Cannot delete locked entries currently in review |
| Summary stats | `GET /summary` | Entry owner | Aggregated per-user metrics |
| Admin search | `GET /admin/entries`, `GET /admin/entries/{entryId}` | `REPETITION_ADMIN` permission | Cross-user visibility |

**Admin Overrides**:
- Users with `REPETITION_ADMIN` may access any entry and bypass quiz-level repetition flag checks.
- Admin-only endpoints are namespaced under `/api/v1/repetition/admin` and return the same DTOs with additional auditing
  metadata.

---

## Functional Requirements

1. **Unique entry per user/question**: `(user_id, question_id)` pairs are unique. Attempts to create duplicates return HTTP `409`.
2. **Quiz gating**: Only quizzes with `isRepetitionEnabled = true` produce or accept manual entries.
3. **Due logic**: An entry is considered due when `nextReviewAt <= now`. Queue endpoints default to only due entries but can
   include upcoming ones with query flags.
4. **Review window**: Reviews cannot be submitted more than 12 hours before `nextReviewAt` unless the `force` flag is set.
5. **Ease factor bounds**: `easeFactor` is clamped to `[1.3, 2.8]`. Values outside raise validation errors.
6. **Interval limits**: Minimum interval is 1 day; maximum is 365 days. Snooze/reschedule requests must respect these bounds.
7. **Deletion safety**: Entries that are part of an ongoing exam lock window cannot be deleted; attempts return HTTP `409`.
8. **Audit trail**: Every mutation records `reviewedAt`, `rating`, `intervalDays`, and `easeFactor` in an internal audit table.
   Admin endpoints expose the last 20 history items for troubleshooting.

---

## Request DTOs

### ReviewCardRequest

**Used by**: `POST /entries/{entryId}/reviews`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `rating` | `ReviewRating` enum | Yes | Must be one of `AGAIN`, `HARD`, `GOOD`, `EASY`, `SKIP` | User feedback for the card |
| `reviewedAt` | string (ISO-8601 UTC) | Yes | Must be within ±5 minutes of server time | Timestamp the user finished reviewing |
| `force` | boolean | No | Defaults to `false` | Allow review before due time when true |
| `notes` | string | No | ≤ 2000 chars | Optional learner notes stored with review |

**Example**:
```json
{
  "rating": "GOOD",
  "reviewedAt": "2025-06-01T10:15:00Z",
  "force": false,
  "notes": "Remember to review derivation proof"
}
```

---

### BatchReviewRequest

**Used by**: `POST /entries/reviews/batch`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `reviews` | array of objects | Yes | 1-50 items | Each item mirrors `ReviewCardRequest` with `entryId` |
| `reviews[].entryId` | UUID string | Yes | Valid UUID | Entry identifier |
| `reviews[].rating` | `ReviewRating` enum | Yes | Must be valid rating | Card rating |
| `reviews[].reviewedAt` | string (ISO-8601 UTC) | Yes | ≤5 minute drift | Completion timestamp |
| `reviews[].force` | boolean | No | Defaults `false` | Force early review |
| `reviews[].notes` | string | No | ≤2000 chars | Optional notes |

**Example**:
```json
{
  "reviews": [
    {
      "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
      "rating": "AGAIN",
      "reviewedAt": "2025-06-01T10:16:00Z"
    },
    {
      "entryId": "003faa6e-7c8d-4bc0-b8a1-3db3529e3c0a",
      "rating": "EASY",
      "reviewedAt": "2025-06-01T10:17:30Z",
      "notes": "Solid recall"
    }
  ]
}
```

---

### SnoozeEntryRequest

**Used by**: `PATCH /entries/{entryId}/snooze`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `snoozeMinutes` | integer | Yes | 5-1440 | Minutes to postpone next review |
| `reason` | string | No | ≤500 chars | Optional user reason, stored for analytics |

**Example**:
```json
{
  "snoozeMinutes": 30,
  "reason": "Need to revisit theory first"
}
```

---

### ManualEntryRequest

**Used by**: `POST /entries`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `questionId` | UUID string | Yes | Question must be visible to user | Target question |
| `initialIntervalDays` | integer | No | 1-30 | Starting interval; defaults to 1 |
| `initialEaseFactor` | number | No | 1.3-2.8 | Starting ease factor; defaults to 2.5 |
| `nextReviewAt` | string (ISO-8601 UTC) | No | Must be future timestamp | Overrides default next review schedule |

**Example**:
```json
{
  "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "initialIntervalDays": 1,
  "initialEaseFactor": 2.5
}
```

**Example with Custom Schedule**:
```json
{
  "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "initialIntervalDays": 7,
  "initialEaseFactor": 2.2,
  "nextReviewAt": "2025-06-08T10:00:00Z"
}
```

---

### BulkDeleteRequest

**Used by**: `DELETE /entries`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `entryIds` | array of UUID strings | Yes | 1-100 IDs | Entries to delete |
| `reason` | string | No | ≤500 chars | Optional reason logged for auditing |

**Example**:
```json
{
  "entryIds": [
    "799a941a-01f9-42d1-94ad-4d74e2c056c6",
    "003faa6e-7c8d-4bc0-b8a1-3db3529e3c0a",
    "f47ac10b-58cc-4372-a567-0e02b2c3d479"
  ],
  "reason": "User requested to reset learning progress"
}
```

**Note**: Request body is optional when calling `DELETE /entries/{entryId}` (single delete).

---

## Response DTOs

### RepetitionQueueItemDto

Returned by queue/list endpoints.

| Field | Type | Description |
| --- | --- | --- |
| `entryId` | UUID | Identifier for the spaced repetition entry |
| `questionId` | UUID | Linked question |
| `quizId` | UUID | Quiz the question belongs to |
| `questionTitle` | string | Short label/title for quick display |
| `questionType` | string | `QuestionType` enum value |
| `nextReviewAt` | string (ISO-8601 UTC) | Scheduled next review time |
| `intervalDays` | integer | Current interval length in days |
| `repetitionCount` | integer | Number of completed reviews |
| `easeFactor` | number | Current ease factor |
| `due` | boolean | `true` when due at fetch time |
| `tags` | array of strings | Optional tag names associated with the question |

**Example**:
```json
{
  "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
  "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "quizId": "quiz-uuid-here",
  "questionTitle": "What is polymorphism in Java?",
  "questionType": "MCQ_SINGLE",
  "nextReviewAt": "2025-06-01T10:00:00Z",
  "intervalDays": 7,
  "repetitionCount": 3,
  "easeFactor": 2.5,
  "due": true,
  "tags": ["Java", "OOP", "Fundamentals"]
}
```

---

### RepetitionSummaryDto

Returned by `GET /summary` and included in admin dashboards.

| Field | Type | Description |
| --- | --- | --- |
| `dueCount` | integer | Cards currently due |
| `overdueCount` | integer | Cards overdue by more than 24 hours |
| `scheduledToday` | integer | Cards scheduled within the current UTC day |
| `totalActive` | integer | Total active entries for the user |
| `lastReviewedAt` | string (ISO-8601 UTC) \| null | Timestamp of most recent review |
| `streakDays` | integer | Consecutive days with at least one review |

**Example**:
```json
{
  "dueCount": 12,
  "overdueCount": 3,
  "scheduledToday": 25,
  "totalActive": 150,
  "lastReviewedAt": "2025-06-01T09:30:00Z",
  "streakDays": 7
}
```

---

### ReviewResultDto

Returned by review submission endpoints.

| Field | Type | Description |
| --- | --- | --- |
| `entryId` | UUID | Reviewed entry |
| `nextReviewAt` | string (ISO-8601 UTC) | Newly scheduled review time |
| `intervalDays` | integer | Updated interval |
| `repetitionCount` | integer | Updated repetition counter |
| `easeFactor` | number | Updated ease factor |
| `rating` | `ReviewRating` | Rating that was applied |

**Example**:
```json
{
  "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
  "nextReviewAt": "2025-06-08T10:00:00Z",
  "intervalDays": 7,
  "repetitionCount": 4,
  "easeFactor": 2.5,
  "rating": "GOOD"
}
```

---

### BatchReviewResultDto

Returned by `POST /entries/reviews/batch`.

| Field | Type | Description |
| --- | --- | --- |
| `results` | array of `ReviewResultDto` | Successful updates |
| `failed` | array of objects | Items that failed validation |
| `failed[].entryId` | UUID | Entry that failed |
| `failed[].status` | integer | HTTP status code per failure |
| `failed[].message` | string | Human-readable error message |

**Example**:
```json
{
  "results": [
    {
      "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
      "nextReviewAt": "2025-06-08T10:00:00Z",
      "intervalDays": 7,
      "repetitionCount": 4,
      "easeFactor": 2.5,
      "rating": "GOOD"
    },
    {
      "entryId": "003faa6e-7c8d-4bc0-b8a1-3db3529e3c0a",
      "nextReviewAt": "2025-06-15T10:00:00Z",
      "intervalDays": 14,
      "repetitionCount": 5,
      "easeFactor": 2.65,
      "rating": "EASY"
    }
  ],
  "failed": [
    {
      "entryId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "status": 422,
      "message": "Review submitted too early. Next review is scheduled for 2025-06-10T10:00:00Z"
    }
  ]
}
```

---

### ManualEntryResponse

Returned by `POST /entries`.

| Field | Type | Description |
| --- | --- | --- |
| `entryId` | UUID | Created entry |
| `questionId` | UUID | Linked question |
| `nextReviewAt` | string (ISO-8601 UTC) | Scheduled first review |
| `intervalDays` | integer | Initial interval |
| `easeFactor` | number | Initial ease factor |
| `createdAt` | string (ISO-8601 UTC) | Creation timestamp |

**Example**:
```json
{
  "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
  "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "nextReviewAt": "2025-06-02T10:00:00Z",
  "intervalDays": 1,
  "easeFactor": 2.5,
  "createdAt": "2025-06-01T10:00:00Z"
}
```

---

## Enumerations

### ReviewRating

| Value | Meaning | Scheduling Effect |
| --- | --- | --- |
| `AGAIN` | Forgot the card | Interval resets to 1 day, repetition count does not increase |
| `HARD` | Remembered with difficulty | Interval increases slightly (×1.2) and ease factor reduced by 0.15 |
| `GOOD` | Comfortable recall | Interval × ease factor (min +1 day), ease factor unchanged |
| `EASY` | Perfect recall | Interval × ease factor × 1.3, ease factor +0.15 (capped at 2.8) |
| `SKIP` | User skipped | Interval unchanged, `nextReviewAt` postponed by `snoozeMinutes` default (10 min) |

### RepetitionSort

Used in queue queries (`sort` query parameter).

| Value | Sort Order |
| --- | --- |
| `NEXT_REVIEW_ASC` | Soonest review first |
| `NEXT_REVIEW_DESC` | Latest review first |
| `EASE_ASC` | Lowest ease factor first |
| `EASE_DESC` | Highest ease factor first |
| `INTERVAL_ASC` | Shortest interval first |
| `INTERVAL_DESC` | Longest interval first |

---

## Endpoints

### GET /queue

Fetch the current review queue for the authenticated user.

**Query Parameters**:

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `limit` | integer | 20 | Max number of entries to return (1-100) |
| `includeUpcoming` | boolean | false | When true, includes entries with `nextReviewAt > now` |
| `quizId` | UUID | null | Filter entries for a specific quiz |
| `tag` | string | null | Filter by tag slug |
| `sort` | `RepetitionSort` | `NEXT_REVIEW_ASC` | Sorting strategy |

**Success Response**: `200 OK`
```json
[
  {
    "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
    "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "quizId": "quiz-uuid-here",
    "questionTitle": "What is polymorphism in Java?",
    "questionType": "MCQ_SINGLE",
    "nextReviewAt": "2025-06-01T10:00:00Z",
    "intervalDays": 7,
    "repetitionCount": 3,
    "easeFactor": 2.5,
    "due": true,
    "tags": ["Java", "OOP"]
  },
  {
    "entryId": "003faa6e-7c8d-4bc0-b8a1-3db3529e3c0a",
    "questionId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "quizId": "quiz-uuid-here",
    "questionTitle": "Explain inheritance",
    "questionType": "OPEN",
    "nextReviewAt": "2025-06-01T11:30:00Z",
    "intervalDays": 3,
    "repetitionCount": 1,
    "easeFactor": 2.3,
    "due": true,
    "tags": ["Java", "OOP"]
  }
]
```

**Error Responses**:
- `400 Bad Request` (`problem.type = validation-error`) for invalid parameters
- `401 Unauthorized` if token missing/invalid

### GET /entries/{entryId}

Retrieve details for a single entry and its associated question metadata.

**Path Parameters**:
- `{entryId}` - Repetition entry UUID

**Success Response**: `200 OK`
```json
{
  "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
  "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "quizId": "quiz-uuid-here",
  "questionTitle": "What is polymorphism in Java?",
  "questionType": "MCQ_SINGLE",
  "nextReviewAt": "2025-06-01T10:00:00Z",
  "intervalDays": 7,
  "repetitionCount": 3,
  "easeFactor": 2.5,
  "due": true,
  "tags": ["Java", "OOP"],
  "questionContent": {
    "text": "What is polymorphism in Java?",
    "options": [
      {"id": "a", "text": "Multiple inheritance"},
      {"id": "b", "text": "Ability to take multiple forms"},
      {"id": "c", "text": "Method overloading"},
      {"id": "d", "text": "None of the above"}
    ],
    "correctAnswers": ["b"]
  },
  "history": [
    {
      "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
      "nextReviewAt": "2025-06-01T10:00:00Z",
      "intervalDays": 7,
      "repetitionCount": 3,
      "easeFactor": 2.5,
      "rating": "GOOD"
    },
    {
      "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
      "nextReviewAt": "2025-05-25T10:00:00Z",
      "intervalDays": 3,
      "repetitionCount": 2,
      "easeFactor": 2.5,
      "rating": "GOOD"
    }
  ]
}
```

**Error Responses**:
- `403 Forbidden` (`problem.type = access-denied`) when user does not own the entry
- `404 Not Found` (`problem.type = repetition-entry-not-found`) if entry does not exist

### GET /summary

Returns aggregated stats for the current user.

**Success Response**: `200 OK` - `RepetitionSummaryDto`
```json
{
  "dueCount": 12,
  "overdueCount": 3,
  "scheduledToday": 25,
  "totalActive": 150,
  "lastReviewedAt": "2025-06-01T09:30:00Z",
  "streakDays": 7
}
```

**Error Responses**:
- `401 Unauthorized`

### POST /entries/{entryId}/reviews

Submit a review result for a single entry.

**Path Parameters**:
- `{entryId}` - Repetition entry UUID

**Request Body**: `ReviewCardRequest`

**Success Response**: `200 OK` - `ReviewResultDto`
```json
{
  "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
  "nextReviewAt": "2025-06-08T10:00:00Z",
  "intervalDays": 7,
  "repetitionCount": 4,
  "easeFactor": 2.5,
  "rating": "GOOD"
}
```

**Error Responses**:
- `409 Conflict` (`problem.type = repetition-locked`) when entry is locked (e.g., quiz archived)
- `422 Unprocessable Entity` (`problem.type = review-window-violation`) when attempting early review without `force`

### POST /entries/reviews/batch

Submit multiple review results in one request.

**Request Body**: `BatchReviewRequest`

**Success Response**: `207 Multi-Status` - `BatchReviewResultDto`
```json
{
  "results": [
    {
      "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
      "nextReviewAt": "2025-06-08T10:00:00Z",
      "intervalDays": 7,
      "repetitionCount": 4,
      "easeFactor": 2.5,
      "rating": "GOOD"
    }
  ],
  "failed": [
    {
      "entryId": "003faa6e-7c8d-4bc0-b8a1-3db3529e3c0a",
      "status": 422,
      "message": "Review submitted too early"
    }
  ]
}
```

**Error Responses**:
- `413 Payload Too Large` when more than 50 items are submitted

### PATCH /entries/{entryId}/snooze

Snooze the next review for a short period without affecting intervals.

**Path Parameters**:
- `{entryId}` - Repetition entry UUID

**Request Body**: `SnoozeEntryRequest`

**Success Response**: `200 OK` - Updated `RepetitionQueueItemDto`
```json
{
  "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
  "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "quizId": "quiz-uuid-here",
  "questionTitle": "What is polymorphism in Java?",
  "questionType": "MCQ_SINGLE",
  "nextReviewAt": "2025-06-01T10:30:00Z",
  "intervalDays": 7,
  "repetitionCount": 3,
  "easeFactor": 2.5,
  "due": false,
  "tags": ["Java", "OOP"]
}
```

**Error Responses**:
- `409 Conflict` (`problem.type = snooze-limit`) when snooze count exceeds per-day limit (3 per entry)

### POST /entries

Create a manual spaced repetition entry for an accessible question.

**Request Body**: `ManualEntryRequest`

**Success Response**: `201 Created` - `ManualEntryResponse`
```json
{
  "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
  "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "nextReviewAt": "2025-06-02T10:00:00Z",
  "intervalDays": 1,
  "easeFactor": 2.5,
  "createdAt": "2025-06-01T10:00:00Z"
}
```

**Error Responses**:
- `403 Forbidden` (`problem.type = access-denied`) if user lacks access to question
- `404 Not Found` when question ID invalid
- `409 Conflict` if entry already exists for user/question combination

### DELETE /entries/{entryId}

Delete a single entry.

**Path Parameters**:
- `{entryId}` - Repetition entry UUID

**Success Response**: `204 No Content`

**Error Responses**:
- `404 Not Found` - Entry does not exist
- `403 Forbidden` - Not entry owner
- `409 Conflict` - Entry is locked or part of an active attempt review window

### DELETE /entries

Bulk delete entries.

**Request Body**: `BulkDeleteRequest`

**Success Response**: `207 Multi-Status`
```json
{
  "deleted": [
    "799a941a-01f9-42d1-94ad-4d74e2c056c6",
    "003faa6e-7c8d-4bc0-b8a1-3db3529e3c0a"
  ],
  "failed": [
    {
      "entryId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "status": 409,
      "message": "Entry is locked and cannot be deleted"
    }
  ]
}
```

**Error Responses**:
- `400 Bad Request` when body missing or empty

### GET /admin/entries

Admin-only search endpoint for troubleshooting.

**Required Permission**: `REPETITION_ADMIN`

**Query Parameters**:
- `userId` (UUID, optional) - Filter by user
- `questionId` (UUID, optional) - Filter by question
- `dueBefore` (ISO-8601 UTC, optional) - Filter entries due before timestamp
- `dueAfter` (ISO-8601 UTC, optional) - Filter entries due after timestamp
- `page` (integer, optional) - Page number (0-indexed)
- `size` (integer, optional) - Page size

**Success Response**: `200 OK` - `Page<RepetitionQueueItemDto>`
```json
{
  "content": [
    {
      "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
      "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "quizId": "quiz-uuid-here",
      "questionTitle": "What is polymorphism in Java?",
      "questionType": "MCQ_SINGLE",
      "nextReviewAt": "2025-06-01T10:00:00Z",
      "intervalDays": 7,
      "repetitionCount": 3,
      "easeFactor": 2.5,
      "due": true,
      "tags": ["Java", "OOP"],
      "userId": "user-uuid",
      "username": "john_doe",
      "createdAt": "2025-05-01T10:00:00Z"
    }
  ],
  "totalElements": 150,
  "totalPages": 8,
  "number": 0,
  "size": 20
}
```

**Error Responses**:
- `403 Forbidden` if caller lacks `REPETITION_ADMIN`

### GET /admin/entries/{entryId}

Admin detail endpoint returning full audit history.

**Required Permission**: `REPETITION_ADMIN`

**Path Parameters**:
- `{entryId}` - Repetition entry UUID

**Success Response**: `200 OK` - `RepetitionQueueItemDto` with full history
```json
{
  "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
  "questionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "quizId": "quiz-uuid-here",
  "questionTitle": "What is polymorphism in Java?",
  "questionType": "MCQ_SINGLE",
  "nextReviewAt": "2025-06-01T10:00:00Z",
  "intervalDays": 7,
  "repetitionCount": 3,
  "easeFactor": 2.5,
  "due": true,
  "tags": ["Java", "OOP"],
  "userId": "user-uuid",
  "username": "john_doe",
  "createdAt": "2025-05-01T10:00:00Z",
  "history": [
    {
      "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
      "nextReviewAt": "2025-06-01T10:00:00Z",
      "intervalDays": 7,
      "repetitionCount": 3,
      "easeFactor": 2.5,
      "rating": "GOOD",
      "reviewedAt": "2025-05-25T10:15:00Z"
    },
    {
      "entryId": "799a941a-01f9-42d1-94ad-4d74e2c056c6",
      "nextReviewAt": "2025-05-25T10:00:00Z",
      "intervalDays": 3,
      "repetitionCount": 2,
      "easeFactor": 2.5,
      "rating": "GOOD",
      "reviewedAt": "2025-05-22T09:30:00Z"
    }
  ]
}
```

**Error Responses**:
- `403 Forbidden` if caller lacks `REPETITION_ADMIN`
- `404 Not Found` if entry missing

---

## Error Handling

All errors use Spring's `ProblemDetail` format:

```json
{
  "type": "https://api.quizmaker.com/problems/repetition-entry-not-found",
  "title": "Repetition entry not found",
  "status": 404,
  "detail": "Entry 799a941a-01f9-42d1-94ad-4d74e2c056c6 does not exist",
  "instance": "/api/v1/repetition/entries/799a941a-01f9-42d1-94ad-4d74e2c056c6"
}
```

### Common Problem Types

| HTTP Status | `problem.type` | When Triggered |
| --- | --- | --- |
| 400 | `https://api.quizmaker.com/problems/validation-error` | Invalid JSON, enum, or field constraint violation |
| 401 | `https://api.quizmaker.com/problems/unauthorized` | Missing/invalid token |
| 403 | `https://api.quizmaker.com/problems/access-denied` | Entry does not belong to caller |
| 404 | `https://api.quizmaker.com/problems/repetition-entry-not-found` | Entry/question missing |
| 409 | `https://api.quizmaker.com/problems/repetition-locked` | Quiz disabled, entry locked, or duplicate creation |
| 409 | `https://api.quizmaker.com/problems/duplicate-entry` | Manual creation duplicates existing entry |
| 422 | `https://api.quizmaker.com/problems/review-window-violation` | Review submitted too early without `force` |
| 429 | `https://api.quizmaker.com/problems/rate-limit` | Rate limit exceeded |
| 500 | `https://api.quizmaker.com/problems/internal-error` | Unexpected server error (logged server-side) |

`details` array may be included for validation errors (machine-readable field violations).

---

## Integration Guide

### Daily Review Flow

**Complete workflow with React/JavaScript**:

```javascript
// 1. Fetch summary to show dashboard
const fetchSummary = async () => {
  const response = await fetch('/api/v1/repetition/summary', {
    headers: {
      'Authorization': `Bearer ${token}`
    }
  });
  
  const summary = await response.json();
  console.log(`You have ${summary.dueCount} cards due today`);
  console.log(`Current streak: ${summary.streakDays} days`);
  return summary;
};

// 2. Load review queue
const loadQueue = async (limit = 10) => {
  const response = await fetch(
    `/api/v1/repetition/queue?limit=${limit}&sort=NEXT_REVIEW_ASC`,
    {
      headers: { 'Authorization': `Bearer ${token}` }
    }
  );
  
  const queue = await response.json();
  return queue;
};

// 3. Submit a review
const submitReview = async (entryId, rating, notes = null) => {
  const response = await fetch(
    `/api/v1/repetition/entries/${entryId}/reviews`,
    {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        rating: rating, // "AGAIN", "HARD", "GOOD", "EASY"
        reviewedAt: new Date().toISOString(),
        notes: notes
      })
    }
  );
  
  if (!response.ok) {
    const error = await response.json();
    if (response.status === 422) {
      console.error('Review submitted too early:', error.detail);
    }
    throw error;
  }
  
  const result = await response.json();
  console.log(`Next review scheduled for: ${result.nextReviewAt}`);
  return result;
};

// 4. Complete review session
const reviewSession = async () => {
  const queue = await loadQueue(10);
  
  for (const card of queue) {
    // Display question to user
    console.log('Question:', card.questionTitle);
    
    // Get user rating (from UI)
    const rating = await getUserRating(); // "GOOD", "EASY", etc.
    
    // Submit review
    try {
      await submitReview(card.entryId, rating);
      console.log('Review submitted successfully');
    } catch (error) {
      console.error('Failed to submit review:', error);
    }
  }
  
  // Refresh summary after session
  await fetchSummary();
};

// 5. Snooze a card
const snoozeCard = async (entryId, minutes = 30) => {
  const response = await fetch(
    `/api/v1/repetition/entries/${entryId}/snooze`,
    {
      method: 'PATCH',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        snoozeMinutes: minutes,
        reason: 'Need to review theory first'
      })
    }
  );
  
  const updated = await response.json();
  console.log(`Snoozed until: ${updated.nextReviewAt}`);
  return updated;
};
```

---

### Bulk Session Flow

**Batch review for offline-first applications**:

```javascript
const batchReviewSession = async () => {
  // 1. Load queue
  const queue = await loadQueue(20);
  
  // 2. Collect reviews offline
  const reviews = [];
  for (const card of queue) {
    const rating = await getUserRating(card);
    reviews.push({
      entryId: card.entryId,
      rating: rating,
      reviewedAt: new Date().toISOString(),
      notes: getUserNotes(card) // optional
    });
  }
  
  // 3. Submit all at once
  const response = await fetch('/api/v1/repetition/entries/reviews/batch', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ reviews })
  });
  
  const result = await response.json();
  
  // 4. Handle results
  console.log(`Successfully reviewed: ${result.results.length} cards`);
  
  if (result.failed.length > 0) {
    console.error('Failed reviews:');
    result.failed.forEach(failure => {
      console.error(`- ${failure.entryId}: ${failure.message}`);
    });
  }
  
  return result;
};
```

---

### Manual Deck Building

**Allow users to manually add questions to their review deck**:

```javascript
const addQuestionToDeck = async (questionId) => {
  const response = await fetch('/api/v1/repetition/entries', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      questionId: questionId,
      initialIntervalDays: 1,
      initialEaseFactor: 2.5
    })
  });
  
  if (response.status === 409) {
    console.log('Question already in your deck');
    return null;
  }
  
  const entry = await response.json();
  console.log('Added to deck. First review:', entry.nextReviewAt);
  return entry;
};

const removeFromDeck = async (entryId) => {
  const response = await fetch(
    `/api/v1/repetition/entries/${entryId}`,
    {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${token}` }
    }
  );
  
  if (response.status === 204) {
    console.log('Removed from deck');
  }
};

const bulkRemove = async (entryIds, reason) => {
  const response = await fetch('/api/v1/repetition/entries', {
    method: 'DELETE',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      entryIds: entryIds,
      reason: reason
    })
  });
  
  const result = await response.json();
  console.log(`Deleted: ${result.deleted.length} entries`);
  
  if (result.failed.length > 0) {
    console.error('Failed to delete:', result.failed);
  }
  
  return result;
};
```

---

### Admin Troubleshooting Flow

**Admin tools for debugging user issues**:

```javascript
const adminSearchEntries = async (userId, filters = {}) => {
  const params = new URLSearchParams({
    userId: userId,
    page: filters.page || 0,
    size: filters.size || 20,
    ...filters
  });
  
  const response = await fetch(
    `/api/v1/repetition/admin/entries?${params}`,
    {
      headers: { 'Authorization': `Bearer ${adminToken}` }
    }
  );
  
  if (response.status === 403) {
    console.error('Requires REPETITION_ADMIN permission');
    return null;
  }
  
  const page = await response.json();
  console.log(`Found ${page.totalElements} entries for user`);
  return page;
};

const adminInspectEntry = async (entryId) => {
  const response = await fetch(
    `/api/v1/repetition/admin/entries/${entryId}`,
    {
      headers: { 'Authorization': `Bearer ${adminToken}` }
    }
  );
  
  const entry = await response.json();
  
  console.log('Entry details:', entry);
  console.log('Review history:');
  entry.history.forEach((review, index) => {
    console.log(`  ${index + 1}. ${review.rating} at ${review.reviewedAt}`);
    console.log(`     Next: ${review.nextReviewAt}, Interval: ${review.intervalDays} days`);
  });
  
  return entry;
};
```

---

### Error Handling

**Comprehensive error handling for common scenarios**:

```javascript
const handleRepetitionErrors = async (operation) => {
  try {
    return await operation();
  } catch (error) {
    if (error.status === 400) {
      // Validation error
      console.error('Validation failed:', error.detail);
      showUserMessage('Invalid input. Please check your data.');
    } else if (error.status === 401) {
      // Unauthorized
      console.error('Authentication required');
      redirectToLogin();
    } else if (error.status === 403) {
      // Forbidden
      console.error('Access denied:', error.detail);
      showUserMessage('You do not have permission to access this card.');
    } else if (error.status === 404) {
      // Not found
      console.error('Entry not found');
      showUserMessage('This card no longer exists.');
    } else if (error.status === 409) {
      // Conflict
      if (error.type.includes('repetition-locked')) {
        showUserMessage('This card is currently locked.');
      } else if (error.type.includes('duplicate-entry')) {
        showUserMessage('This question is already in your deck.');
      } else if (error.type.includes('snooze-limit')) {
        showUserMessage('You have reached the daily snooze limit for this card.');
      }
    } else if (error.status === 422) {
      // Unprocessable entity
      if (error.type.includes('review-window-violation')) {
        const shouldForce = confirm(
          'This card is not due yet. Review anyway?'
        );
        if (shouldForce) {
          // Retry with force flag
          return await operation({ force: true });
        }
      }
    } else if (error.status === 429) {
      // Rate limit
      const retryAfter = error.headers?.get('Retry-After') || 60;
      console.log(`Rate limited. Retry after ${retryAfter} seconds`);
      showUserMessage(`Please wait ${retryAfter} seconds before trying again.`);
    } else {
      // Unexpected error
      console.error('Unexpected error:', error);
      showUserMessage('An unexpected error occurred. Please try again.');
    }
  }
};

// Usage
const reviewWithErrorHandling = async (entryId, rating) => {
  return await handleRepetitionErrors(() => 
    submitReview(entryId, rating)
  );
};
```

---

## Security Considerations

1. **Ownership Enforcement**: Every endpoint double-checks the authenticated user matches entry owner; unauthorized access
   returns `403` rather than `404` to avoid enumeration.
2. **Quiz Visibility**: Manual creation ensures the caller retains at least read access to the quiz/question.
3. **Rate Limiting**: Enforced per user to mitigate brute-force enumeration attempts.
4. **Audit Logging**: Reviews log user agent and IP hash for fraud detection (available to admins only).
5. **PII Protection**: Notes are stored encrypted at rest and redacted from admin responses unless `includeNotes=true` query
   parameter is provided by an admin with elevated permission.
6. **Token Expiry**: Clients must handle `401` responses gracefully (refresh token and retry once).
7. **Data Privacy**: Review notes and history are never exposed to other users; admin endpoints require explicit permissions.
8. **Consistency**: Use optimistic locking to prevent lost updates; clients should retry with exponential backoff on `409`
   conflicts triggered by concurrent reviews.