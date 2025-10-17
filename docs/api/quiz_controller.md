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
| `description` | string | No | Max 500 characters | `null` | Quiz description |
| `visibility` | `Visibility` enum | No | `PUBLIC` or `PRIVATE` | `PRIVATE` | Visibility setting |
| `difficulty` | `Difficulty` enum | No | `EASY`, `MEDIUM`, `HARD` | `MEDIUM` | Difficulty level |
| `status` | `QuizStatus` enum | No | Valid status | `DRAFT` | Initial status |
| `timeLimitMinutes` | integer | No | > 0 | `null` | Time limit in minutes (null = no limit) |
| `showHints` | boolean | No | - | `true` | Whether to show hints |
| `shuffleQuestions` | boolean | No | - | `false` | Shuffle question order |
| `showResults` | boolean | No | - | `true` | Show results after completion |
| `categoryId` | UUID | No | Valid category UUID | `null` | Category assignment; omitted or invalid IDs fall back to the configured default category |
| `tagIds` | array of UUIDs | No | Valid tag UUIDs | `[]` | Associated tags |

**Example**:
```json
{
  "title": "Java Fundamentals Quiz",
  "description": "Test your knowledge of Java basics",
  "visibility": "PRIVATE",
  "difficulty": "MEDIUM",
  "status": "DRAFT",
  "timeLimitMinutes": 30,
  "showHints": true,
  "shuffleQuestions": false,
  "showResults": true,
  "categoryId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "tagIds": [
    "11111111-2222-3333-4444-555555555555",
    "66666666-7777-8888-9999-000000000000"
  ]
}
```

---

#### UpdateQuizRequest

**Used by**: `PATCH /quizzes/{id}`, `PATCH /quizzes/bulk-update`

All fields are optional. Omitted fields keep existing values.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `title` | string | No | 3-100 characters | Updated title |
| `description` | string | No | Max 500 characters | Updated description |
| `difficulty` | `Difficulty` enum | No | Valid difficulty | Updated difficulty |
| `timeLimitMinutes` | Integer | No | > 0 or null | Updated time limit |
| `showHints` | Boolean | No | - | Updated hint visibility |
| `shuffleQuestions` | Boolean | No | - | Updated shuffle setting |
| `showResults` | Boolean | No | - | Updated results visibility |
| `categoryId` | UUID | No | Valid category UUID | Updated category |

**Example**:
```json
{
  "title": "Updated Quiz Title",
  "difficulty": "HARD",
  "timeLimitMinutes": 45
}
```

**Notes**:
- Use Boolean (capitalized) for nullable boolean fields
- Visibility and status have dedicated endpoints
- Tags and questions managed via separate endpoints

---

#### BulkQuizUpdateRequest

**Used by**: `PATCH /quizzes/bulk-update`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `quizIds` | array of UUIDs | Yes | Non-empty, valid UUIDs | Quizzes to update |
| `updates` | `UpdateQuizRequest` | Yes | Valid update object | Changes to apply |

**Example**:
```json
{
  "quizIds": [
    "quiz-uuid-1",
    "quiz-uuid-2",
    "quiz-uuid-3"
  ],
  "updates": {
    "difficulty": "MEDIUM",
    "showHints": true
  }
}
```

---

#### VisibilityUpdateRequest

**Used by**: `PATCH /quizzes/{id}/visibility`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `isPublic` | boolean | Yes | - | `true` for PUBLIC, `false` for PRIVATE |

**Example**:
```json
{
  "isPublic": true
}
```

**Authorization Note**: Setting `isPublic: true` requires `QUIZ_MODERATE` or `QUIZ_ADMIN` permission.

---

#### QuizStatusUpdateRequest

**Used by**: `PATCH /quizzes/{id}/status`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `status` | `QuizStatus` enum | Yes | Valid status | New status |

**Example**:
```json
{
  "status": "PUBLISHED"
}
```

**Valid Transitions**:
- `DRAFT` → `PENDING_REVIEW`, `PUBLISHED`, `ARCHIVED`
- `PENDING_REVIEW` → `PUBLISHED`, `REJECTED`, `DRAFT`
- `PUBLISHED` → `ARCHIVED`
- `REJECTED` → `DRAFT`
- `ARCHIVED` → `DRAFT`

