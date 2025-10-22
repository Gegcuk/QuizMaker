# TagController API Spec

Base path: `/api/v1/tags`\
Content type: `application/json`

## Auth

| Operation | Auth | Roles/Permissions |
| ---------- | ---- | ----------------- |
| GET `/` | none | public |
| GET `/{tagId}` | none | public |
| POST `/` | JWT | `ROLE_ADMIN` |
| PATCH `/{tagId}` | JWT | `ROLE_ADMIN` |
| DELETE `/{tagId}` | JWT | `ROLE_ADMIN` |

---

## DTOs

**TagDto**

```ts
{ id: string; name: string; description?: string | null; }
```

**CreateTagRequest / UpdateTagRequest**

```ts
type CreateTagRequest = {
  name: string;         // 3-50 chars, unique (case-insensitive)
  description?: string; // optional, ≤1000 chars
};

type UpdateTagRequest = CreateTagRequest;
```

**CreateTagResponse**

```ts
{ tagId: string; }
```

**ErrorResponse**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
```

---

## Endpoints

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| GET | `/` | – | `Page<TagDto>` | none | Supports `page`, `size`, `sort` (`name,asc` default) |
| GET | `/{tagId}` | – | `TagDto` | none | 404 if missing |
| POST | `/` | `CreateTagRequest` | `CreateTagResponse` | admin | 409 on duplicate name |
| PATCH | `/{tagId}` | `UpdateTagRequest` | `TagDto` | admin | Full update; returns latest dto |
| DELETE | `/{tagId}` | – | 204 | admin | Hard delete |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 400 | Validation error | Length constraints, malformed UUID |
| 401 | Unauthorized | Missing/invalid JWT |
| 403 | Forbidden | Caller lacks `ROLE_ADMIN` |
| 404 | Not found | Tag id absent |
| 409 | Conflict | Duplicate tag name |
| 500 | Server error | Unexpected failure |

---

## Validation Summary

- `name` is required, trimmed, 3–50 chars, unique (case-insensitive).
- `description` optional but must be ≤1000 chars if present.
- Update requires both fields; to clear description send empty string.

---

## Notes for Agents

- Public reads need no headers; admin writes must include `Authorization: Bearer <jwt>`.
- After create/update/delete, refresh tag cache in UI (responses don’t include pagination metadata for writes).
- Treat 409 as duplicate name; prompt user to choose a different value.
