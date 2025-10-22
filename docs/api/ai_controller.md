# AI Controllers API Spec

Base paths: `/api/ai` and `/api/v1/ai-analysis`\
Content type: `application/json`

## Auth

| Operation | Auth | Roles/Permissions |
| ---------- | ---- | ----------------- |
| POST `/api/ai/chat` | JWT | authenticated user |
| POST `/api/v1/ai-analysis/analyze` | JWT | `ROLE_ADMIN` |

---

## DTOs

**ChatRequestDto**

```ts
{ message: string; } // required, 1–2000 chars
```

**ChatResponseDto**

```ts
{
  message: string;
  model: string;
  latency: number;     // ms
  tokensUsed: number;
  timestamp: string;   // ISO timestamp
}
```

**AnalysisResponse** (map)

```ts
{ status: 'success' | 'error'; message: string; }
```

**ErrorResponse**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
```

---

## Endpoints

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| POST | `/api/ai/chat` | `ChatRequestDto` | `ChatResponseDto` | JWT | Stateless chat; logs usage and latency |
| POST | `/api/v1/ai-analysis/analyze` | – | `AnalysisResponse` | Admin | Runs offline analysis of stored responses |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 400 | Validation error | Blank or overlong message |
| 401 | Unauthorized | Missing/invalid JWT |
| 403 | Forbidden | Non-admin calling analysis endpoint |
| 429 | Too many requests | Surfaced from shared rate limiter if configured |
| 500 | Server error | Unexpected AI service failure |
| 503 | Service unavailable | Upstream AI provider unavailable |

---

## Validation Summary

- `message` must be non-blank and ≤2000 characters.
- Analysis endpoint performs no payload validation (no body) but may throw errors surfaced as 500.

---

## Notes for Agents

- Always include `Authorization: Bearer <jwt>`.
- Chat endpoint is stateless; the client must maintain conversation context.
- Display `tokensUsed` to users to show consumption; expect latency in milliseconds to help with UX timing.
- Hide `/api/v1/ai-analysis/analyze` from non-admin users; it is primarily a diagnostics tool whose output appears in server logs.
