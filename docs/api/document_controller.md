# DocumentController API Spec

Base path: `/api/documents`\
Content types: `application/json`, `multipart/form-data` for uploads

## Auth

| Operation | Auth | Roles/Permissions |
| ---------- | ---- | ----------------- |
| POST `/upload` | JWT | authenticated user |
| GET `/` | JWT | authenticated user |
| GET `/{documentId}` | JWT | authenticated user (owner only) |
| GET `/{documentId}/chunks` | JWT | authenticated user (owner only) |
| GET `/{documentId}/chunks/{chunkIndex}` | JWT | authenticated user (owner only) |
| DELETE `/{documentId}` | JWT | authenticated user (owner only) |
| POST `/{documentId}/reprocess` | JWT | authenticated user (owner only) |
| GET `/{documentId}/status` | JWT | authenticated user (owner only) |
| GET `/config` | JWT | authenticated user |

---

## DTOs

**DocumentDto** (partial)

```ts
{
  id: string;
  originalFilename: string;
  contentType: string;
  fileSize: number;
  status: 'UPLOADED' | 'PROCESSING' | 'PROCESSED' | 'FAILED';
  uploadedAt: string;
  processedAt?: string | null;
  title?: string | null;
  author?: string | null;
  totalPages?: number | null;
  totalChunks?: number | null;
  processingError?: string | null;
  chunks?: DocumentChunkDto[] | null;
}
```

**DocumentChunkDto**

```ts
{
  id: string;
  chunkIndex: number;
  title?: string | null;
  content: string;
  startPage?: number | null;
  endPage?: number | null;
  wordCount?: number | null;
  characterCount?: number | null;
  chapterTitle?: string | null;
  sectionTitle?: string | null;
  chapterNumber?: number | null;
  sectionNumber?: number | null;
  chunkType: string; // enum
}
```

**ProcessDocumentRequest**

```ts
{
  chunkingStrategy: 'AUTO' | 'CHAPTER_BASED' | 'SECTION_BASED' | 'SIZE_BASED' | 'PAGE_BASED';
  maxChunkSize?: number;                // 100–100000 chars
  minChunkSize?: number;                // default 1000
  aggressiveCombinationThreshold?: number; // default 3000
  storeChunks?: boolean;                // default true
}
```

**DocumentConfigDto**

```ts
{ defaultMaxChunkSize: number; defaultStrategy: string; }
```

**ErrorResponse**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
```

---

## Endpoints

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| POST | `/upload` | multipart (`file`, optional `chunkingStrategy`, `maxChunkSize`) | `DocumentDto` | JWT | File ≤150 MB; supported MIME: PDF, EPUB, TXT |
| GET | `/` | – | `Page<DocumentDto>` | JWT | Owner-only list; `page`, `size` |
| GET | `/{documentId}` | – | `DocumentDto` | JWT | Owner-only; 404 if doc missing or forbidden |
| GET | `/{documentId}/chunks` | – | `DocumentChunkDto[]` | JWT | Owner-only |
| GET | `/{documentId}/chunks/{chunkIndex}` | – | `DocumentChunkDto` | JWT | Owner-only; 404 if chunk missing |
| DELETE | `/{documentId}` | – | 204 | JWT | Owner-only; hard delete |
| POST | `/{documentId}/reprocess` | `ProcessDocumentRequest` | `DocumentDto` | JWT | Requires chunkingStrategy; re-runs pipeline |
| GET | `/{documentId}/status` | – | `DocumentDto` | JWT | Lightweight poll of status |
| GET | `/config` | – | `DocumentConfigDto` | JWT | Returns defaults for UI |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 400 | Validation error | Missing file, invalid chunk size/strategy, unsupported MIME |
| 401 | Unauthorized | Missing/invalid JWT |
| 403 | Forbidden | Attempt to access another user's document |
| 404 | Not found | Document or chunk absent |
| 415 | Unsupported media type | Non-supported upload MIME |
| 422 | Unprocessable entity | Processing/normalization failures |
| 500 | Server error | Storage or processing failure |

---

## Validation Summary

- Uploads require non-empty file ≤150 MB with MIME `application/pdf`, `application/epub+zip`, or `text/plain` (extra converters may add formats internally).
- `maxChunkSize` (upload and reprocess) must be between 100 and 100000 when provided.
- `chunkingStrategy` must match enum (case-insensitive); reprocess requires it.
- Ownership enforced server-side—non-owners receive 403.

---

## Notes for Agents

- Always include `Authorization: Bearer <jwt>` and send multipart form-data for uploads.
- After upload or reprocess, poll `/status` until `status === 'PROCESSED'` before requesting chunks.
- Use `/config` to seed UI defaults for chunking controls.
- Surface `processingError` when `status === 'FAILED'` to inform users.
