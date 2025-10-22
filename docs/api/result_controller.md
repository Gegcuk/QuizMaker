# Quiz Results & Leaderboard API Spec

Results endpoints are implemented inside the QuizController (`/api/v1/quizzes`).

Base path: `/api/v1/quizzes/{quizId}`\
Content type: `application/json`

## Auth

| Operation | Auth | Roles/Permissions |
| ---------- | ---- | ----------------- |
| GET `/results` | JWT | Quiz owner or moderator permissions enforced in service |
| GET `/leaderboard` | JWT | Access subject to quiz visibility and moderation rules |
| GET `/attempts` | JWT | Quiz owner only |
| GET `/attempts/{attemptId}/stats` | JWT | Quiz owner only |

---

## DTOs

**QuizResultSummaryDto**

```ts
{
  quizId: string;
  attemptsCount: number;
  averageScore: number | null;
  bestScore: number | null;
  worstScore: number | null;
  passRate: number | null;          // percentage 0-100
  questionStats: QuestionStatsDto[];
}
```

**QuestionStatsDto**

```ts
{ questionId: string; timesAsked: number; timesCorrect: number; correctRate: number; }
```

**LeaderboardEntryDto**

```ts
{ userId: string; username: string; bestScore: number | null; }
```

**AttemptDto / AttemptStatsDto**

See `attempt_controller.md` for full shape; reused here.

**ErrorResponse**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
```

---

## Endpoints

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| GET | `/results` | – | `QuizResultSummaryDto` | JWT | Aggregated metrics for quiz owner/moderator |
| GET | `/leaderboard` | – | `LeaderboardEntryDto[]` | JWT | Query `top` (default 10) |
| GET | `/attempts` | – | `AttemptDto[]` | JWT | Owner-only list of attempts |
| GET | `/attempts/{attemptId}/stats` | – | `AttemptStatsDto` | JWT | Owner-only view |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 401 | Unauthorized | Missing/invalid JWT |
| 403 | Forbidden | Caller lacks ownership/moderation rights |
| 404 | Not found | Quiz or attempt not accessible |
| 409 | Conflict | Attempt not completed when requesting stats/review |
| 500 | Server error | Unexpected failure |

---

## Notes for Agents

- Always include `Authorization: Bearer <jwt>`.
- Respect quiz visibility rules—service enforces owner/moderator checks; surface 403s with appropriate messaging.
- Use `top` query parameter on `/leaderboard` to limit results.
- For per-attempt analytics, combine `/attempts/{attemptId}/stats` with attempt details from the Attempt API.