---

#### QuizSearchCriteria

**Used by**: `GET /quizzes` (query parameters)

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `page` | integer | No | Page number (0-indexed), default: 0 |
| `size` | integer | No | Page size (1-100), default: 20 |
| `sort` | string | No | Sort specification (e.g., "title,asc"), default: "createdAt,desc" |
| `scope` | string | No | Filter scope: `public` (default), `me`, `all` |
| `search` | string | No | Search term for title/description |
| `categoryNames` | array | No | Filter by category names |
| `tagNames` | array | No | Filter by tag names |
| `authorName` | string | No | Filter by author username |
| `difficulty` | string | No | Filter by difficulty: `EASY`, `MEDIUM`, `HARD` |

**Example URL**:
```
GET /api/v1/quizzes?scope=me&difficulty=MEDIUM&search=java&page=0&size=20&sort=title,asc
```

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
| `sectionTitle` | string | Conditional | For `SPECIFIC_SECTION` | `null` | Section to use |
| `title` | string | Yes | 3-100 characters | - | Generated quiz title |
| `description` | string | No | Max 500 characters | `null` | Quiz description |
| `questionsPerType` | object (map) | Yes | 1-10 per type | - | Question type → count mapping |
| `difficulty` | `Difficulty` enum | Yes | Valid difficulty | - | Question difficulty |
| `estimatedTimePerQuestion` | integer | No | > 0 | `2` | Minutes per question |
| `categoryId` | UUID | No | Valid category | `null` | Category assignment |
| `tagIds` | array of UUIDs | No | Valid tag UUIDs | `[]` | Tags to assign |
| `language` | string | No | ISO language code | `en` | Target language |

**Example**:
```json
{
  "documentId": "doc-uuid-here",
  "quizScope": "ENTIRE_DOCUMENT",
  "title": "Java Basics Generated Quiz",
  "description": "Auto-generated from Java tutorial document",
  "questionsPerType": {
    "MCQ_SINGLE": 5,
    "MCQ_MULTI": 3,
    "TRUE_FALSE": 4,
    "OPEN": 2
  },
  "difficulty": "MEDIUM",
  "estimatedTimePerQuestion": 2,
  "categoryId": "category-uuid",
  "tagIds": ["tag-uuid-1", "tag-uuid-2"],
  "language": "en"
}
```

**Example with Specific Chunks**:
```json
{
  "documentId": "doc-uuid-here",
  "quizScope": "SPECIFIC_CHUNKS",
  "chunkIndices": [0, 2, 5],
  "title": "Selected Chapters Quiz",
  "questionsPerType": {
    "MCQ_SINGLE": 3,
    "TRUE_FALSE": 2
  },
  "difficulty": "EASY",
  "language": "en"
}
```

---

#### GenerateQuizFromUploadRequest

**Used by**: `POST /quizzes/generate-from-upload` (multipart/form-data)

| Field | Type | Required | Validation | Default | Description |
| --- | --- | --- | --- | --- | --- |
| `file` | file | Yes | Supported types, < max size | - | Document file to upload |
| `chunkingStrategy` | string | No | `CHAPTER_BASED`, `FIXED_SIZE`, `SEMANTIC` | `CHAPTER_BASED` | How to split document |
| `maxChunkSize` | integer | No | > 0 | `250000` | Max characters per chunk |
| `title` | string | Yes | 3-100 characters | - | Quiz title |
| `description` | string | No | Max 500 characters | `null` | Quiz description |
| `questionsPerType` | JSON string | Yes | Valid JSON map | - | Question type → count (as JSON string) |
| `difficulty` | string | Yes | Valid difficulty | - | Question difficulty |
| `quizScope` | string | No | Valid scope | `ENTIRE_DOCUMENT` | Generation scope |
| `chunkIndices` | array | Conditional | For SPECIFIC_CHUNKS | `null` | Chunk indices |
| `language` | string | No | ISO code | `en` | Target language |
| `categoryId` | UUID string | No | Valid UUID | `null` | Category |
| `tagIds` | array | No | Valid UUIDs | `[]` | Tags |

