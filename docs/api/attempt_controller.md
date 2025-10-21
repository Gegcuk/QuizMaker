# Attempt Controller API Reference

Complete frontend integration guide for `/api/v1/attempts` REST endpoints. This document is self-contained and includes all DTOs, validation rules, and error semantics needed to integrate without accessing backend code.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Enumerations](#enumerations)
- [Endpoints](#endpoints)
- [Error Handling](#error-handling)
- [Integration Guide](#integration-guide)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/v1/attempts`
* **Authentication**: Required for all endpoints. Uses JWT Bearer token in `Authorization` header.
* **Authorization Model**: Ownership-based. Users can only access their own attempts.
* **Content-Type**: `application/json` for requests and responses (except `204` responses)
* **Error Format**: All errors return `ErrorResponse` object (see Error Handling section)

---

## Authorization Matrix

Unlike admin endpoints which use permission-based authorization, attempt endpoints use **ownership-based authorization**. Users can only interact with their own quiz attempts.

| Capability | Endpoint(s) | Authorization Rule | Notes |
| --- | --- | --- | --- |
| **Start attempt** | `POST /quizzes/{quizId}` | User must be authenticated | Any authenticated user can start an attempt |
| **List own attempts** | `GET /` | User sees only their own attempts | Filtered by authenticated username |
| **View attempt details** | `GET /{attemptId}` | User must own the attempt | Returns 403 if not the owner |
| **Get current question** | `GET /{attemptId}/current-question` | User must own the attempt | Returns 403 if not the owner |
| **Submit answers** | `POST /{attemptId}/answers`, `POST /{attemptId}/answers/batch` | User must own the attempt | Returns 403 if not the owner |
| **Complete attempt** | `POST /{attemptId}/complete` | User must own the attempt | Returns 403 if not the owner |
| **View stats** | `GET /{attemptId}/stats` | User must own the attempt | Returns 403 if not the owner |
| **Pause/Resume** | `POST /{attemptId}/pause`, `POST /{attemptId}/resume` | User must own the attempt | Returns 403 if not the owner |
| **Delete attempt** | `DELETE /{attemptId}` | User must own the attempt | Returns 403 if not the owner |
| **Review attempt** | `GET /{attemptId}/review`, `GET /{attemptId}/answer-key` | User must own the attempt | Returns 403 if not the owner |
| **Get shuffled questions** | `GET /quizzes/{quizId}/questions/shuffled` | User must have access to quiz | Quiz visibility rules apply |

**Admin Overrides**:
- Admin users with appropriate permissions may access all attempts (implementation-specific)
- Use admin endpoints for cross-user attempt management

**Quiz Access**:
- Starting an attempt requires read access to the quiz
- Quiz visibility (public/private) is enforced at quiz level
- Deleted or closed quizzes cannot be attempted

---

## Request DTOs

### StartAttemptRequest

**Used by**: `POST /quizzes/{quizId}`

Optional request body to configure attempt mode.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `mode` | `AttemptMode` enum | No | Must be one of: `ONE_BY_ONE`, `ALL_AT_ONCE`, `TIMED` | Defaults to `ALL_AT_ONCE` if omitted |


---

### AnswerSubmissionRequest

**Used by**: `POST /{attemptId}/answers`

Submit a single answer for a question.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `questionId` | UUID string | Yes | Must be valid UUID | ID of the question being answered |
| `response` | JSON object | Yes | Non-null, structure depends on `QuestionType` | User's answer payload |

**Response payload structure by QuestionType**:

| Question Type | Response Field | Type | Example Value |
| --- | --- | --- | --- |
| `MCQ_SINGLE` | `selectedOptionId` | integer | `1` |
| `MCQ_MULTI` | `selectedOptionIds` | array of integers | `[1, 3, 4]` |
| `TRUE_FALSE` | `answer` | boolean | `true` |
| `OPEN` | `answer` | string | `"User's text answer"` |
| `FILL_GAP` | `gaps` | object (map) | `{"0": "answer1", "1": "answer2"}` |
| `ORDERING` | `orderedItems` | array of integers | `[3, 1, 4, 2]` (item IDs in user's order) |
| `MATCHING` | `pairs` | object (map) | `{"1": "A", "2": "C"}` (left ID → right ID) |
| `COMPLIANCE` | `statements` | object (map) | `{"1": true, "2": false}` (statement ID → compliant) |
| `HOTSPOT` | `selectedRegionIds` | array of integers | `[1, 3]` |

**MCQ_SINGLE**:
```json
{
  "questionId": "uuid-here",
  "response": {
    "selectedOptionId": 1
  }
}
```

**MCQ_MULTI**:
```json
{
  "questionId": "uuid-here",
  "response": {
    "selectedOptionIds": [1, 3, 4]
  }
}
```

**TRUE_FALSE**:
```json
{
  "questionId": "uuid-here",
  "response": {
    "answer": true
  }
}
```

**OPEN**:
```json
{
  "questionId": "uuid-here",
  "response": {
    "answer": "User's text answer"
  }
}
```

**FILL_GAP**:
```json
{
  "questionId": "uuid-here",
  "response": {
    "gaps": {
      "0": "first answer",
      "1": "second answer"
    }
  }
}
```

**ORDERING**:
```json
{
  "questionId": "uuid-here",
  "response": {
    "orderedItems": [3, 1, 4, 2]
  }
}
```

**MATCHING**:
```json
{
  "questionId": "uuid-here",
  "response": {
    "pairs": {
      "1": "A",
      "2": "C",
      "3": "B"
    }
  }
}
```

**COMPLIANCE**:
```json
{
  "questionId": "uuid-here",
  "response": {
    "statements": {
      "1": true,
      "2": false,
      "3": true
    }
  }
}
```

**HOTSPOT**:
```json
{
  "questionId": "uuid-here",
  "response": {
    "selectedRegionIds": [1, 3]
  }
}
```

---

### BatchAnswerSubmissionRequest

**Used by**: `POST /{attemptId}/answers/batch`

Submit multiple answers at once. Only valid for `ALL_AT_ONCE` mode.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `answers` | Array of `AnswerSubmissionRequest` | Yes | Must be non-empty array | List of answer submissions |


---

## Response DTOs

### StartAttemptResponse

**Returned by**: `POST /quizzes/{quizId}`

| Field | Type | Description |
| --- | --- | --- |
| `attemptId` | UUID string | Unique identifier for this attempt |
| `quizId` | UUID string | ID of the quiz being attempted |
| `mode` | `AttemptMode` enum | Selected attempt mode |
| `totalQuestions` | integer | Number of questions in this quiz |
| `timeLimitMinutes` | integer (nullable) | Time limit in minutes (null if no limit) |
| `startedAt` | ISO 8601 datetime | When the attempt was started |


**Note**: Does not include question content. Call `GET /{attemptId}/current-question` to fetch the first question.

---

### AttemptDto

**Returned by**: `GET /`, `POST /{attemptId}/pause`, `POST /{attemptId}/resume`

| Field | Type | Description |
| --- | --- | --- |
| `attemptId` | UUID | Unique identifier |
| `quizId` | UUID | Associated quiz ID |
| `userId` | UUID | User who created this attempt |
| `startedAt` | ISO 8601 datetime | Start timestamp |
| `status` | `AttemptStatus` enum | Current status |
| `mode` | `AttemptMode` enum | Attempt mode |


---

### AttemptDetailsDto

**Returned by**: `GET /{attemptId}`

| Field | Type | Description |
| --- | --- | --- |
| `attemptId` | UUID | Unique identifier |
| `quizId` | UUID | Associated quiz ID |
| `userId` | UUID | User who created this attempt |
| `startedAt` | ISO 8601 datetime | Start timestamp |
| `completedAt` | ISO 8601 datetime (nullable) | Completion timestamp |
| `status` | `AttemptStatus` enum | Current status |
| `mode` | `AttemptMode` enum | Attempt mode |
| `answers` | Array of `AnswerSubmissionDto` | All submitted answers |

---

### CurrentQuestionDto

**Returned by**: `GET /{attemptId}/current-question`

| Field | Type | Description |
| --- | --- | --- |
| `question` | `QuestionForAttemptDto` | Question details (see below) |
| `questionNumber` | integer | Current question number (1-indexed) |
| `totalQuestions` | integer | Total number of questions |
| `attemptStatus` | `AttemptStatus` enum | Current attempt status |

---

### QuestionForAttemptDto

**Embedded in**: `CurrentQuestionDto`, `AnswerSubmissionDto`, `AnswerReviewDto`

Safe question representation without correct answers.

| Field | Type | Description |
| --- | --- | --- |
| `questionId` | UUID string | Question identifier |
| `type` | `QuestionType` enum | Question type |
| `difficulty` | `Difficulty` enum | Difficulty level (`EASY`, `MEDIUM`, `HARD`) |
| `questionText` | string | Question prompt |
| `hint` | string (nullable) | Optional hint text |
| `attachmentUrl` | string (nullable) | URL to image/media attachment |
| `content` | JSON object | Type-specific content (options, items, etc.) - see Content Structures below |

**Content structure by type**:

**MCQ_SINGLE / MCQ_MULTI**:
```json
{
  "options": [
    { "id": 1, "text": "Option A" },
    { "id": 2, "text": "Option B" },
    { "id": 3, "text": "Option C" }
  ]
}
```

**FILL_GAP**:
```json
{
  "text": "The capital of France is {0} and it's located in {1}.",
  "gapCount": 2
}
```

**ORDERING**:
```json
{
  "items": [
    { "id": 1, "text": "Step 1" },
    { "id": 2, "text": "Step 2" },
    { "id": 3, "text": "Step 3" }
  ]
}
```

**MATCHING**:
```json
{
  "leftItems": [
    { "id": 1, "text": "Term 1" },
    { "id": 2, "text": "Term 2" }
  ],
  "rightItems": [
    { "id": "A", "text": "Definition A" },
    { "id": "B", "text": "Definition B" }
  ]
}
```

**HOTSPOT**:
```json
{
  "imageUrl": "https://example.com/image.png",
  "regions": [
    { "id": 1, "x": 100, "y": 150, "width": 50, "height": 30 },
    { "id": 2, "x": 200, "y": 100, "width": 60, "height": 40 }
  ]
}
```

**COMPLIANCE**:
```json
{
  "statements": [
    { "id": 1, "text": "Statement A" },
    { "id": 2, "text": "Statement B" }
  ]
}
```

---

### AnswerSubmissionDto

**Returned by**: `POST /{attemptId}/answers`, `POST /{attemptId}/answers/batch`

| Field | Type | Description |
| --- | --- | --- |
| `answerId` | UUID | Answer record identifier |
| `questionId` | UUID | Question identifier |
| `isCorrect` | boolean (nullable) | Whether answer is correct (null for OPEN) |
| `score` | double | Score awarded for this answer |
| `answeredAt` | ISO 8601 datetime | Submission timestamp |
| `nextQuestion` | `QuestionForAttemptDto` (nullable) | Next question (only in ONE_BY_ONE mode) |

---

### AttemptResultDto

**Returned by**: `POST /{attemptId}/complete`

| Field | Type | Description |
| --- | --- | --- |
| `attemptId` | UUID | Attempt identifier |
| `quizId` | UUID | Quiz identifier |
| `userId` | UUID | User identifier |
| `startedAt` | ISO 8601 datetime | Start timestamp |
| `completedAt` | ISO 8601 datetime | Completion timestamp |
| `totalScore` | double | Final score achieved |
| `correctCount` | integer | Number of correct answers |
| `totalQuestions` | integer | Total number of questions |
| `answers` | Array of `AnswerSubmissionDto` | All submitted answers |

---

### AttemptStatsDto

**Returned by**: `GET /{attemptId}/stats`

| Field | Type | Description |
| --- | --- | --- |
| `attemptId` | UUID | Attempt identifier |
| `totalTime` | Duration (ISO 8601) | Total time spent (e.g., "PT15M30S") |
| `averageTimePerQuestion` | Duration (ISO 8601) | Average time per question |
| `questionsAnswered` | integer | Questions answered |
| `correctAnswers` | integer | Correct answer count |
| `accuracyPercentage` | double | Accuracy (0-100) |
| `completionPercentage` | double | Completion (0-100) |
| `questionTimings` | Array of `QuestionTimingStatsDto` | Per-question timing |
| `startedAt` | ISO 8601 datetime | Start timestamp |
| `completedAt` | ISO 8601 datetime (nullable) | Completion timestamp |

---

### AttemptReviewDto

**Returned by**: `GET /{attemptId}/review`, `GET /{attemptId}/answer-key`

| Field | Type | Description |
| --- | --- | --- |
| `attemptId` | UUID string | Attempt identifier |
| `quizId` | UUID string | Quiz identifier |
| `quizTitle` | string | Quiz title |
| `score` | integer | Final score |
| `maxScore` | integer | Maximum score |
| `percentageScore` | number | Score percentage |
| `completedAt` | ISO 8601 datetime | Completion timestamp |
| `answers` | Array of `AnswerReviewDto` | Detailed answer review |

**AnswerReviewDto** structure:

| Field | Type | Description |
| --- | --- | --- |
| `question` | `QuestionForAttemptDto` | Question details |
| `userResponse` | JSON object (nullable) | User's answer (null in answer-key endpoint) |
| `correctAnswer` | JSON object | Correct answer payload |
| `isCorrect` | boolean (nullable) | Whether user was correct (null for OPEN) |
| `scoreAwarded` | integer | Points awarded |
| `maxScore` | integer | Maximum points for this question |
| `explanation` | string (nullable) | Answer explanation |

---

## Enumerations

### AttemptMode

| Value | Description |
| --- | --- |
| `ONE_BY_ONE` | Present questions one at a time, user can only see current question |
| `ALL_AT_ONCE` | Display all questions at once, user can submit batch answers |
| `TIMED` | Timed quiz with countdown timer |

### AttemptStatus

| Value | Description |
| --- | --- |
| `IN_PROGRESS` | Attempt is active |
| `PAUSED` | Attempt temporarily paused |
| `COMPLETED` | Attempt finished successfully |
| `ABANDONED` | Attempt abandoned without completion |

### QuestionType

| Value | Description |
| --- | --- |
| `MCQ_SINGLE` | Multiple choice with single correct answer |
| `MCQ_MULTI` | Multiple choice with multiple correct answers |
| `TRUE_FALSE` | True/False question |
| `OPEN` | Open-ended text answer |
| `FILL_GAP` | Fill in the blank(s) |
| `ORDERING` | Put items in correct order |
| `MATCHING` | Match items from two lists |
| `COMPLIANCE` | Mark statements as compliant/non-compliant |
| `HOTSPOT` | Click correct regions on an image |

### Difficulty

| Value | Description |
| --- | --- |
| `EASY` | Easy difficulty |
| `MEDIUM` | Medium difficulty |
| `HARD` | Hard difficulty |

---

## Endpoints

### 1. Start Attempt

```
POST /api/v1/attempts/quizzes/{quizId}
```

**Parameters**:
- `{quizId}` - UUID of the quiz (path parameter)

**Request Body**: `StartAttemptRequest` (optional)

**Success Response**: `201 Created`
```json
{
  "attemptId": "uuid",
  "quizId": "uuid",
  "mode": "ALL_AT_ONCE",
  "totalQuestions": 10,
  "timeLimitMinutes": null,
  "startedAt": "2024-01-15T10:30:00Z"
}
```

**Error Responses**:
- `404` - Quiz not found
- `403` - Access denied
- `422` - Quiz is closed or not available

---

### 2. List Attempts

```
GET /api/v1/attempts/
```

**Query Parameters**:
- `page` (integer, optional) - Page number (0-indexed), default: 0
- `size` (integer, optional) - Page size (1-100), default: 20
- `quizId` (UUID, optional) - Filter by quiz
- `userId` (UUID, optional) - Filter by user (admin only)
- `sort` (string, optional) - Sort field, default: `startedAt,desc`

**Success Response**: `200 OK`
```json
{
  "content": [ /* Array of AttemptDto */ ],
  "totalElements": 42,
  "totalPages": 3,
  "number": 0,
  "size": 20
}
```

**Error Responses**:
- `400` - Invalid query parameters

---

### 3. Get Attempt Details

```
GET /api/v1/attempts/{attemptId}
```

**Success Response**: `200 OK` - `AttemptDetailsDto`

**Error Responses**:
- `404` - Attempt not found
- `403` - Not the attempt owner

---

### 4. Get Current Question

```
GET /api/v1/attempts/{attemptId}/current-question
```

**Success Response**: `200 OK` - `CurrentQuestionDto`

**Error Responses**:
- `404` - Attempt not found
- `409` - Attempt already completed or no questions remaining
- `422` - Invalid attempt state

---

### 5. Submit Answer

```
POST /api/v1/attempts/{attemptId}/answers
```

**Request Body**: `AnswerSubmissionRequest`

**Success Response**: `200 OK` - `AnswerSubmissionDto`

**Error Responses**:
- `400` - Invalid answer format
- `404` - Attempt or question not found
- `403` - Not the attempt owner

---

### 6. Submit Batch Answers

```
POST /api/v1/attempts/{attemptId}/answers/batch
```

**Request Body**: `BatchAnswerSubmissionRequest`

**Success Response**: `200 OK` - Array of `AnswerSubmissionDto`

**Error Responses**:
- `400` - Invalid payload
- `404` - Attempt not found
- `409` - Attempt mode is not `ALL_AT_ONCE` or duplicate answers detected

---

### 7. Complete Attempt

```
POST /api/v1/attempts/{attemptId}/complete
```

**Success Response**: `200 OK` - `AttemptResultDto`

**Error Responses**:
- `404` - Attempt not found
- `409` - Cannot complete (e.g., unanswered questions)

---

### 8. Get Attempt Statistics

```
GET /api/v1/attempts/{attemptId}/stats
```

**Success Response**: `200 OK` - `AttemptStatsDto`

**Error Responses**:
- `404` - Attempt not found
- `403` - Access denied

---

### 9. Pause Attempt

```
POST /api/v1/attempts/{attemptId}/pause
```

**Success Response**: `200 OK` - `AttemptDto`

**Error Responses**:
- `404` - Attempt not found
- `409` - Attempt cannot be paused (already completed)

---

### 10. Resume Attempt

```
POST /api/v1/attempts/{attemptId}/resume
```

**Success Response**: `200 OK` - `AttemptDto`

**Error Responses**:
- `404` - Attempt not found
- `409` - Attempt is not paused

---

### 11. Delete Attempt

```
DELETE /api/v1/attempts/{attemptId}
```

**Success Response**: `204 No Content`

**Error Responses**:
- `404` - Attempt not found
- `403` - Not the attempt owner

---

### 12. Get Shuffled Questions

```
GET /api/v1/attempts/quizzes/{quizId}/questions/shuffled
```

Fetch questions before starting an attempt (for preview or client-side rendering).

**Success Response**: `200 OK` - Array of `QuestionForAttemptDto`

**Error Responses**:
- `404` - Quiz not found
- `403` - Access denied

---

### 13. Get Attempt Review

```
GET /api/v1/attempts/{attemptId}/review
```

**Query Parameters**:
- `includeUserAnswers` (boolean, optional) - Include user's answers, default: true
- `includeCorrectAnswers` (boolean, optional) - Include correct answers, default: true
- `includeQuestionContext` (boolean, optional) - Include question details, default: true

**Success Response**: `200 OK` - `AttemptReviewDto`

**Error Responses**:
- `404` - Attempt not found
- `403` - Not the attempt owner
- `409` - Attempt not yet completed

---

### 14. Get Answer Key

```
GET /api/v1/attempts/{attemptId}/answer-key
```

Convenience endpoint that returns review with correct answers only (no user responses).

**Success Response**: `200 OK` - `AttemptReviewDto` (userResponse fields are null)

**Error Responses**:
- `404` - Attempt not found
- `403` - Not the attempt owner
- `409` - Attempt not yet completed

---

## Error Handling

All errors return an `ErrorResponse` object:

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "details": ["Attempt not found with ID: uuid-here"]
}
```

**Common HTTP Status Codes**:

| Code | Meaning | When to expect |
| --- | --- | --- |
| `400` | Bad Request | Validation errors, malformed JSON, invalid enums |
| `401` | Unauthorized | Missing or invalid authentication token |
| `403` | Forbidden | User doesn't own the resource |
| `404` | Not Found | Attempt, quiz, or question doesn't exist |
| `409` | Conflict | Attempt state conflict (e.g., reviewing incomplete attempt) |
| `422` | Unprocessable Entity | Invalid state transition (e.g., getting current question after completion) |
| `500` | Internal Server Error | Unexpected server error |

---


# Integration Guide

### Starting an Attempt Flow

1. **Start attempt**: `POST /quizzes/{quizId}` with optional mode
2. **Get first question**: `GET /{attemptId}/current-question`
3. **Submit answers**:
    - ONE_BY_ONE: `POST /{attemptId}/answers` for each question
    - ALL_AT_ONCE: Collect all answers, then `POST /{attemptId}/answers/batch`
4. **Complete attempt**: `POST /{attemptId}/complete`
5. **View results**: `GET /{attemptId}/review`

### Pause/Resume Flow

- **Pause**: `POST /{attemptId}/pause` (status becomes `PAUSED`)
- **Resume**: `POST /{attemptId}/resume` (status returns to `IN_PROGRESS`)
- **Get current position**: `GET /{attemptId}/current-question`

### Review Features

- **Full review with user answers**: `GET /{attemptId}/review`
- **Answer key only**: `GET /{attemptId}/answer-key`
- **Control visibility**: Use query parameters to show/hide user answers and correct answers

### Error Handling Best Practices

1. Always check `status` code in `ErrorResponse`
2. Display `details` array messages to users (they are user-friendly)
3. Handle 409/422 with clear UI messaging (e.g., "Complete the quiz before viewing review")
4. For batch submissions, check for partial success in error details
5. Implement retry logic for 500 errors

### Validation Tips

- Validate `questionId` format (UUID) before submission
- Ensure `response` structure matches question type
- For MCQ_MULTI, send array even if only one option selected
- For ORDERING, send complete array of all item IDs in desired order
- For batch submissions, ensure no duplicate `questionId` values

---

## Security Considerations

### Ownership Enforcement

1. **Attempt Isolation**: Users can only access their own attempts
2. **Automatic Filtering**: List endpoints automatically filter by authenticated user
3. **Explicit Checks**: Individual attempt operations validate ownership before processing
4. **403 Responses**: Non-owners receive `403 Forbidden` instead of `404 Not Found` to prevent enumeration

### Token Security

1. **Token Expiry**: Handle token expiration and implement refresh logic
2. **Secure Storage**: Store JWT tokens securely (HttpOnly cookies or secure storage)
3. **Token Validation**: All endpoints validate token signature and expiration
4. **Logout Cleanup**: Clear tokens on logout to prevent reuse

### Answer Integrity

1. **Server-Side Validation**: All answer formats are validated server-side
2. **Timing Protection**: Server timestamps all submissions to prevent tampering
3. **Correct Answer Privacy**: Correct answers are never sent to client during active attempts
4. **Review Access Control**: Review endpoint only accessible after attempt completion

### Quiz Access Control

1. **Quiz Visibility**: Starting attempts respects quiz public/private status
2. **Quiz Availability**: Closed or deleted quizzes cannot be attempted
3. **Question Security**: Shuffled questions endpoint filters based on quiz access
4. **Admin Separation**: Use dedicated admin endpoints for cross-user access

### State Management

1. **Status Transitions**: Server enforces valid state transitions
2. **Completion Protection**: Once completed, attempts cannot be modified
3. **Pause/Resume Guards**: Only `IN_PROGRESS` attempts can be paused
4. **Idempotency**: Multiple identical submissions are handled safely

### Data Privacy

1. **No User Data Leakage**: Attempt responses don't expose other users' data
2. **Stats Privacy**: Statistics only available to attempt owner
3. **Review Controls**: Use query parameters to control sensitive data exposure
4. **Minimal Exposure**: Error messages don't reveal system internals

### Best Practices for Implementation

**Frontend**:
- Never cache correct answers client-side during active attempts
- Implement token refresh before expiration
- Clear sensitive data on logout
- Validate user ownership before rendering attempt data
- Use HTTPS for all API calls

**Mobile Apps**:
- Store tokens in secure storage (Keychain/Keystore)
- Implement certificate pinning for API endpoints
- Clear attempt data from memory after completion
- Handle background/foreground transitions securely

**Testing**:
- Test ownership checks with different user accounts
- Verify correct answers are not leaked in API responses
- Test token expiration handling
- Verify state transition guards work correctly

