# Quiz Controller API Reference

Complete frontend integration guide for `/api/v1/quizzes` REST endpoints. This document is self-contained and includes all DTOs, permissions, rate limits, validation rules, and error semantics needed to integrate quiz management and AI generation features.

## Table of Contents

- [Overview](#overview)
- [Permission Matrix](#permission-matrix)
- [Rate Limits](#rate-limits)
- [Request DTOs](#request-dtos)
  - [Core Quiz DTOs](#core-quiz-dtos)
  - [Generation DTOs](#generation-dtos)
- [Response DTOs](#response-dtos)
  - [Quiz DTOs](#quiz-dtos)
  - [Generation DTOs](#generation-response-dtos)
  - [Export DTOs](#export-dtos)
  - [Analytics DTOs](#analytics-dtos)
- [Enumerations](#enumerations)
- [Endpoints](#endpoints)
  - [CRUD & Listing](#crud--listing)
  - [Question & Tag Management](#question--tag-management)
  - [Analytics & Attempts](#analytics--attempts)
  - [Visibility & Status](#visibility--status)
  - [AI Generation Lifecycle](#ai-generation-lifecycle)
  - [Data Export](#data-export)
  - [Admin Operations](#admin-operations)
- [Error Handling](#error-handling)
- [Integration Guide](#integration-guide)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/v1/quizzes`
* **Authentication**: Required for most endpoints (except public listing). Uses JWT Bearer token in `Authorization` header.
* **Authorization Model**: Hybrid - Permission-based for CRUD operations, ownership-based for modifications.
* **Content-Type**: `application/json` for requests and responses. Multipart form-data for file uploads.
* **Error Format**: All errors return `ProblemDetail` or `ErrorResponse` object
* **Caching**: List endpoints support ETag-based HTTP caching

---

## Permission Matrix

Quiz endpoints use permission-based authorization for operations. Users need specific permissions granted through their roles.

| Capability | Endpoint(s) | Required Permission(s) | Additional Rules |
| --- | --- | --- | --- |
| **Create quizzes** | `POST /quizzes` | `QUIZ_CREATE` | Non-moderators limited to `PRIVATE`/`DRAFT` |
| **Update quizzes** | `PATCH /quizzes/{id}`, `PATCH /quizzes/bulk-update` | `QUIZ_UPDATE` | Ownership or moderator check enforced |
| **Delete quizzes** | `DELETE /quizzes/{id}`, `DELETE /quizzes?ids=` | `QUIZ_DELETE` | Owner or moderator |
| **Set PUBLIC** | `PATCH /quizzes/{id}/visibility` | `QUIZ_MODERATE` OR `QUIZ_ADMIN` | Owners can only set `PRIVATE` |
| **Publish quiz** | `PATCH /quizzes/{id}/status` | `QUIZ_MODERATE` OR `QUIZ_ADMIN` | Publishing to PUBLIC requires moderator |
| **Moderation** | `POST /quizzes/{id}/submit-for-review` | `QUIZ_UPDATE` | Must be quiz owner |
| **AI Generation** | `POST /generate-*` endpoints | `QUIZ_CREATE` | Rate limited to 3/min |
| **View public quizzes** | `GET /quizzes/public` | None (public endpoint) | Rate limited to 120/min |
| **View own quizzes** | `GET /quizzes?scope=me` | Authenticated user | No special permission needed |
| **View all quizzes** | `GET /quizzes?scope=all` | `QUIZ_READ` OR moderator/admin | Cross-user access |
| **Export public quizzes** | `GET /quizzes/export?scope=public` | None (public endpoint) | Rate limited to 30/min |
| **Export own quizzes** | `GET /quizzes/export?scope=me` | `QUIZ_READ` | Must be authenticated |
| **Export all quizzes** | `GET /quizzes/export?scope=all` | `QUIZ_MODERATE` OR `QUIZ_ADMIN` | Cross-user export |
| **Admin generation ops** | `POST /generation-jobs/cleanup-stale`, `POST /generation-jobs/{id}/force-cancel` | `QUIZ_ADMIN` | System administration |

**Ownership Rules**:
- Quiz creators can update/delete their own quizzes
- Moderators and admins can modify any quiz
- Public visibility and publish status require elevated permissions

---

## Rate Limits

The API enforces per-minute quotas to prevent abuse. Exceeding limits returns HTTP `429 Too Many Requests` with `Retry-After` header.

| Operation | Limit | Key | Scope |
| --- | --- | --- | --- |
| **List (authenticated)** | 120 requests/min | Client IP | `GET /quizzes` |
| **List (public)** | 120 requests/min | Client IP | `GET /quizzes/public` |
| **Export (authenticated)** | 30 requests/min | Username | `GET /quizzes/export` (scope=me/all) |
| **Export (public)** | 30 requests/min | Client IP | `GET /quizzes/export` (scope=public) |
| **AI Generation (start)** | 3 requests/min | Username | All `/generate-*` endpoints |
| **AI Generation (cancel)** | 5 requests/min | Username | `DELETE /generation-status/{jobId}` |

**Retry Strategy**:
- Always check `Retry-After` header in 429 responses
- Implement exponential backoff for retry logic
- Cache results where possible (list endpoints support ETags)

---

## Request DTOs

### Core Quiz DTOs

#### CreateQuizRequest

**Used by**: `POST /quizzes`

| Field | Type | Required | Validation | Default | Description |
| --- | --- | --- | --- | --- | --- |
| `title` | string | Yes | 3-100 characters | - | Quiz title |
| `description` | string | No | Max 1000 characters | `null` | Quiz description |
| `visibility` | `Visibility` enum | No | `PUBLIC` or `PRIVATE` | `PRIVATE` | Visibility setting |
| `difficulty` | `Difficulty` enum | No | `EASY`, `MEDIUM`, `HARD` | `MEDIUM` | Difficulty level |
| `isRepetitionEnabled` | boolean | Yes | - | - | Enable spaced repetition for this quiz |
| `timerEnabled` | boolean | Yes | - | - | Enable timer for this quiz |
| `estimatedTime` | integer | Yes | 1-180 minutes | - | Estimated time to complete quiz |
| `timerDuration` | integer | Yes | 1-180 minutes | - | Timer duration in minutes (if timer enabled) |
| `categoryId` | UUID | No | Valid category UUID | `null` | Category assignment; omitted or invalid IDs fall back to the configured default category |
| `tagIds` | List of UUIDs | No | Valid tag UUIDs | `[]` | Associated tags |

---

#### UpdateQuizRequest

**Used by**: `PATCH /quizzes/{id}`, `PATCH /quizzes/bulk-update`

All fields are optional. Omitted fields keep existing values.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `title` | string | No | 3-100 characters | Updated title |
| `description` | string | No | Max 1000 characters | Updated description |
| `visibility` | `Visibility` enum | No | `PUBLIC` or `PRIVATE` | Updated visibility |
| `difficulty` | `Difficulty` enum | No | `EASY`, `MEDIUM`, `HARD` | Updated difficulty |
| `isRepetitionEnabled` | Boolean | No | - | Enable/disable spaced repetition |
| `timerEnabled` | Boolean | No | - | Enable/disable timer |
| `estimatedTime` | Integer | No | 1-180 minutes | Updated estimated time |
| `timerDuration` | Integer | No | 1-180 minutes | Updated timer duration |
| `categoryId` | UUID | No | Valid category UUID | Updated category |
| `tagIds` | List of UUIDs | No | Valid tag UUIDs | Updated tags |

**Notes**:
- Use Boolean (capitalized) for nullable boolean fields
- Status has a dedicated endpoint (`PATCH /quizzes/{id}/status`)
- Individual questions and tags can be managed via dedicated endpoints

---

#### BulkQuizUpdateRequest

**Used by**: `PATCH /quizzes/bulk-update`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `quizIds` | array of UUIDs | Yes | Non-empty, valid UUIDs | Quizzes to update |
| `updates` | `UpdateQuizRequest` | Yes | Valid update object | Changes to apply |

---

#### VisibilityUpdateRequest

**Used by**: `PATCH /quizzes/{id}/visibility`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `isPublic` | boolean | Yes | - | `true` for PUBLIC, `false` for PRIVATE |


**Authorization Note**: Setting `isPublic: true` requires `QUIZ_MODERATE` or `QUIZ_ADMIN` permission.

---

#### QuizStatusUpdateRequest

**Used by**: `PATCH /quizzes/{id}/status`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `status` | `QuizStatus` enum | Yes | Valid status | New status |


**Valid Transitions**:
- `DRAFT` → `PENDING_REVIEW`, `PUBLISHED`, `ARCHIVED`
- `PENDING_REVIEW` → `PUBLISHED`, `REJECTED`, `DRAFT`
- `PUBLISHED` → `ARCHIVED`
- `REJECTED` → `DRAFT`
- `ARCHIVED` → `DRAFT`

---

#### QuizSearchCriteria

**Used by**: `GET /quizzes` (query parameters)

**Pagination Parameters** (Spring Data standard):
| Parameter | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `page` | integer | No | 0 | Page number (0-indexed) |
| `size` | integer | No | 20 | Page size (1-100) |
| `sort` | string | No | `createdAt,desc` | Sort specification (e.g., "title,asc") |

**Scope Parameter** (access control):
| Parameter | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `scope` | string | No | `public` | Filter scope: `public`, `me`, `all` |

**Filter Parameters** (QuizSearchCriteria):
| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `category` | array of strings | No | Filter by category names (comma-delimited) |
| `tag` | array of strings | No | Filter by tag names (comma-delimited) |
| `authorName` | string | No | Filter by author username |
| `search` | string | No | Full-text search on title/description |
| `difficulty` | `Difficulty` enum | No | Filter by difficulty: `EASY`, `MEDIUM`, `HARD` |


---

### Generation DTOs

#### GenerateQuizFromDocumentRequest

**Used by**: `POST /quizzes/generate-from-document`

| Field | Type | Required | Validation | Default | Description |
| --- | --- | --- | --- | --- | --- |
| `documentId` | UUID | Yes | Valid document UUID | - | Processed document to use |
| `quizScope` | `QuizScope` enum | No | Valid scope | `ENTIRE_DOCUMENT` | Scope of generation |
| `chunkIndices` | array of integers | Conditional | Required if scope=`SPECIFIC_CHUNKS` | `null` | Chunk indices to use |
| `chapterTitle` | string | Conditional | Required if scope=`SPECIFIC_CHAPTER` | `null` | Chapter to use |
| `chapterNumber` | integer | Conditional | Alternative to chapterTitle | `null` | Chapter number |
| `quizTitle` | string | No | Max 100 characters | `null` | Custom quiz title (AI generates if omitted) |
| `quizDescription` | string | No | Max 500 characters | `null` | Custom quiz description (AI generates if omitted) |
| `questionsPerType` | object (map) | Yes | 1-10 per type | - | Question type → count mapping |
| `difficulty` | `Difficulty` enum | Yes | Valid difficulty | - | Question difficulty |
| `estimatedTimePerQuestion` | integer | No | 1-10 minutes | `1` | Minutes per question |
| `categoryId` | UUID | No | Valid category | `null` | Category assignment |
| `tagIds` | array of UUIDs | No | Valid tag UUIDs | `[]` | Tags to assign |



---

#### GenerateQuizFromUploadRequest

**Used by**: `POST /quizzes/generate-from-upload` (multipart/form-data)

| Field | Type | Required | Validation | Default | Description |
| --- | --- | --- | --- | --- | --- |
| `file` | file | Yes | Supported types, < max size | - | Document file to upload |
| `chunkingStrategy` | string | No | `CHAPTER_BASED`, `FIXED_SIZE`, `SEMANTIC` | `CHAPTER_BASED` | How to split document |
| `maxChunkSize` | integer | No | 1000-100000 | `250000` | Max characters per chunk |
| `quizScope` | string | No | Valid scope | `ENTIRE_DOCUMENT` | Generation scope |
| `chunkIndices` | array of integers | Conditional | For SPECIFIC_CHUNKS | `null` | Chunk indices |
| `chapterTitle` | string | Conditional | For SPECIFIC_CHAPTER | `null` | Chapter title |
| `chapterNumber` | integer | Conditional | For SPECIFIC_CHAPTER | `null` | Chapter number |
| `quizTitle` | string | No | Max 100 characters | `null` | Custom quiz title (AI generates if omitted) |
| `quizDescription` | string | No | Max 500 characters | `null` | Custom quiz description (AI generates if omitted) |
| `questionsPerType` | JSON string | Yes | Valid JSON map, 1-10 per type | - | Question type → count (as JSON string) |
| `difficulty` | string | Yes | `EASY`, `MEDIUM`, `HARD` | - | Question difficulty |
| `estimatedTimePerQuestion` | integer | No | 1-10 minutes | `1` | Minutes per question |
| `categoryId` | UUID string | No | Valid UUID | `null` | Category |
| `tagIds` | array of UUIDs | No | Valid UUIDs | `[]` | Tags |
| `language` | string | No | ISO 639-1 code | `en` | Target language |


**Notes**:
- `questionsPerType` must be a JSON string (not raw object)
- `quizTitle` and `quizDescription` are optional - AI will generate if omitted
- Supported file formats: PDF, DOCX, TXT (check server config for others)
- File size limits apply (check server configuration)

---

#### GenerateQuizFromTextRequest

**Used by**: `POST /quizzes/generate-from-text`

| Field | Type | Required | Validation | Default | Description |
| --- | --- | --- | --- | --- | --- |
| `text` | string | Yes | Non-empty, ≤ 300,000 chars | - | Raw text to generate from |
| `language` | string | No | ISO 639-1 code | `en` | Language of text content |
| `chunkingStrategy` | string | No | `CHAPTER_BASED`, `FIXED_SIZE`, `SEMANTIC` | `CHAPTER_BASED` | How to chunk text |
| `maxChunkSize` | integer | No | 1000-100000 | `250000` | Max chars per chunk |
| `quizScope` | `QuizScope` enum | No | Valid scope | `ENTIRE_DOCUMENT` | Generation scope |
| `chunkIndices` | array of integers | Conditional | For SPECIFIC_CHUNKS | `null` | Chunk indices |
| `chapterTitle` | string | Conditional | For SPECIFIC_CHAPTER | `null` | Chapter title |
| `chapterNumber` | integer | Conditional | For SPECIFIC_CHAPTER | `null` | Chapter number |
| `quizTitle` | string | No | Max 100 characters | `null` | Custom quiz title (AI generates if omitted) |
| `quizDescription` | string | No | Max 500 characters | `null` | Custom quiz description (AI generates if omitted) |
| `questionsPerType` | object (map) | Yes | 1-10 per type | - | Question type → count |
| `difficulty` | `Difficulty` enum | Yes | Valid difficulty | - | Question difficulty |
| `estimatedTimePerQuestion` | integer | No | 1-10 minutes | `1` | Minutes per question |
| `categoryId` | UUID | No | Valid UUID | `null` | Category |
| `tagIds` | array of UUIDs | No | Valid UUIDs | `[]` | Tags |


---

## Response DTOs

### Quiz DTOs

#### QuizDto

**Returned by**: Most quiz endpoints

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Quiz unique identifier |
| `creatorId` | UUID | User who created the quiz |
| `categoryId` | UUID (nullable) | Associated category |
| `title` | string | Quiz title |
| `description` | string (nullable) | Quiz description |
| `visibility` | `Visibility` enum | `PUBLIC` or `PRIVATE` |
| `difficulty` | `Difficulty` enum | `EASY`, `MEDIUM`, or `HARD` |
| `status` | `QuizStatus` enum | Current status |
| `estimatedTime` | Integer (nullable) | Estimated time in minutes |
| `isRepetitionEnabled` | Boolean (nullable) | Spaced repetition enabled |
| `timerEnabled` | Boolean (nullable) | Timer enabled |
| `timerDuration` | Integer (nullable) | Timer duration in minutes |
| `tagIds` | List of UUIDs | Associated tag IDs |
| `createdAt` | ISO 8601 datetime | Creation timestamp |
| `updatedAt` | ISO 8601 datetime | Last update timestamp |


---

#### BulkQuizUpdateOperationResultDto

**Returned by**: `PATCH /quizzes/bulk-update`

| Field | Type | Description |
| --- | --- | --- |
| `successfulIds` | array of UUIDs | Successfully updated quiz IDs |
| `failures` | object (map) | UUID → error message for failed updates |


---

### Generation Response DTOs

#### QuizGenerationResponse

**Returned by**: All generation start endpoints (`POST /generate-*`)

| Field | Type | Description |
| --- | --- | --- |
| `jobId` | UUID | Generation job identifier (use for polling) |
| `status` | string | Initial status (usually "PROCESSING") |
| `message` | string | Human-readable status message |
| `estimatedTimeSeconds` | integer (nullable) | Estimated completion time |


---

#### QuizGenerationStatus

**Returned by**: `GET /generation-status/{jobId}`, `DELETE /generation-status/{jobId}`

| Field | Type | Description |
| --- | --- | --- |
| `jobId` | UUID | Job identifier |
| `status` | `GenerationStatus` enum | Current status |
| `totalChunks` | integer | Total chunks to process |
| `processedChunks` | integer | Chunks processed so far |
| `progressPercentage` | number | Progress (0-100) |
| `currentChunk` | string | Current processing status |
| `totalTasks` | integer | Total tasks (chunk × types) |
| `completedTasks` | integer | Completed tasks |
| `estimatedCompletion` | ISO 8601 datetime | Estimated completion time |
| `errorMessage` | string (nullable) | Error message if failed |
| `totalQuestionsGenerated` | integer | Questions generated so far |
| `elapsedTimeSeconds` | integer | Time elapsed since start |
| `estimatedTimeRemainingSeconds` | integer | Estimated time remaining |
| `generatedQuizId` | UUID (nullable) | Generated quiz ID (when completed) |
| `startedAt` | ISO 8601 datetime | Job start time |
| `completedAt` | ISO 8601 datetime (nullable) | Job completion time |



---

### Export DTOs

#### QuizExportDto

**Returned by**: `GET /quizzes/export` (JSON format)

Stable structure designed for round-trip import/export. Used in JSON_EDITABLE format exports.

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Quiz unique identifier |
| `title` | string | Quiz title |
| `description` | string (nullable) | Quiz description |
| `visibility` | `Visibility` enum | `PUBLIC` or `PRIVATE` |
| `difficulty` | `Difficulty` enum | `EASY`, `MEDIUM`, or `HARD` |
| `estimatedTime` | integer (nullable) | Estimated completion time in minutes |
| `tags` | array of strings | Tag names (not IDs) |
| `category` | string (nullable) | Category name (not ID) |
| `creatorId` | UUID | User who created the quiz |
| `questions` | array of `QuestionExportDto` | Nested questions with full content |
| `createdAt` | ISO 8601 datetime | Creation timestamp |
| `updatedAt` | ISO 8601 datetime | Last update timestamp |

**Notes**:
- No `status` field (unlike QuizDto)
- Uses category/tag names instead of IDs for better readability
- Questions are nested inline (not separate entities)


---

#### QuestionExportDto

**Returned by**: Nested in `QuizExportDto`

Preserves question structure with JSON content for round-trip compatibility.

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Question unique identifier |
| `type` | `QuestionType` enum | Question type |
| `difficulty` | `Difficulty` enum | Question difficulty |
| `questionText` | string | The question text |
| `content` | JSON object | Question-specific content (options, answers, etc.) |
| `hint` | string (nullable) | Optional hint text |
| `explanation` | string (nullable) | Optional explanation text |
| `attachmentUrl` | string (nullable) | Optional attachment URL |


---

### Analytics DTOs

#### QuizResultSummaryDto

**Returned by**: `GET /quizzes/{id}/results`

| Field | Type | Description |
| --- | --- | --- |
| `quizId` | UUID | Quiz identifier |
| `attemptsCount` | integer | Total attempts |
| `averageScore` | number | Average score (0-100) |
| `bestScore` | number | Highest score |
| `worstScore` | number | Lowest score |
| `passRate` | number | Percentage of passing attempts |
| `questionStats` | array of `QuestionStatsDto` | Per-question statistics |

**QuestionStatsDto**:

| Field | Type | Description |
| --- | --- | --- |
| `questionId` | UUID | Question identifier |
| `questionText` | string | Question text |
| `attemptsCount` | integer | Times attempted |
| `correctCount` | integer | Times answered correctly |
| `averageTimeSeconds` | number | Average time spent |
| `difficulty` | `Difficulty` enum | Question difficulty |


---

#### LeaderboardEntryDto

**Returned by**: `GET /quizzes/{id}/leaderboard`

| Field | Type | Description |
| --- | --- | --- |
| `userId` | UUID | User identifier |
| `username` | string | Username |
| `bestScore` | number | User's best score (0-100) |
| `rank` | integer | Leaderboard rank |


---

## Enumerations

### Difficulty

| Value | Description |
| --- | --- |
| `EASY` | Easy difficulty level |
| `MEDIUM` | Medium difficulty level |
| `HARD` | Hard difficulty level |

---

### Visibility

| Value | Description |
| --- | --- |
| `PUBLIC` | Quiz visible to all users |
| `PRIVATE` | Quiz visible only to creator |

---

### QuizStatus

| Value | Description |
| --- | --- |
| `DRAFT` | Work in progress, not published |
| `PENDING_REVIEW` | Submitted for moderation |
| `PUBLISHED` | Active and available |
| `REJECTED` | Rejected by moderators |
| `ARCHIVED` | No longer active |

---

### QuizScope

| Value | Description | Required Fields |
| --- | --- | --- |
| `ENTIRE_DOCUMENT` | Use entire document | None |
| `SPECIFIC_CHUNKS` | Use specific chunks | `chunkIndices` |
| `SPECIFIC_CHAPTER` | Use specific chapter | `chapterTitle` or `chapterNumber` |
| `SPECIFIC_SECTION` | Use specific section | `sectionTitle` |

---

### GenerationStatus

| Value | Description |
| --- | --- |
| `PENDING` | Job queued, not started |
| `PROCESSING` | Currently generating |
| `COMPLETED` | Successfully completed |
| `FAILED` | Generation failed |
| `CANCELLED` | Cancelled by user |

---

### QuestionType

| Value | Description |
| --- | --- |
| `MCQ_SINGLE` | Multiple choice, single answer |
| `MCQ_MULTI` | Multiple choice, multiple answers |
| `TRUE_FALSE` | True/False question |
| `OPEN` | Open-ended text answer |
| `FILL_GAP` | Fill in the blank(s) |
| `ORDERING` | Put items in correct order |
| `MATCHING` | Match items between lists |
| `COMPLIANCE` | Compliance statements |
| `HOTSPOT` | Click regions on image |

**Usage in `questionsPerType` field:**
```json
{
  "MCQ_SINGLE": 5,
  "TRUE_FALSE": 3,
  "OPEN": 2
}
```
Each key is a `QuestionType`, each value is the count (1-10) to generate per chunk.

---

## Endpoints

### CRUD & Listing

#### 1. Create Quiz

```
POST /api/v1/quizzes
```

**Required Permission**: `QUIZ_CREATE`

**Request Body**: `CreateQuizRequest`

**Success Response**: `201 Created`
```json
{
  "quizId": "newly-created-quiz-uuid"
}
```

**Notes**:
- When `categoryId` is omitted or points to a non-existent category, the backend automatically assigns the category configured by `quiz.default-category-id`.

**Error Responses**:
- `400` - Validation error (invalid title length, etc.)
- `401` - Unauthorized
- `403` - Missing `QUIZ_CREATE` permission

---

#### 2. List Quizzes

```
GET /api/v1/quizzes
```

**Required Permission**: Depends on scope parameter

**Query Parameters**: See `QuizSearchCriteria`

**Success Response**: `200 OK`
```json
{
  "content": [ /* Array of QuizDto */ ],
  "totalElements": 150,
  "totalPages": 8,
  "number": 0,
  "size": 20,
  "first": true,
  "last": false
}
```

**Headers**:
- `ETag`: Weak ETag for caching (e.g., `W/"hash-value"`)
- Send `If-None-Match` header to get `304 Not Modified` if unchanged

**Error Responses**:
- `401` - Unauthorized (for `scope=me` or `scope=all`)
- `403` - Missing permissions (for `scope=all`)
- `429` - Rate limit exceeded (120/min)

---

#### 3. Get Quiz by ID

```
GET /api/v1/quizzes/{quizId}
```

**Path Parameters**:
- `{quizId}` - Quiz UUID

**Success Response**: `200 OK` - `QuizDto`

**Error Responses**:
- `404` - Quiz not found or not accessible
- `403` - Private quiz, not the owner

---

#### 4. Update Quiz

```
PATCH /api/v1/quizzes/{quizId}
```

**Required Permission**: `QUIZ_UPDATE` (and must be owner or moderator)

**Request Body**: `UpdateQuizRequest`

**Success Response**: `200 OK` - `QuizDto`

**Error Responses**:
- `400` - Validation error
- `403` - Not authorized to update
- `404` - Quiz not found

---

#### 5. Bulk Update Quizzes

```
PATCH /api/v1/quizzes/bulk-update
```

**Required Permission**: `QUIZ_UPDATE`

**Request Body**: `BulkQuizUpdateRequest`

**Success Response**: `200 OK` - `BulkQuizUpdateOperationResultDto`


---

#### 6. Delete Quiz

```
DELETE /api/v1/quizzes/{quizId}
```

**Required Permission**: `QUIZ_DELETE` (and must be owner or moderator)

**Success Response**: `204 No Content`

**Error Responses**:
- `403` - Not authorized
- `404` - Quiz not found

---

#### 7. Bulk Delete Quizzes

```
DELETE /api/v1/quizzes?ids=uuid1&ids=uuid2&ids=uuid3
```

**Required Permission**: `QUIZ_DELETE`

**Query Parameters**:
- `ids` - Repeated parameter with quiz UUIDs

**Success Response**: `204 No Content`

**Error Responses**:
- `400` - Invalid UUID format
- `403` - Not authorized for one or more quizzes
- `404` - One or more quizzes not found

---

### Question & Tag Management

#### 8. Add Question to Quiz

```
POST /api/v1/quizzes/{quizId}/questions/{questionId}
```

**Required Permission**: `QUIZ_UPDATE`

**Success Response**: `204 No Content`

**Error Responses**:
- `404` - Quiz or question not found
- `403` - Not authorized
- `409` - Question already in quiz

---

#### 9. Remove Question from Quiz

```
DELETE /api/v1/quizzes/{quizId}/questions/{questionId}
```

**Required Permission**: `QUIZ_UPDATE`

**Success Response**: `204 No Content`

**Error Responses**:
- `404` - Quiz or question not found
- `403` - Not authorized

---

#### 10. Add Tag to Quiz

```
POST /api/v1/quizzes/{quizId}/tags/{tagId}
```

**Required Permission**: `QUIZ_UPDATE`

**Success Response**: `204 No Content`

**Error Responses**:
- `404` - Quiz or tag not found
- `403` - Not authorized
- `409` - Tag already assigned

---

#### 11. Remove Tag from Quiz

```
DELETE /api/v1/quizzes/{quizId}/tags/{tagId}
```

**Required Permission**: `QUIZ_UPDATE`

**Success Response**: `204 No Content`

---

#### 12. Change Category

```
PATCH /api/v1/quizzes/{quizId}/category/{categoryId}
```

**Required Permission**: `QUIZ_UPDATE`

**Success Response**: `204 No Content`

**Error Responses**:
- `404` - Quiz or category not found
- `403` - Not authorized

---

### Analytics & Attempts

#### 13. Get Quiz Results Summary

```
GET /api/v1/quizzes/{quizId}/results
```

**Success Response**: `200 OK` - `QuizResultSummaryDto`

**Error Responses**:
- `404` - Quiz not found
- `403` - Not authorized (must be quiz owner)

---

#### 14. Get Leaderboard

```
GET /api/v1/quizzes/{quizId}/leaderboard?top=10
```

**Query Parameters**:
- `top` (integer, optional) - Number of top entries, default: 10

**Success Response**: `200 OK` - Array of `LeaderboardEntryDto`

**Error Responses**:
- `404` - Quiz not found
- `403` - Not authorized

---

#### 15. List Quiz Attempts

```
GET /api/v1/quizzes/{quizId}/attempts
```

**Success Response**: `200 OK` - Array of `AttemptDto`

**Error Responses**:
- `404` - Quiz not found
- `403` - Not quiz owner

---

#### 16. Get Attempt Stats for Quiz Owner

```
GET /api/v1/quizzes/{quizId}/attempts/{attemptId}/stats
```

**Path Parameters**:
- `{quizId}` - Quiz UUID
- `{attemptId}` - Attempt UUID

**Required Authentication**: Yes (must be authenticated)

**Success Response**: `200 OK` - `AttemptStatsDto`

**Error Responses**:
- `401` - Unauthorized (not authenticated)
- `403` - Not quiz owner
- `404` - Quiz or attempt not found

**Notes**:
- Only quiz owners can access detailed attempt statistics
- Returns comprehensive statistics including score, time taken, correct/incorrect answers breakdown

---

### Visibility & Status

#### 17. Update Visibility

```
PATCH /api/v1/quizzes/{quizId}/visibility
```

**Required Permission**: `QUIZ_UPDATE` (for PRIVATE), `QUIZ_MODERATE` or `QUIZ_ADMIN` (for PUBLIC)

**Request Body**: `VisibilityUpdateRequest`

**Success Response**: `200 OK` - `QuizDto`

**Error Responses**:
- `400` - Invalid transition
- `403` - Not authorized (owners can only set PRIVATE)
- `404` - Quiz not found

---

#### 18. Update Status

```
PATCH /api/v1/quizzes/{quizId}/status
```

**Required Permission**: `QUIZ_UPDATE` (for DRAFT/ARCHIVED), `QUIZ_MODERATE` or `QUIZ_ADMIN` (for PUBLISHED)

**Request Body**: `QuizStatusUpdateRequest`

**Success Response**: `200 OK` - `QuizDto`

**Error Responses**:
- `400` - Invalid status transition
- `403` - Not authorized
- `404` - Quiz not found

---

#### 19. Submit for Review

```
POST /api/v1/quizzes/{quizId}/submit-for-review
```

**Required Permission**: `QUIZ_UPDATE` (must be owner)

**Success Response**: `204 No Content`

**Error Responses**:
- `403` - Not quiz owner
- `404` - Quiz not found

**Notes**:
- Quiz status changes to `PENDING_REVIEW`
- Moderators will review before publishing

---

### AI Generation Lifecycle

#### 20. Generate from Document

```
POST /api/v1/quizzes/generate-from-document
```

**Required Permission**: `QUIZ_CREATE`

**Rate Limit**: 3 requests/min per user

**Request Body**: `GenerateQuizFromDocumentRequest`

**Success Response**: `202 Accepted` - `QuizGenerationResponse`

**Error Responses**:
- `400` - Invalid request (missing fields, invalid scope)
- `404` - Document not found
- `409` - Generation job already active for this document
- `429` - Rate limit exceeded

---

#### 21. Generate from Upload

```
POST /api/v1/quizzes/generate-from-upload
Content-Type: multipart/form-data
```

**Required Permission**: `QUIZ_CREATE`

**Rate Limit**: 3 requests/min per user

**Request Body**: `GenerateQuizFromUploadRequest` (multipart)

**Success Response**: `202 Accepted` - `QuizGenerationResponse`

**Error Responses**:
- `400` - Invalid file, validation error, JSON parse error
- `415` - Unsupported file type
- `422` - Document processing failed
- `429` - Rate limit exceeded

**Supported File Types**:
- PDF (`.pdf`)
- Word (`.doc`, `.docx`)
- Text (`.txt`)
- Other document formats (check server config)

---

#### 22. Generate from Text

```
POST /api/v1/quizzes/generate-from-text
```

**Required Permission**: `QUIZ_CREATE`

**Rate Limit**: 3 requests/min per user

**Request Body**: `GenerateQuizFromTextRequest`

**Success Response**: `202 Accepted` - `QuizGenerationResponse`

**Error Responses**:
- `400` - Text too long (> 300,000 chars) or validation error
- `409` - Existing active job
- `422` - Text processing failed
- `429` - Rate limit exceeded

---

#### 23. Poll Generation Status

```
GET /api/v1/quizzes/generation-status/{jobId}
```

**Success Response**: `200 OK` - `QuizGenerationStatus`

**Error Responses**:
- `404` - Job not found
- `403` - Not job owner

**Polling Strategy**:
- Poll every 2-5 seconds while `status` is `PROCESSING`
- Stop polling when status is terminal: `COMPLETED`, `FAILED`, or `CANCELLED`

---

#### 24. Get Generated Quiz

```
GET /api/v1/quizzes/generated-quiz/{jobId}
```

**Success Response**: `200 OK` - `QuizDto`

**Error Responses**:
- `404` - Job or quiz not found
- `409` - Job not yet completed
- `403` - Not job owner

---

#### 25. Cancel Generation

```
DELETE /api/v1/quizzes/generation-status/{jobId}
```

**Rate Limit**: 5 requests/min per user

**Success Response**: `200 OK` - `QuizGenerationStatus` (updated with cancelled status)

**Error Responses**:
- `400` - Job already completed (cannot cancel)
- `404` - Job not found
- `403` - Not job owner
- `429` - Rate limit exceeded

---

#### 26. List Generation Jobs

```
GET /api/v1/quizzes/generation-jobs
```

**Query Parameters**:
- `page`, `size`, `sort` (standard pagination)

**Success Response**: `200 OK` - `Page<QuizGenerationStatus>`

**Error Responses**:
- `401` - Not authenticated

---

#### 27. Get Generation Statistics

```
GET /api/v1/quizzes/generation-jobs/statistics
```

**Success Response**: `200 OK`
```json
{
  "totalJobs": 150,
  "completedJobs": 120,
  "failedJobs": 10,
  "cancelledJobs": 5,
  "activeJobs": 15,
  "averageCompletionTimeSeconds": 180
}
```

---

### Data Export

#### 28. Export Quizzes

```
GET /api/v1/quizzes/export
```

**Permission Requirements** (scope-dependent):
- `scope=public` - No authentication required (anonymous access)
- `scope=me` - Authenticated user with `QUIZ_READ` permission  
- `scope=all` - `QUIZ_MODERATE` or `QUIZ_ADMIN` permission

**Rate Limit**: 30 requests/min per IP (public scope), 30 requests/min per user (authenticated)

**Query Parameters**:

| Parameter | Type | Required | Validation | Default | Description |
| --- | --- | --- | --- | --- | --- |
| `format` | string enum | Yes | `JSON_EDITABLE`, `XLSX_EDITABLE`, `HTML_PRINT`, `PDF_PRINT` | - | Export format |
| `scope` | string | No | `public`, `me`, `all` | `public` | Access scope filter |
| `categoryIds` | array of UUIDs | No | Valid UUIDs | `[]` | Filter by categories |
| `tags` | array of strings | No | Tag names (case-insensitive) | `[]` | Filter by tags |
| `authorId` | UUID | No | Valid user UUID | Current user (if `scope=me`) | Filter by author |
| `difficulty` | string | No | `EASY`, `MEDIUM`, `HARD` | - | Filter by difficulty |
| `search` | string | No | Search term | - | Search in title/description |
| `quizIds` | array of UUIDs | No | Valid quiz UUIDs | `[]` | Export specific quizzes |
| `includeCover` | boolean | No | - | `true` | Include cover page (print formats) |
| `includeMetadata` | boolean | No | - | `true` | Include quiz metadata (print formats) |
| `answersOnSeparatePages` | boolean | No | - | `true` | Separate answer key pages (print formats) |
| `includeHints` | boolean | No | - | `false` | Include question hints (print formats) |
| `includeExplanations` | boolean | No | - | `false` | Include answer explanations (print formats) |
| `groupQuestionsByType` | boolean | No | - | `false` | Group by question type (print formats) |

**Scope Behavior**:
- `public`: Returns only PUBLIC + PUBLISHED quizzes (anonymous access allowed)
- `me`: Returns only authenticated user's quizzes (all statuses/visibilities)
- `all`: Returns all quizzes (requires moderation permissions)

**Export Formats**:

| Format | Content-Type | Extension | Round-Trip | Use Case |
| --- | --- | --- | --- | --- |
| `JSON_EDITABLE` | `application/json` | `.json` | ✅ Yes | Full data export/import, API integration |
| `XLSX_EDITABLE` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | `.xlsx` | ✅ Yes | Spreadsheet editing, bulk review |
| `HTML_PRINT` | `text/html` | `.html` | ❌ No | Browser printing, web preview |
| `PDF_PRINT` | `application/pdf` | `.pdf` | ❌ No | Professional printing, distribution |

**Success Response**: `200 OK`

**Response Headers**:
- `Content-Type`: Format-specific MIME type
- `Content-Disposition`: `attachment; filename="quizzes_{scope}_{timestamp}_{filters}.{ext}"`
- `Transfer-Encoding`: `chunked` (streaming response)

**Example Filename Patterns**:
- `quizzes_public_20241014_1430.json`
- `quizzes_me_20241014_1430_cat_tag_diff.xlsx`
- `quizzes_all_20241014_1430_search.pdf`

**JSON Format Structure** (`QuizExportDto[]`):
```json
[
  {
    "id": "quiz-uuid",
    "title": "Quiz Title",
    "description": "Quiz description",
    "visibility": "PUBLIC",
    "difficulty": "MEDIUM",
    "estimatedTime": 30,
    "tags": ["java", "fundamentals"],
    "category": "Programming",
    "creatorId": "user-uuid",
    "questions": [
      {
        "id": "question-uuid",
        "type": "MCQ_SINGLE",
        "difficulty": "EASY",
        "questionText": "What is Java?",
        "content": {
          "options": [
            {"id": "opt-1", "text": "A programming language", "isCorrect": true},
            {"id": "opt-2", "text": "A coffee brand", "isCorrect": false}
          ]
        },
        "hint": "Think about technology",
        "explanation": "Java is a widely-used programming language",
        "attachmentUrl": null
      }
    ],
    "createdAt": "2024-01-15T10:00:00Z",
    "updatedAt": "2024-01-16T14:30:00Z"
  }
]
```

**QuizExportDto Fields**:
- `id` (UUID): Quiz identifier
- `title` (string): Quiz title
- `description` (string, nullable): Quiz description
- `visibility` (enum): `PUBLIC` or `PRIVATE`
- `difficulty` (enum): `EASY`, `MEDIUM`, or `HARD`
- `estimatedTime` (integer, nullable): Estimated completion time in minutes
- `tags` (array of strings): Tag names
- `category` (string, nullable): Category name
- `creatorId` (UUID): Creator's user ID
- `questions` (array): Nested questions (see QuestionExportDto)
- `createdAt` (ISO 8601): Creation timestamp
- `updatedAt` (ISO 8601): Last update timestamp

**QuestionExportDto Fields**:
- `id` (UUID): Question identifier
- `type` (enum): Question type (MCQ_SINGLE, MCQ_MULTI, TRUE_FALSE, OPEN, etc.)
- `difficulty` (enum): Question difficulty
- `questionText` (string): The question text (generic description for FILL_GAP)
- `content` (JSON object): Question-specific content (options, correct answers, etc.)
  - **FILL_GAP Note**: For `FILL_GAP` questions, `content.text` contains the actual prompt with underscores (e.g., "The ___ operates at the ___ layer"). Print formats (HTML_PRINT, PDF_PRINT) display this text instead of the generic `questionText`.
- `hint` (string, nullable): Optional hint
- `explanation` (string, nullable): Optional explanation
- `attachmentUrl` (string, nullable): Optional attachment URL

**XLSX Format Structure**:

**Sheet Organization**:
- **Sheet 1 ("Quizzes")**: Quiz-level metadata
- **One sheet per question type** (only types present in export)

**Question Sheet Structure** (all types follow this pattern):
1. Question ID
2. Quiz ID  
3. Difficulty
4. Question Text
5. **[Type-specific content columns]** ← Answer/options come right after question for easy input
6. Hint (optional)
7. Explanation (optional)
8. Attachment URL (optional)

**Type-Specific Content Columns**:
- **"MCQ_SINGLE" / "MCQ_MULTI"**: Option 1-6 (text + "Correct" flag)
- **"TRUE_FALSE"**: Correct Answer (True/False)
- **"OPEN"**: Sample Answer (text)
- **"FILL_GAP"**: Gap 1-10 Answer
- **"ORDERING"**: Item 1-10 (in correct order)
- **"MATCHING"**: Left 1-8, Right 1-8 pairs
- **"COMPLIANCE"**: Statement 1-10 (text + "Compliant" flag)
- **"HOTSPOT"**: Image URL, Hotspot Count

**Example TRUE_FALSE Sheet**:
| Question ID | Quiz ID | Difficulty | Question Text | Correct Answer | Hint | Explanation | Attachment URL |
|-------------|---------|------------|---------------|----------------|------|-------------|----------------|
| uuid-1 | quiz-uuid | EASY | Is Java compiled? | True | Think about JVM | Java compiles to bytecode | |
| uuid-2 | quiz-uuid | MEDIUM | Is Python statically typed? | False | | Dynamic typing | |

**Benefits**:
✅ **No raw JSON** - Clean, readable format for humans  
✅ **Answer right after question** - Quick data entry without scrolling  
✅ **Type-specific columns** - Only relevant fields per type  
✅ **Easy bulk editing** - Edit all MCQ options or all True/False answers at once  
✅ **Import-friendly** - Clear structure for parsing back into system  
✅ **Professional appearance** - Clean spreadsheet suitable for sharing

**HTML/PDF Print Options**:
All print-specific parameters control the output formatting:
- **Cover page**: Shows quiz title and metadata for single quiz; generic title for multiple quizzes
- **Metadata blocks**: Quiz details, difficulty, time estimates, tags
- **Answer key placement**: Separate pages or inline
- **Hints**: Warm yellow background with orange border for visibility
- **Explanations**: Blue background with darker blue border for clarity
- **Grouping**: Questions organized by type (MCQ, True/False, etc.); each type starts on a new page
- **Matching questions**: Clean 2-column grid layout with numbers (1, 2, 3...) on left and letters (A, B, C...) on right (no instruction text)
- **Ordering questions**: Items labeled with letters (A, B, C, D...)
- **Answer key formats**:
  - MATCHING: Number-letter combinations (e.g., `1 → A, 2 → B, 3 → C`)
  - ORDERING: Letter sequence (e.g., `C → A → D → B`)
  - COMPLIANCE: Statement positions (e.g., `Compliant: 1, 3, 5`)
- **Version identifier**: Every export generates a unique 6-character version code (e.g., `A1B2C3`) displayed in the footer of every page. Use this to match student sheets with the correct answer key, especially when content is shuffled.
- **Page footers**: Left side shows version code, right side shows page numbers (e.g., "Page 1 of 5")
- **Deterministic shuffling**: Options, statements, and items are shuffled using a seed derived from the export ID, ensuring the answer key always matches the question order for that specific export

**Error Responses**:
- `400` - Invalid format enum, invalid UUID format, validation error
- `401` - Unauthorized (for `scope=me` or `scope=all` without authentication)
- `403` - Forbidden (missing required permission for scope)
- `404` - No quizzes match the filters (returns empty result, not error)
- `429` - Rate limit exceeded

**Notes**:
- Response is streamed to prevent OOM for large exports
- Questions are ordered deterministically (by createdAt, then id)
- All filters are optional and can be combined
- Print options only apply to `HTML_PRINT` and `PDF_PRINT` formats
- JSON and XLSX formats preserve full data structure for round-trip import
- Filename includes timestamp and filter indicators for traceability

**Version Code & Reproducibility**:
- Each print export generates a unique 6-character version code (e.g., `3F7A2K`) displayed in the footer
- The version code is derived from a unique export ID and ensures reproducibility
- For shuffled content (MCQ options, ORDERING items, MATCHING pairs, COMPLIANCE statements), the shuffle is deterministic based on a seed tied to the export
- **Troubleshooting**: If a student's quiz sheet doesn't match the answer key, check the version code in the footer. Each export version has its own answer key mapping
- Use the version code to audit which export a student received and match it with the correct answer key
- The same content with the same filters exported at different times will have different version codes and potentially different shuffled order

---

### Admin Operations

#### 29. Cleanup Stale Jobs

```
POST /api/v1/quizzes/generation-jobs/cleanup-stale
```

**Required Permission**: `QUIZ_ADMIN`

**Success Response**: `200 OK`
```
Cleaned up 5 stale generation jobs
```

**Error Responses**:
- `401/403` - Not admin

---

#### 30. Force Cancel Job

```
POST /api/v1/quizzes/generation-jobs/{jobId}/force-cancel
```

**Required Permission**: `QUIZ_ADMIN`

**Success Response**: `200 OK`
```
Job forcefully cancelled
```

**Error Responses**:
- `404` - Job not found
- `500` - Unexpected error (with error message)
- `401/403` - Not admin

---

#### 31. Get Public Quizzes (No Auth Required)

```
GET /api/v1/quizzes/public
```

**No Authentication Required**

**Rate Limit**: 120 requests/min per IP

**Query Parameters**: Standard pagination and search

**Success Response**: `200 OK` - `Page<QuizDto>` (only PUBLIC quizzes)

**Headers**:
- `ETag`: Weak ETag for caching

**Error Responses**:
- `429` - Rate limit exceeded

---

## Error Handling

### Error Response Format

All errors return the same `ErrorResponse` structure:

| Field | Type | Description |
| --- | --- | --- |
| `timestamp` | ISO 8601 datetime | When the error occurred |
| `status` | integer | HTTP status code |
| `error` | string | Error category (e.g., "Bad Request", "Not Found") |
| `details` | array of strings | Error details/validation messages |

### HTTP Status Codes

| Code | Error Category | Common Causes |
| --- | --- | --- |
| `400` | Bad Request | Validation errors, invalid data, malformed JSON |
| `401` | Unauthorized | Missing or invalid authentication token |
| `403` | Forbidden | Missing required permission or not resource owner |
| `404` | Not Found | Quiz, document, job, or other resource doesn't exist |
| `409` | Conflict | Duplicate operation, invalid state transition |
| `415` | Unsupported Media Type | Invalid file type in upload |
| `422` | Unprocessable Entity | Document/text processing failed |
| `429` | Too Many Requests | Rate limit exceeded (includes `Retry-After` header) |
| `500` | Internal Server Error | Unexpected server error |

---

## Security Considerations

### Permission-Based Access

1. **Least Privilege**: Request only necessary permissions
2. **Permission Checks**: Endpoints validate permissions before processing
3. **Ownership Validation**: Users can only modify their own quizzes (unless moderator/admin)
4. **Visibility Enforcement**: PUBLIC visibility requires elevated permissions

### Quiz Ownership

1. **Creator Rights**: Quiz creators have full control over their quizzes
2. **Moderator Override**: Moderators can manage any quiz
3. **Visibility Rules**: Setting PUBLIC requires moderator permission
4. **Status Transitions**: Publishing requires proper permissions

### AI Generation Security

1. **Rate Limiting**: Strict limits (3/min) prevent abuse
2. **Job Isolation**: Users can only access their own generation jobs
3. **Token Tracking**: Generation jobs track resource usage
4. **Cancellation Rights**: Only job owner can cancel

### Data Privacy

1. **Private Quizzes**: Not accessible to other users
2. **Analytics Privacy**: Only quiz owner can view detailed analytics
3. **Leaderboard Control**: Consider privacy settings
4. **Attempt Data**: Linked to quiz ownership

### File Upload Security

1. **File Type Validation**: Only allowed types accepted
2. **Size Limits**: Enforced at server level
3. **Content Scanning**: Files processed safely
4. **Malware Protection**: Implement virus scanning

### Best Practices

**Frontend**:
- Validate permissions before showing UI controls
- Cache permission status to avoid unnecessary checks
- Handle 403 errors gracefully with clear messaging
- Implement rate limit backoff strategies
- Use ETags for efficient caching
- Validate file types client-side before upload

**API Usage**:
- Always include authentication token
- Respect rate limits and `Retry-After` headers
- Poll generation status efficiently (2-5 second intervals)
- Cancel unused generation jobs to save resources
- Use bulk operations when updating multiple quizzes

**Token Management**:
- Store tokens securely (HttpOnly cookies recommended)
- Implement token refresh before expiration
- Clear tokens on logout
- Handle 401 errors with re-authentication flow

**Error Handling**:
- Parse `ProblemDetail` responses for user feedback
- Display validation errors clearly
- Implement retry logic for 500 errors
- Handle network failures gracefully

**Performance**:
- Use pagination for large lists
- Implement infinite scroll or load more
- Cache quiz listings with ETags
- Debounce search inputs
- Lazy load quiz details

**Testing**:
- Test permission checks with different user roles
- Verify ownership validations
- Test rate limiting behavior
- Validate file upload error handling
- Test generation job polling and cancellation