**Example (multipart form)**:
```
POST /api/v1/quizzes/generate-from-upload
Content-Type: multipart/form-data

file: [binary file data]
title: "Uploaded Document Quiz"
description: "Generated from uploaded PDF"
questionsPerType: "{\"MCQ_SINGLE\":5,\"TRUE_FALSE\":3}"
difficulty: "MEDIUM"
chunkingStrategy: "CHAPTER_BASED"
maxChunkSize: 250000
language: "en"
```

**Notes**:
- `questionsPerType` must be a JSON string (not raw object)
- File must be PDF, DOCX, TXT, or other supported formats
- File size limits apply (check server configuration)

---

#### GenerateQuizFromTextRequest

**Used by**: `POST /quizzes/generate-from-text`

| Field | Type | Required | Validation | Default | Description |
| --- | --- | --- | --- | --- | --- |
| `text` | string | Yes | Non-empty, ≤ 300,000 chars | - | Raw text to generate from |
| `title` | string | Yes | 3-100 characters | - | Quiz title |
| `description` | string | No | Max 500 characters | `null` | Quiz description |
| `questionsPerType` | object (map) | Yes | 1-10 per type | - | Question type → count |
| `difficulty` | `Difficulty` enum | Yes | Valid difficulty | - | Question difficulty |
| `chunkingStrategy` | string | No | Valid strategy | `SEMANTIC` | How to chunk text |
| `maxChunkSize` | integer | No | > 0 | `250000` | Max chars per chunk |
| `language` | string | No | ISO code | `en` | Target language |
| `categoryId` | UUID | No | Valid UUID | `null` | Category |
| `tagIds` | array | No | Valid UUIDs | `[]` | Tags |

**Example**:
```json
{
  "text": "Long text content here... Java is a programming language...",
  "title": "Java Concepts Quiz",
  "description": "Generated from custom text",
  "questionsPerType": {
    "MCQ_SINGLE": 4,
    "TRUE_FALSE": 3
  },
  "difficulty": "EASY",
  "chunkingStrategy": "SEMANTIC",
  "language": "en"
}
```

---

## Response DTOs

### Quiz DTOs

#### QuizDto

**Returned by**: Most quiz endpoints

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Quiz unique identifier |
| `title` | string | Quiz title |
| `description` | string (nullable) | Quiz description |
| `visibility` | `Visibility` enum | `PUBLIC` or `PRIVATE` |
| `difficulty` | `Difficulty` enum | `EASY`, `MEDIUM`, or `HARD` |
| `status` | `QuizStatus` enum | Current status |
| `creatorId` | UUID | User who created the quiz |
| `creatorUsername` | string | Creator's username |
| `categoryId` | UUID (nullable) | Associated category |
| `categoryName` | string (nullable) | Category name |
| `tagIds` | array of UUIDs | Associated tag IDs |
| `tagNames` | array of strings | Tag names |
| `questionCount` | integer | Number of questions |
| `timeLimitMinutes` | integer (nullable) | Time limit (null = no limit) |
| `showHints` | boolean | Whether hints are shown |
| `shuffleQuestions` | boolean | Whether questions are shuffled |
| `showResults` | boolean | Whether results shown after completion |
| `createdAt` | ISO 8601 datetime | Creation timestamp |
| `updatedAt` | ISO 8601 datetime | Last update timestamp |

**Example**:
```json
{
  "id": "quiz-uuid-here",
  "title": "Java Fundamentals",
  "description": "Test your Java knowledge",
  "visibility": "PUBLIC",
  "difficulty": "MEDIUM",
  "status": "PUBLISHED",
  "creatorId": "user-uuid",
  "creatorUsername": "john_doe",
  "categoryId": "category-uuid",
  "categoryName": "Programming",
  "tagIds": ["tag-uuid-1", "tag-uuid-2"],
  "tagNames": ["Java", "OOP"],
  "questionCount": 15,
  "timeLimitMinutes": 30,
  "showHints": true,
  "shuffleQuestions": false,
  "showResults": true,
  "createdAt": "2024-01-15T10:00:00Z",
  "updatedAt": "2024-01-16T14:30:00Z"
}
```

