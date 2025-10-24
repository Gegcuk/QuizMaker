# QuestionController API Spec

Base path: `/api/v1/questions`\
Content type: `application/json`

## Auth

| Operation | Auth | Permissions |
| ---------- | ---- | ----------- |
| List / fetch questions | JWT | Authenticated user (ownership/moderation enforced in service) |
| Create question | JWT | `QUESTION_CREATE` |
| Update question | JWT | `QUESTION_UPDATE` |
| Delete question | JWT | `QUESTION_DELETE` |

---

## DTOs

**QuestionDto**

```ts
{
  id: string;
  type: 'MCQ_SINGLE' | 'MCQ_MULTI' | 'TRUE_FALSE' | 'OPEN' | 'FILL_GAP' | 'ORDERING' | 'MATCHING' | 'COMPLIANCE' | 'HOTSPOT';
  difficulty: 'EASY' | 'MEDIUM' | 'HARD';
  questionText: string;
  content: Record<string, unknown>;      // type-specific structure
  hint?: string | null;
  explanation?: string | null;
  attachmentUrl?: string | null;
  createdAt: string;
  updatedAt: string;
  quizIds: string[];
  tagIds: string[];
}
```

**CreateQuestionRequest / UpdateQuestionRequest**

```ts
type CreateQuestionRequest = {
  type: QuestionType;          // required
  difficulty: Difficulty;      // required
  questionText: string;        // 3-1000 chars
  content: Record<string, unknown>; // required; validated per handler
  hint?: string | null;        // ≤500 chars
  explanation?: string | null; // ≤2000 chars
  attachmentUrl?: string | null; // ≤2048 chars
  quizIds?: string[];          // optional associations
  tagIds?: string[];
};

type UpdateQuestionRequest = CreateQuestionRequest;
```

**CreateQuestionResponse**

```ts
{ questionId: string; }
```

**ErrorResponse**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
```

---

## Endpoints

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| GET | `/` | – | `Page<QuestionDto>` | JWT | Query params: `quizId`, `page`, `size`; sorted by `createdAt DESC` |
| GET | `/{questionId}` | – | `QuestionDto` | JWT | Ownership/moderator enforcement in service |
| POST | `/` | `CreateQuestionRequest` | `{ questionId: string; }` | `QUESTION_CREATE` | Requires caller to own referenced quizzes |
| PATCH | `/{questionId}` | `UpdateQuestionRequest` | `QuestionDto` | `QUESTION_UPDATE` | Returns updated question |
| DELETE | `/{questionId}` | – | 204 | `QUESTION_DELETE` | Removes question and associations |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 400 | Validation error | Invalid payload, missing content, handler validation failures |
| 401 | Unauthorized | Missing/invalid JWT |
| 403 | Forbidden | Caller lacks permission or quiz ownership |
| 404 | Not found | Question (or referenced quiz/tag) missing |
| 409 | Conflict | Persistence conflicts (e.g., duplicate identifiers) |
| 500 | Server error | Unexpected failure |

---

## Validation Summary

- `questionText` required, 3–1000 characters.
- `content` must match the schema enforced by the handler for `QuestionType`; handler throws `ValidationException` on mismatch.
- Optional text fields (`hint`, `explanation`) respect length limits; `attachmentUrl` validated for max length.
- Association lists (`quizIds`, `tagIds`) must contain UUID strings; service enforces caller ownership of referenced quizzes.

---

## Notes for Agents

- Always include `Authorization: Bearer <jwt>`.
- Fetch questions with pagination; `size` defaults to 20.
- When creating/updating, build `content` according to the selected `QuestionType` (multiple-choice, ordering, etc.).
- Handle 403s by informing users they lack ownership or permission for the referenced quiz.
