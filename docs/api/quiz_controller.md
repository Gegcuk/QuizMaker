# QuizController API Spec

Base path: `/api/v1/quizzes`\
Content types: `application/json`, `multipart/form-data` (AI upload), streamed files (export)

## Auth

| Operation group | Auth | Permissions |
| ---------------- | ---- | ----------- |
| Create quiz | JWT | `QUIZ_CREATE` |
| Update quiz (single/bulk) | JWT | `QUIZ_UPDATE` |
| Delete quiz | JWT | `QUIZ_DELETE` |
| Question/tag associations | JWT | `QUIZ_UPDATE` |
| Visibility/status changes, moderation review | JWT | `QUIZ_MODERATE` / `QUIZ_ADMIN` (owners can submit for review and set PRIVATE) |
| Analytics (results/leaderboard/attempts) | JWT | Owner or moderator/admin (service enforced) |
| AI generation jobs | JWT | `QUIZ_CREATE`; admin job maintenance requires `QUIZ_ADMIN` |
| Export (scope=public) | none | public |
| Export (scope=me) | JWT | `QUIZ_READ` |
| Export (scope=all) | JWT | `QUIZ_MODERATE` or `QUIZ_ADMIN` |
| Share links | JWT | Authenticated user (ownership enforced) |
| Admin moderation controller | JWT | `QUIZ_MODERATE` / `QUIZ_ADMIN` |
| GET `/public` | none | public |

---

## DTOs (selected)

**CreateQuizRequest / UpdateQuizRequest**

```ts
type CreateQuizRequest = {
  title: string;                // 3-100 chars
  description?: string | null;  // ≤1000 chars
  visibility?: 'PUBLIC' | 'PRIVATE' | 'UNLISTED';
  status?: 'DRAFT' | 'PUBLISHED';
  categoryId?: string | null;
  tags?: string[];
  questions?: Array<{ questionId: string; points?: number }>;
  settings?: Record<string, unknown>;
};

type UpdateQuizRequest = CreateQuizRequest & { status?: string; };
```

**BulkQuizUpdateRequest**

```ts
{
  quizIds: string[];
  operations: Array<{
    type: 'VISIBILITY' | 'STATUS' | 'CATEGORY' | 'TAGS';
    payload: Record<string, unknown>;
  }>;
}
```

**QuizDto**

```ts
{
  id: string;
  title: string;
  description?: string | null;
  visibility: string;
  status: string;
  authorId: string;
  categoryId?: string | null;
  tagIds: string[];
  questionCount: number;
  createdAt: string;
  updatedAt: string;
  settings?: Record<string, unknown> | null;
}
```

**ShareLink DTOs**

```ts
type CreateShareLinkRequest = {
  expiresAt?: string | null;
  oneTime?: boolean;
  scope?: 'ANONYMOUS' | 'AUTHENTICATED';
};

type CreateShareLinkResponse = { link: ShareLinkDto; token: string; };

type ShareLinkDto = {
  id: string;
  quizId: string;
  creatorId: string;
  createdAt: string;
  expiresAt?: string | null;
  oneTime: boolean;
  scope: string;
  token?: string; // not returned for listing unless included intentionally
};
```

**QuizExportRequest**

```ts
{
  scope: 'public' | 'me' | 'all';
  format: 'CSV' | 'JSON' | 'PDF';
  categoryIds?: string[];
  tags?: string[];
  authorId?: string;
  difficulty?: string;
  search?: string;
  quizIds?: string[];
}
```

**AI Generation Requests**

```ts
GenerateQuizFromDocumentRequest {
  documentId: string;
  quizScope: 'ENTIRE_DOCUMENT' | 'SPECIFIC_CHUNKS' | 'SPECIFIC_CHAPTER' | 'SPECIFIC_SECTION';
  chunkIndices?: number[];         // required when scope = SPECIFIC_CHUNKS
  chapterTitle?: string;
  chapterNumber?: number;
  quizTitle?: string;
  quizDescription?: string;
  questionsPerType: Record<QuestionType, number>; // 1-10 each
  difficulty: Difficulty;
  estimatedTimePerQuestion?: number; // defaults 1 minute
  categoryId?: string;
  tagIds?: string[];
  language?: string; // defaults "en"
}

GenerateQuizFromUploadRequest: multipart `file` + JSON fields above.
GenerateQuizFromTextRequest { text: string; language?: string; settings?: Record<string, unknown>; }
```

**QuizGenerationStatus / Result**

```ts
QuizGenerationStatus { jobId: string; status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'; progress: number; }
QuizGenerationResult { quizId?: string; message?: string; tokensUsed?: number; }
```