---

#### BulkQuizUpdateOperationResultDto

**Returned by**: `PATCH /quizzes/bulk-update`

| Field | Type | Description |
| --- | --- | --- |
| `successfulIds` | array of UUIDs | Successfully updated quiz IDs |
| `failures` | object (map) | UUID → error message for failed updates |

**Example**:
```json
{
  "successfulIds": [
    "quiz-uuid-1",
    "quiz-uuid-2"
  ],
  "failures": {
    "quiz-uuid-3": "Quiz not found",
    "quiz-uuid-4": "Unauthorized: not the owner"
  }
}
```

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

**Example**:
```json
{
  "jobId": "job-uuid-here",
  "status": "PROCESSING",
  "message": "Quiz generation started successfully",
  "estimatedTimeSeconds": 120
}
```

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

**Example (In Progress)**:
```json
{
  "jobId": "job-uuid",
  "status": "PROCESSING",
  "totalChunks": 5,
  "processedChunks": 3,
  "progressPercentage": 60.0,
  "currentChunk": "Processing chunk 3/5",
  "totalTasks": 15,
  "completedTasks": 9,
  "estimatedCompletion": "2024-01-15T10:45:00Z",
  "errorMessage": null,
  "totalQuestionsGenerated": 12,
  "elapsedTimeSeconds": 90,
  "estimatedTimeRemainingSeconds": 60,
  "generatedQuizId": null,
  "startedAt": "2024-01-15T10:30:00Z",
  "completedAt": null
}
```

**Example (Completed)**:
```json
{
  "jobId": "job-uuid",
  "status": "COMPLETED",
  "totalChunks": 5,
  "processedChunks": 5,
  "progressPercentage": 100.0,
  "currentChunk": "Completed",
  "totalTasks": 15,
  "completedTasks": 15,
  "totalQuestionsGenerated": 20,
  "generatedQuizId": "generated-quiz-uuid",
  "startedAt": "2024-01-15T10:30:00Z",
  "completedAt": "2024-01-15T10:35:00Z",
  "elapsedTimeSeconds": 300,
  "errorMessage": null
}
```

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

**Example**:
```json
{
  "id": "quiz-uuid",
  "title": "Java Fundamentals",
  "description": "Test your Java knowledge",
  "visibility": "PUBLIC",
  "difficulty": "MEDIUM",
  "estimatedTime": 30,
  "tags": ["java", "oop"],
  "category": "Programming",
  "creatorId": "user-uuid",
  "questions": [ /* array of QuestionExportDto */ ],
  "createdAt": "2024-01-15T10:00:00Z",
  "updatedAt": "2024-01-16T14:30:00Z"
}
```

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

**Example**:
```json
{
  "id": "question-uuid",
  "type": "MCQ_SINGLE",
  "difficulty": "EASY",
  "questionText": "What is polymorphism?",
  "content": {
    "options": [
      {"id": "opt-1", "text": "Many forms", "isCorrect": true},
      {"id": "opt-2", "text": "Single form", "isCorrect": false}
    ]
  },
  "hint": "Think about OOP principles",
  "explanation": "Polymorphism allows objects to take many forms",
  "attachmentUrl": null
}
```

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

**Example**:
```json
{
  "quizId": "quiz-uuid",
  "attemptsCount": 150,
  "averageScore": 75.5,
  "bestScore": 100.0,
  "worstScore": 30.0,
  "passRate": 82.0,
  "questionStats": [
    {
      "questionId": "question-uuid-1",
      "questionText": "What is polymorphism?",
      "attemptsCount": 150,
      "correctCount": 120,
      "averageTimeSeconds": 45.0,
      "difficulty": "MEDIUM"
    }
  ]
}
```

---

#### LeaderboardEntryDto

**Returned by**: `GET /quizzes/{id}/leaderboard`

| Field | Type | Description |
| --- | --- | --- |
| `userId` | UUID | User identifier |
| `username` | string | Username |
| `bestScore` | number | User's best score (0-100) |
| `rank` | integer | Leaderboard rank |

