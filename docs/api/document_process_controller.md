# DocumentProcessController API Spec

Base path: `/api/v1/documentProcess/documents`\
Content types: `application/json`, `multipart/form-data`

## Auth

| Operation | Auth | Roles/Permissions |
| ---------- | ---- | ----------------- |
| POST `/` (JSON) | JWT | authenticated user |
| POST `/` (multipart) | JWT | authenticated user |
| GET `/{id}` | JWT | authenticated user |
| GET `/{id}/head` | JWT | authenticated user |
| GET `/{id}/text` | JWT | authenticated user |
| GET `/{id}/structure` | JWT | authenticated user |
| POST `/{id}/structure` | JWT | authenticated user |
| GET `/{id}/extract` | JWT | authenticated user |

---

## DTOs

**IngestRequest**

```ts
{ text: string; language?: string; } // text required, language ≤32 chars
```

**IngestResponse**

```ts
{ id: string; status: 'PENDING' | 'NORMALIZED' | 'FAILED'; }
```

**DocumentView**

```ts
{
  id: string;
  originalName: string;
  mime: string;
  source: 'TEXT' | 'UPLOAD' | string;
  charCount: number;
  language?: string | null;
  status: 'PENDING' | 'NORMALIZED' | 'FAILED' | 'STRUCTURED';
  createdAt: string;
  updatedAt: string;
}
```

**TextSliceResponse**

```ts
{ documentId: string; start: number; end: number; text: string; }
```

**StructureTreeResponse / NodeView**

```ts
{
  documentId: string;
  rootNodes: NodeView[];
  totalNodes: number;
}

type NodeView = {
  id: string;
  documentId: string;
  parentId?: string | null;
  idx: number;
  type: string;
  title?: string | null;
  startOffset?: number | null;
  endOffset?: number | null;
  depth: number;
  aiConfidence?: number | null;
  metaJson?: string | null;
  children: NodeView[];
};
```

**StructureFlatResponse / FlatNode**

```ts
{
  documentId: string;
  nodes: FlatNode[];
  totalNodes: number;
}

type FlatNode = Omit<NodeView, 'children'>;
```

**ExtractResponse**

```ts
{ documentId: string; nodeId: string; title?: string | null; start: number; end: number; text: string; }
```

**StructureBuildResponse**

```ts
{ status: 'STRUCTURED' | 'FAILED' | 'ERROR'; message: string; }
```

**ErrorResponse**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
```

---

## Endpoints

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| POST | `/` (JSON) | `IngestRequest` | `IngestResponse` | JWT | Optional `originalName` query param |
| POST | `/` (multipart) | multipart `file` (+optional `originalName`) | `IngestResponse` | JWT | File must be non-empty |
| GET | `/{id}` | – | `DocumentView` | JWT | Full metadata |
| GET | `/{id}/head` | – | `DocumentView` | JWT | Lightweight metadata |
| GET | `/{id}/text` | – | `TextSliceResponse` | JWT | Query `start` (default 0), optional `end`; 400 on invalid range |
| GET | `/{id}/structure` | – | `StructureTreeResponse` or `StructureFlatResponse` | JWT | Query `format=tree|flat` (default tree) |
| POST | `/{id}/structure` | – | `StructureBuildResponse` | JWT | Triggers AI structure build |
| GET | `/{id}/extract` | – | `ExtractResponse` | JWT | Requires query `nodeId` |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 400 | Validation error | Missing text/file, bad offsets, invalid format param |
| 401 | Unauthorized | Missing/invalid JWT |
| 404 | Not found | Document/node absent |
| 415 | Unsupported media type | Multipart upload without file |
| 422 | Unprocessable entity | AI normalization/build failure |
| 500 | Server error | Unexpected ingestion or structure error |

---

## Validation Summary

- `text` must be non-blank; `language` max 32 chars.
- Multipart uploads require non-empty file bytes; name defaults to provided `originalName` or derived filename.
- `start`/`end` query params for `/text` must be ≥0 and `end >= start`.
- `format` accepts only `tree` or `flat`; anything else returns 400.
- `nodeId` query param required for `/extract`.

---

## Notes for Agents

- Always include `Authorization: Bearer <jwt>`.
- Use JSON ingestion for raw text; use multipart for binary uploads.
- Tree responses can be large—request `format=flat` when hierarchical context unnecessary.
- After triggering `/structure`, poll `/structure` or `/head` until `status` transitions to `STRUCTURED`.