**ErrorResponse**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
```

---

## Endpoints – Core CRUD

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| POST | `/` | `CreateQuizRequest` | `{ quizId: string; }` | `QUIZ_CREATE` | Non-moderators limited to PRIVATE/DRAFT |
| GET | `/` | – | `Page<QuizDto>` | JWT | Query: pagination + `QuizSearchCriteria`; `scope` param = `public` (default) \| `me` \| `all` |
| GET | `/{quizId}` | – | `QuizDto` | JWT | Ownership/moderation enforced |
| PATCH | `/{quizId}` | `UpdateQuizRequest` | `QuizDto` | `QUIZ_UPDATE` | Full update |
| PATCH | `/bulk-update` | `BulkQuizUpdateRequest` | `BulkQuizUpdateOperationResultDto` | `QUIZ_UPDATE` | Batch operations |
| DELETE | `/{quizId}` | – | 204 | `QUIZ_DELETE` | Hard delete |
| DELETE | `?ids=<id,...>` | – | 204 | `QUIZ_DELETE` | Bulk delete via query param |

### Question & Tag Associations

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| POST | `/{quizId}/questions/{questionId}` | – | 204 | `QUIZ_UPDATE` | Add question |
| DELETE | `/{quizId}/questions/{questionId}` | – | 204 | `QUIZ_UPDATE` | Remove question |
| POST | `/{quizId}/tags/{tagId}` | – | 204 | `QUIZ_UPDATE` | Attach tag |
| DELETE | `/{quizId}/tags/{tagId}` | – | 204 | `QUIZ_UPDATE` | Detach tag |
| PATCH | `/{quizId}/category/{categoryId}` | – | 204 | `QUIZ_UPDATE` | Change category |

### Analytics & Attempts

| Method | Path | Resp | Auth | Notes |
| ------ | ---- | ---- | ---- | ----- |
| GET | `/{quizId}/results` | `QuizResultSummaryDto` | JWT | Owner/moderator |
| GET | `/{quizId}/leaderboard?top=n` | `LeaderboardEntryDto[]` | JWT | Visibility rules enforced |
| GET | `/{quizId}/attempts` | `AttemptDto[]` | JWT | Owner-only |
| GET | `/{quizId}/attempts/{attemptId}/stats` | `AttemptStatsDto` | JWT | Owner-only |

### Visibility & Moderation

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| PATCH | `/{quizId}/visibility` | `VisibilityUpdateRequest` | 204 | `QUIZ_MODERATE` (owners can set PRIVATE) | Toggle PUBLIC/PRIVATE |
| PATCH | `/{quizId}/status` | `QuizStatusUpdateRequest` | 204 | `QUIZ_MODERATE` or `QUIZ_ADMIN` | Manage lifecycle |
| GET | `/public` | `Page<QuizSummaryDto>` | none | Public browse with pagination & ETag |
| POST | `/{quizId}/submit-for-review` | – | 204 | `QUIZ_UPDATE` owner | Moves quiz to moderation queue |

**ModerationController (`/api/v1/admin/quizzes`)**

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| POST | `/admin/quizzes/{quizId}/approve` | `ApproveRequest?` (reason query optional) | 204 | `QUIZ_MODERATE` | Approve pending quiz |
| POST | `/admin/quizzes/{quizId}/reject` | `RejectRequest` (`reason` query) | 204 | `QUIZ_MODERATE` | Reject quiz |
| POST | `/admin/quizzes/{quizId}/unpublish` | `UnpublishRequest?` (reason query) | 204 | `QUIZ_MODERATE` | Unpublish quiz |
| GET | `/admin/quizzes/pending-review?orgId=` | – | `PendingReviewQuizDto[]` | `QUIZ_MODERATE` | Pending queue (org scoped) |
| GET | `/admin/quizzes/{quizId}/audits` | – | `QuizModerationAuditDto[]` | `QUIZ_MODERATE` | Audit trail |

### Share Link & Anonymous Attempt Workflow

All paths below share base `/api/v1/quizzes`.

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| POST | `/{quizId}/share-link` | `CreateShareLinkRequest` | `CreateShareLinkResponse` | JWT (owner) | Rate limited 10/min per user |
| DELETE | `/shared/{tokenId}` | – | 204 | JWT (creator/moderator) | Revoke link |
| GET | `/share-links` | – | `ShareLinkDto[]` | JWT | Returns links created by caller |
| GET | `/shared/{token}` | – | `ShareLinkDto` | public | Validates token, sets cookie |
| POST | `/shared/{token}/attempts` | `StartAttemptRequest?` | `StartAttemptResponse` | public | Starts anonymous attempt (201) |
| POST | `/shared/attempts/{attemptId}/answers` | `AnswerSubmissionRequest` | `AnswerSubmissionDto` | public w/ cookie | Submit single answer |
| POST | `/shared/attempts/{attemptId}/answers/batch` | `BatchAnswerSubmissionRequest` | `AnswerSubmissionDto[]` | public w/ cookie | Batch answers |
| GET | `/shared/attempts/{attemptId}/current-question` | – | `CurrentQuestionDto` | public w/ cookie | Poll active question |
| GET | `/shared/attempts/{attemptId}/stats` | – | `AttemptStatsDto` | public w/ cookie | Attempt analytics |
| POST | `/shared/attempts/{attemptId}/complete` | – | `AttemptResultDto` | public w/ cookie | Complete attempt |
| GET | `/shared/attempts/{attemptId}/review` | – | `AttemptReviewDto` | public w/ cookie | Review (user answers + correct) |
| POST | `/shared/{token}/consume` | – | `ShareLinkDto` | public | Consume one-time token |

### AI Generation Workflow

| Method | Path | ReqBody | Resp | Status | Auth | Notes |
| ------ | ---- | ------- | ---- | ------ | ---- | ----- |
| POST | `/generate-from-document` | `GenerateQuizFromDocumentRequest` | `QuizGenerationResponse` | 202 | `QUIZ_CREATE` | Document must be processed |
| POST | `/generate-from-upload` | multipart (`file` + JSON) | `QuizGenerationResponse` | 202 | `QUIZ_CREATE` | Upload & generate |
| POST | `/generate-from-text` | `GenerateQuizFromTextRequest` | `QuizGenerationResponse` | 202 | `QUIZ_CREATE` | Raw text |
| GET | `/generation-status/{jobId}` | – | `QuizGenerationStatus` | 200 | JWT | Poll job progress |
| GET | `/generated-quiz/{jobId}` | – | `QuizDto` | 200 | JWT | Fetch generated quiz when complete |
| DELETE | `/generation-status/{jobId}` | – | `QuizGenerationStatus` | 200 | JWT | Cancel own job (includes updated status) |
| GET | `/generation-jobs` | – | `Page<QuizGenerationStatus>` | 200 | JWT | Caller’s jobs |
| GET | `/generation-jobs/statistics` | – | `QuizGenerationJobService.JobStatistics` | 200 | JWT | Caller’s job stats |
| POST | `/generation-jobs/cleanup-stale` | – | 200 | `QUIZ_ADMIN` | Maintenance |
| POST | `/generation-jobs/{jobId}/force-cancel` | – | 200 | `QUIZ_ADMIN` | Force cancel job |

### Export

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| GET | `/export` | query = `QuizExportRequest` | File stream (`CSV`/`JSON`/`PDF`) | public / `QUIZ_READ` / `QUIZ_MODERATE+` | Rate limited 30/min; honours scope rules |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 400 | Validation error | Invalid payloads, illegal state transitions |
| 401 | Unauthorized | Missing/invalid JWT (except public endpoints) |
| 403 | Forbidden | Lacking required permission/ownership |
| 404 | Not found | Quiz/question/tag/job/share link missing or inaccessible |
| 409 | Conflict | Duplicate operations, moderation conflicts |
| 410 | Gone | One-time share link already consumed |
| 415 | Unsupported media type | AI upload with wrong MIME |
| 422 | Unprocessable entity | AI generation failure or invalid content |
| 429 | Too many requests | Rate limit exceeded (search/export/generation/share link flows) |
| 500 | Server error | Unexpected failure |

---

## Validation Summary

- Quiz titles 3–100 chars; descriptions ≤1000 chars; visibility/status transitions enforced service-side.
- Bulk operations validate every quiz id; failures reported in result payload.
- Share link token format `[A-Za-z0-9_-]{43}`; creation enforces rate limit 10/min per user.
- AI generation question counts 1–10 per type; chunk indices non-negative; uploads respect server file limits.
- Export scope rules: `scope=me` forces authenticated author; `scope=all` restricted to moderators/admins.
- Rate limits via `RateLimitService`: search 120/min per IP, export 30/min, quiz generation start 3/min, cancel 5/min, share link analytics 60/min (varies per endpoint).

---

## Notes for Agents

- Include `Authorization: Bearer <jwt>` except for `/public`, public exports, and share-link access endpoints.
- Poll `/generation-status/{jobId}` until `status === 'COMPLETED'`, then fetch via `/generated-quiz/{jobId}`.
- Use `scope` on GET `/` carefully: `public` (default) for browse, `me` for dashboard, `all` for moderators.
- Surface 403/410 w/ actionable messaging (e.g., “link expired”) when users hit share-link restrictions.
- Stream `/export` responses directly; content type depends on `format`.