**Example**:
```json
[
  {
    "userId": "user-uuid-1",
    "username": "john_doe",
    "bestScore": 98.5,
    "rank": 1
  },
  {
    "userId": "user-uuid-2",
    "username": "jane_smith",
    "bestScore": 95.0,
    "rank": 2
  }
]
```

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

**Example Response**:
```json
{
  "successfulIds": ["uuid-1", "uuid-2"],
  "failures": {
    "uuid-3": "Quiz not found"
  }
}
```

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

### Visibility & Status

#### 16. Update Visibility

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

#### 17. Update Status

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

#### 18. Submit for Review

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

#### 19. Generate from Document

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

#### 20. Generate from Upload

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

#### 21. Generate from Text

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

#### 22. Poll Generation Status

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

#### 23. Get Generated Quiz

```
GET /api/v1/quizzes/generated-quiz/{jobId}
```

**Success Response**: `200 OK` - `QuizDto`

**Error Responses**:
- `404` - Job or quiz not found
- `409` - Job not yet completed
- `403` - Not job owner

---

#### 24. Cancel Generation

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

#### 25. List Generation Jobs

```
GET /api/v1/quizzes/generation-jobs
```

**Query Parameters**:
- `page`, `size`, `sort` (standard pagination)

**Success Response**: `200 OK` - `Page<QuizGenerationStatus>`

**Error Responses**:
- `401` - Not authenticated

---

#### 26. Get Generation Statistics

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

#### 27. Export Quizzes

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
- **Cover page**: Generated timestamp (no redundant title)
- **Metadata blocks**: Quiz details, difficulty, time estimates
- **Answer key placement**: Separate pages or inline
- **Hints**: Warm yellow background with orange border for visibility
- **Explanations**: Blue background with darker blue border for clarity
- **Grouping**: Questions organized by type (MCQ, True/False, etc.)
- **Matching questions**: Clean 2-column grid layout with numbers (1, 2, 3...) on left and letters (A, B, C...) on right (no instruction text)
- **Ordering questions**: Items labeled with letters (A, B, C, D...)
- **Answer key formats**:
  - MATCHING: Number-letter combinations (e.g., `1 → A, 2 → B, 3 → C`)
  - ORDERING: Letter sequence (e.g., `C → A → D → B`)
  - COMPLIANCE: Statement positions (e.g., `Compliant: 1, 3, 5`)

**Error Responses**:
- `400` - Invalid format enum, invalid UUID format, validation error
- `401` - Unauthorized (for `scope=me` or `scope=all` without authentication)
- `403` - Forbidden (missing required permission for scope)
- `404` - No quizzes match the filters (returns empty result, not error)
- `429` - Rate limit exceeded

**Example Requests**:

**Public JSON export (anonymous)**:
```
GET /api/v1/quizzes/export?format=JSON_EDITABLE&scope=public
```

**User's quizzes in XLSX**:
```
GET /api/v1/quizzes/export?format=XLSX_EDITABLE&scope=me
Authorization: Bearer <token>
```

**Filtered PDF export with options**:
```
GET /api/v1/quizzes/export?format=PDF_PRINT&scope=me&difficulty=MEDIUM&tags=java&includeCover=true&includeHints=true&groupQuestionsByType=true
Authorization: Bearer <token>
```

**Specific quizzes HTML export**:
```
GET /api/v1/quizzes/export?format=HTML_PRINT&quizIds=uuid1&quizIds=uuid2&answersOnSeparatePages=true
Authorization: Bearer <token>
```

**Notes**:
- Response is streamed to prevent OOM for large exports
- Questions are ordered deterministically (by createdAt, then id)
- All filters are optional and can be combined
- Print options only apply to `HTML_PRINT` and `PDF_PRINT` formats
- JSON and XLSX formats preserve full data structure for round-trip import
- Filename includes timestamp and filter indicators for traceability

---

### Admin Operations

#### 28. Cleanup Stale Jobs

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

#### 29. Force Cancel Job

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

#### 30. Get Public Quizzes (No Auth Required)

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

