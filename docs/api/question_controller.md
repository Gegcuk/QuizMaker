# Question Controller API Reference

Endpoints for managing questions under `/api/v1/questions`. All endpoints require authentication; write operations enforce fine-grained permissions.

## Overview
- **Base path**: `/api/v1/questions`
- **Authentication**: Required (`Authorization: Bearer <JWT>`)
- **Authorization**:
  - `QUESTION_CREATE` to create questions
  - `QUESTION_UPDATE` to modify questions
  - `QUESTION_DELETE` to delete questions
  - Read endpoints rely on ownership/moderator rules enforced in `QuestionService`
- **Content types**: JSON request/response
- **Pagination**: `GET /api/v1/questions` accepts `page`, `size`; results sorted by `createdAt DESC`
- **Error schema**: `ErrorResponse` (`timestamp`, `status`, `error`, `details`)

## Authorization Matrix
| Operation | Method & Path | Permission Requirement | Notes |
| --- | --- | --- | --- |
| List questions | `GET /api/v1/questions` | Authenticated | Optional `quizId` filter |
| Fetch question | `GET /api/v1/questions/{id}` | Authenticated | Ownership/moderator checks |
| Create question | `POST /api/v1/questions` | `QUESTION_CREATE` | Returns new `questionId` |
| Update question | `PATCH /api/v1/questions/{id}` | `QUESTION_UPDATE` | Full update |
| Delete question | `DELETE /api/v1/questions/{id}` | `QUESTION_DELETE` | No body returned |

## DTOs
### CreateQuestionRequest / UpdateQuestionRequest
| Field | Type | Required | Constraints / Notes |
| --- | --- | --- | --- |
| `type` | enum `QuestionType` | yes | See [Question Type Content](#question-type-content) |
| `difficulty` | enum `Difficulty` (`EASY`, `MEDIUM`, `HARD`) | yes | |
| `questionText` | string | yes | 3–1000 characters |
| `content` | JSON object | yes | Structure varies by `type` |
| `hint` | string | no | Max 500 chars |
| `explanation` | string | no | Max 2000 chars |
| `attachmentUrl` | string | no | Valid URL ≤ 2048 chars |
| `quizIds` | array<UUID> | no | Quiz associations; create defaults to empty list |
| `tagIds` | array<UUID> | no | Tag associations; create defaults to empty list |

### QuestionDto
| Field | Type | Notes |
| --- | --- | --- |
| `id` | UUID | Question identifier |
| `type` | `QuestionType` | Question category |
| `difficulty` | `Difficulty` | |
| `questionText` | string | Display text |
| `content` | JSON object | Normalized question content |
| `hint` / `explanation` | string \| null | Optional support text |
| `attachmentUrl` | string \| null | External media |
| `quizIds` | array<UUID> | Associated quizzes |
| `tagIds` | array<UUID> | Associated tags |
| `createdAt` / `updatedAt` | ISO timestamp | Audit metadata |

### Create/Update Responses
- `POST /api/v1/questions` → `201 Created` with `{ "questionId": "<UUID>" }`
- `PATCH /api/v1/questions/{id}` → `200 OK` with updated `QuestionDto`

## Question Type Content
| QuestionType | Required `content` structure | Notes |
| --- | --- | --- |
| `MCQ_SINGLE` | `options` array (≥ 2) with unique string `id`, `text`, and exactly one `correct: true` | Responses expect `selectedOptionId` |
| `MCQ_MULTI` | `options` array (≥ 2) with unique `id`, `text`, `correct` flags (≥ 1 true) | Responses expect `selectedOptionIds` array |
| `TRUE_FALSE` | Object with boolean `answer` | Responses expect `answer` boolean |
| `OPEN` | Object with string `expectedAnswer` or richer evaluation metadata | Free-text answer; scoring handled downstream |
| `FILL_GAP` | Object with `text` containing placeholders and `gaps` array with `index`, `answer` | Responses expect `gaps` map of indexes to answers |
| `ORDERING` | Object with `items` array (each with `id`, `text`) representing correct order | Responses expect `orderedItems` array of ids |
| `MATCHING` | Object with `pairs` array; each entry includes `leftId`, `leftText`, `rightId`, `rightText`, `correctRightId` | Responses expect `pairs` map (`leftId` → `rightId`) |
| `COMPLIANCE` | Object with `statements` array (`id`, `text`, `expected` boolean) | Responses expect `statements` map (`id` → boolean) |
| `HOTSPOT` | Object describing `imageUrl` and `regions` array (`id`, shape metadata, `correct` flag) | Responses expect `selectedRegionIds` array |

## Endpoint Summary
| Method | Path | Description | Request Body | Success |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/questions` | Paginated question list (optional quiz filter) | – | `200 OK` → `Page<QuestionDto>` |
| GET | `/api/v1/questions/{id}` | Retrieve question by id | – | `200 OK` → `QuestionDto` |
| POST | `/api/v1/questions` | Create new question | `CreateQuestionRequest` | `201 Created` |
| PATCH | `/api/v1/questions/{id}` | Update question | `UpdateQuestionRequest` | `200 OK` |
| DELETE | `/api/v1/questions/{id}` | Delete question | – | `204 No Content` |

## Endpoint Details
### GET /api/v1/questions
- Query parameters:
  - `quizId` (optional UUID) – filter questions belonging to a quiz the caller can access.
  - `page` (default 0), `size` (default 20).
- Returns results sorted by `createdAt` descending.
- Service enforces ownership/permission checks; moderators/admins may see broader data.

### GET /api/v1/questions/{id}
- Path parameter `id` (UUID).
- Returns `404 Not Found` when the user lacks access or the question does not exist.

### POST /api/v1/questions
- Requires `QUESTION_CREATE` permission.
- Validates `content` via type-specific handlers.
- Associates question with provided `quizIds` and `tagIds` if caller has access; unauthorized associations raise `403/404`.

### PATCH /api/v1/questions/{id}
- Requires `QUESTION_UPDATE` permission.
- Full replacement: include all fields even for minor edits.
- Returns updated `QuestionDto`; content is revalidated.

### DELETE /api/v1/questions/{id}
- Requires `QUESTION_DELETE` permission.
- `204 No Content` on success.
- Returns `404 Not Found` if question unavailable or inaccessible.

## Error Handling
| Status | When Returned |
| --- | --- |
| `400 Bad Request` | Validation failures, including question type handler rejections |
| `401 Unauthorized` | Missing/invalid JWT |
| `403 Forbidden` | Lacking necessary permission or ownership |
| `404 Not Found` | Question not found or not accessible |
| `409 Conflict` | Underlying data integrity conflict |
| `500 Internal Server Error` | Unexpected failure |

## Integration Notes
- Reuse frontend form types that mirror `QuestionType` content schema; avoid sending extraneous fields to keep validations simple.
- Normalize option identifiers client-side to stable strings before create/update calls.
- After mutations, refresh associated quiz/question lists to reflect new content order; responses do not include paging context.
- Handle `403`/`404` uniformly by messaging users that they lack access or the resource was removed.
