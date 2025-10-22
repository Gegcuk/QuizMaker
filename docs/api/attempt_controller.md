# AttemptController API Spec

Base path: `/api/v1/attempts`\
Content type: `application/json`

## Auth

| Operation | Auth | Roles/Permissions |
| ---------- | ---- | ----------------- |
| All endpoints | JWT | Authenticated user; ownership enforced server-side |

---

## DTOs

**StartAttemptRequest / Response**

```ts
type StartAttemptRequest = {
  mode?: 'ONE_BY_ONE' | 'ALL_AT_ONCE' | 'TIMED';
};

type StartAttemptResponse = {
  attemptId: string;
  firstQuestion?: CurrentQuestionDto | null;
};
```

**AttemptDto** (partial)

```ts
{
  id: string;
  quizId: string;
  status: 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED';
  mode: 'ONE_BY_ONE' | 'ALL_AT_ONCE' | 'TIMED';
  startedAt: string;
  completedAt?: string | null;
  score?: number | null;
}
```

**AnswerSubmissionRequest / BatchAnswerSubmissionRequest**

```ts
type AnswerSubmissionRequest = {
  questionId: string;
  response: Record<string, unknown>; // structure depends on QuestionType
};

type BatchAnswerSubmissionRequest = {
  submissions: AnswerSubmissionRequest[];
};
```

**AnswerSubmissionDto**

```ts
{
  answerId: string;
  questionId: string;
  isCorrect?: boolean | null;
  score?: number | null;
  answeredAt: string;
  nextQuestion?: QuestionForAttemptDto | null; // only for ONE_BY_ONE
}
```

**AttemptReviewDto**

```ts
{
  attemptId: string;
  quizId: string;
  status: string;
  answers: Array<{
    questionId: string;
    userAnswer?: Record<string, unknown> | null;
    correctAnswer?: Record<string, unknown> | null;
    questionContext?: Record<string, unknown> | null;
    isCorrect?: boolean | null;
    score?: number | null;
  }>;
}
```

**AttemptStatsDto / AttemptResultDto / CurrentQuestionDto / QuestionForAttemptDto**

Provided by attempt API DTO package; include timing, per-question metrics, etc.

**ErrorResponse**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
```

---

## Endpoints

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| POST | `/quizzes/{quizId}` | `StartAttemptRequest?` | `StartAttemptResponse` | JWT | Defaults mode to `ALL_AT_ONCE` |
| GET | `/` | – | `Page<AttemptDto>` | JWT | Filters: `page`, `size`, `quizId`, `userId`; caller receives own data (mods handled in service) |
| GET | `/{attemptId}` | – | `AttemptDto` | JWT | Owner-only |
| GET | `/{attemptId}/current-question` | – | `CurrentQuestionDto` | JWT | Active attempts only |
| POST | `/{attemptId}/answers` | `AnswerSubmissionRequest` | `AnswerSubmissionDto` | JWT | Owner-only; returns next question if ONE_BY_ONE |
| POST | `/{attemptId}/answers/batch` | `BatchAnswerSubmissionRequest` | `AnswerSubmissionDto[]` | JWT | Owner-only; `ALL_AT_ONCE` mode |
| POST | `/{attemptId}/complete` | – | `AttemptResultDto` | JWT | Marks attempt complete |
| GET | `/{attemptId}/stats` | – | `AttemptStatsDto` | JWT | Completed attempts |
| POST | `/{attemptId}/pause` | – | `AttemptDto` | JWT | Only `IN_PROGRESS` attempts |
| POST | `/{attemptId}/resume` | – | `AttemptDto` | JWT | Only `PAUSED` attempts |
| DELETE | `/{attemptId}` | – | 204 | JWT | Owner-only delete |
| GET | `/quizzes/{quizId}/questions/shuffled` | – | `QuestionForAttemptDto[]` | JWT | Safe question payload for UI |
| GET | `/{attemptId}/review` | – | `AttemptReviewDto` | JWT | Query flags: `includeUserAnswers`, `includeCorrectAnswers`, `includeQuestionContext` (defaults true) |
| GET | `/{attemptId}/answer-key` | – | `AttemptReviewDto` | JWT | Correct answers only |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 400 | Validation error | Malformed request, invalid response payload |
| 401 | Unauthorized | Missing/invalid JWT |
| 403 | Forbidden | Attempt does not belong to caller |
| 404 | Not found | Attempt/quiz/question missing |
| 409 | Conflict | Invalid state (e.g., submitting after completion, pause/resume sequencing) |
| 422 | Unprocessable entity | Question handler rejected response (e.g., wrong option ids) |
| 500 | Server error | Unexpected failure |

---

## Validation Summary

- `StartAttemptRequest.mode` optional; defaults to `ALL_AT_ONCE` when omitted.
- Answer submissions must match question type schema; duplicates in batch submission return 409/422.
- State transitions enforced: only `IN_PROGRESS` attempts can pause/complete; only `PAUSED` attempts can resume.
- Reviews and answer keys allowed only for completed attempts; incomplete attempts return 409.

---

## Notes for Agents

- Always send `Authorization: Bearer <jwt>` and include the attempt UUID in path parameters.
- Batched submissions suit `ALL_AT_ONCE`; use per-question endpoint for step-by-step modes.
- For timed or proctored flows, poll `/{attemptId}/current-question` and `/{attemptId}/stats` judiciously to limit load.
- Use `/{attemptId}/review` for learner view and `/{attemptId}/answer-key` when only correct answers are needed.