### ProblemDetail Format

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Title must be between 3 and 100 characters",
  "instance": "/api/v1/quizzes"
}
```

### Common HTTP Status Codes

| Code | Meaning | When to Expect |
| --- | --- | --- |
| `400` | Bad Request | Validation errors, invalid data, malformed JSON |
| `401` | Unauthorized | Missing or invalid authentication token |
| `403` | Forbidden | Missing required permission or not resource owner |
| `404` | Not Found | Quiz, document, job, or other resource doesn't exist |
| `409` | Conflict | Duplicate operation, invalid state transition |
| `415` | Unsupported Media Type | Invalid file type in upload |
| `422` | Unprocessable Entity | Document/text processing failed |
| `429` | Too Many Requests | Rate limit exceeded |
| `500` | Internal Server Error | Unexpected server error |

### Common Error Scenarios

**Invalid Quiz Title**:
```json
{
  "status": 400,
  "detail": "Title must be between 3 and 100 characters"
}
```

**Rate Limit Exceeded**:
```json
{
  "status": 429,
  "detail": "Rate limit exceeded. Please try again in 30 seconds"
}
```
Headers: `Retry-After: 30`

**Unauthorized Visibility Change**:
```json
{
  "status": 403,
  "detail": "Only moderators can set quiz to PUBLIC visibility"
}
```

**Generation Job Not Complete**:
```json
{
  "status": 409,
  "detail": "Quiz generation job is still in progress. Please wait for completion."
}
```

**Unsupported File Type**:
```json
{
  "status": 415,
  "detail": "Unsupported file type: .exe. Supported types: pdf, docx, txt"
}
```

---

## Integration Guide

### Creating a Quiz

**Simple Quiz Creation**:
```javascript
const createQuiz = async () => {
  const response = await fetch('/api/v1/quizzes', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      title: 'My First Quiz',
      description: 'A test quiz',
      difficulty: 'EASY',
      visibility: 'PRIVATE',
      status: 'DRAFT',
      showHints: true,
      shuffleQuestions: false,
      timeLimitMinutes: 30
    })
  });
  
  if (response.ok) {
    const { quizId } = await response.json();
    console.log('Quiz created:', quizId);
    return quizId;
  }
};
```

---

### AI Generation Workflow

**Complete generation flow from document**:
```javascript
const generateQuiz = async (documentId) => {
  // 1. Start generation
  const startResponse = await fetch('/api/v1/quizzes/generate-from-document', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      documentId: documentId,
      quizScope: 'ENTIRE_DOCUMENT',
      title: 'Generated Quiz',
      questionsPerType: {
        'MCQ_SINGLE': 5,
        'TRUE_FALSE': 3
      },
      difficulty: 'MEDIUM',
      language: 'en'
    })
  });

  if (startResponse.status === 429) {
    const retryAfter = startResponse.headers.get('Retry-After');
    console.log(`Rate limited. Retry after ${retryAfter} seconds`);
    return;
  }

  const { jobId } = await startResponse.json();
  console.log('Generation started:', jobId);

  // 2. Poll for status
  const pollStatus = async () => {
    const statusResponse = await fetch(
      `/api/v1/quizzes/generation-status/${jobId}`,
      {
        headers: { 'Authorization': `Bearer ${token}` }
      }
    );
    
    const status = await statusResponse.json();
    console.log(`Progress: ${status.progressPercentage}%`);
    console.log(`Status: ${status.currentChunk}`);

    if (status.status === 'COMPLETED') {
      return status.generatedQuizId;
    } else if (status.status === 'FAILED') {
      throw new Error(status.errorMessage);
    } else if (status.status === 'CANCELLED') {
      throw new Error('Generation was cancelled');
    }

    // Still processing, poll again
    await new Promise(resolve => setTimeout(resolve, 3000));
    return pollStatus();
  };

  const quizId = await pollStatus();

  // 3. Fetch generated quiz
  const quizResponse = await fetch(
    `/api/v1/quizzes/generated-quiz/${jobId}`,
    {
      headers: { 'Authorization': `Bearer ${token}` }
    }
  );
  
  const quiz = await quizResponse.json();
  console.log('Generated quiz:', quiz);
  return quiz;
};
```

---

### Upload and Generate

**Generate quiz from uploaded file**:
```javascript
const uploadAndGenerate = async (file) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('title', 'Quiz from Upload');
  formData.append('description', 'Generated from uploaded document');
  formData.append('questionsPerType', JSON.stringify({
    'MCQ_SINGLE': 5,
    'TRUE_FALSE': 3
  }));
  formData.append('difficulty', 'MEDIUM');
  formData.append('chunkingStrategy', 'CHAPTER_BASED');
  formData.append('language', 'en');

  const response = await fetch('/api/v1/quizzes/generate-from-upload', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`
      // Don't set Content-Type - browser will set it with boundary
    },
    body: formData
  });

  if (response.status === 415) {
    const error = await response.json();
    console.error('Unsupported file type:', error.detail);
    return;
  }

  const { jobId } = await response.json();
  // Continue with polling as above
  return jobId;
};
```

---

### Listing with Caching

**Efficient list with ETag caching**:
```javascript
let cachedETag = null;

const listQuizzes = async () => {
  const headers = {
    'Authorization': `Bearer ${token}`
  };
  
  if (cachedETag) {
    headers['If-None-Match'] = cachedETag;
  }

  const response = await fetch(
    '/api/v1/quizzes?scope=me&page=0&size=20&sort=title,asc',
    { headers }
  );

  if (response.status === 304) {
    console.log('List unchanged, using cached data');
    return; // Use cached data
  }

  cachedETag = response.headers.get('ETag');
  const data = await response.json();
  return data;
};
```

---

### Publishing Workflow

**Submit quiz for review and publish**:
```javascript
const publishWorkflow = async (quizId) => {
  // 1. Submit for review
  await fetch(`/api/v1/quizzes/${quizId}/submit-for-review`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` }
  });
  console.log('Submitted for review');

  // 2. (After moderator approval) Change visibility to PUBLIC
  await fetch(`/api/v1/quizzes/${quizId}/visibility`, {
    method: 'PATCH',
    headers: {
      'Authorization': `Bearer ${moderatorToken}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ isPublic: true })
  });

  // 3. Publish
  await fetch(`/api/v1/quizzes/${quizId}/status`, {
    method: 'PATCH',
    headers: {
      'Authorization': `Bearer ${moderatorToken}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ status: 'PUBLISHED' })
  });
  
  console.log('Quiz published successfully');
};
```

---

### Bulk Operations

**Bulk update multiple quizzes**:
```javascript
const bulkUpdate = async (quizIds, updates) => {
  const response = await fetch('/api/v1/quizzes/bulk-update', {
    method: 'PATCH',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      quizIds: quizIds,
      updates: updates
    })
  });

  const result = await response.json();
  console.log('Successful:', result.successfulIds);
  console.log('Failed:', result.failures);
  
  // Handle partial failures
  Object.entries(result.failures).forEach(([quizId, error]) => {
    console.error(`Failed to update ${quizId}: ${error}`);
  });
};

// Usage
bulkUpdate(
  ['quiz-1', 'quiz-2', 'quiz-3'],
  { difficulty: 'HARD', showHints: false }
);
```

---

### Error Handling

**Comprehensive error handling**:
```javascript
const handleQuizOperation = async () => {
  try {
    const response = await fetch('/api/v1/quizzes/generate-from-text', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ /* request data */ })
    });

    if (!response.ok) {
      const error = await response.json();
      
      switch (response.status) {
        case 400:
          console.error('Validation error:', error.detail);
          // Show user-friendly validation message
          break;
        case 401:
          console.error('Unauthorized');
          // Redirect to login
          break;
        case 403:
          console.error('Forbidden:', error.detail);
          // Show permission error
          break;
        case 409:
          console.error('Conflict:', error.detail);
          // Handle state conflict
          break;
        case 429:
          const retryAfter = response.headers.get('Retry-After');
          console.log(`Rate limited. Retry after ${retryAfter}s`);
          // Implement backoff
          break;
        case 500:
          console.error('Server error');
          // Show generic error, maybe retry
          break;
      }
      return;
    }

    const data = await response.json();
    return data;
  } catch (error) {
    console.error('Network error:', error);
    // Handle network failures
  }
};
```

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
