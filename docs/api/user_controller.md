# UserController API Spec

Base path: `/api/v1/users`\
Content type: `application/json` (avatar upload uses `multipart/form-data`)

## Auth

| Operation | Auth | Roles/Permissions |
| ---------- | ---- | ----------------- |
| GET `/me` | JWT | authenticated user |
| PATCH `/me` | JWT | authenticated user |
| POST `/me/avatar` | JWT | authenticated user |

---

## DTOs

**UserProfileResponse**

```ts
{
  id: string;
  username: string;
  email: string;
  displayName?: string | null;
  bio?: string | null;
  avatarUrl?: string | null;
  preferences?: Record<string, unknown> | null;
  joinedAt: string;     // ISO timestamp
  verified: boolean;
  roles: string[];
  version?: number | null; // used for ETag/If-Match
}
```

**AvatarUploadResponse**

```ts
{ avatarUrl: string; message: string; }
```

**Profile Update Payload**

```ts
// Accepts full JSON or JSON Merge Patch
type UpdateMePayload = {
  displayName?: string | null; // ≤50 chars
  bio?: string | null;         // ≤500 chars
  preferences?: Record<string, unknown> | null; // ≤50 keys, key ≤64 chars
};
```

**ErrorResponse**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
```

---

## Endpoints

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| GET | `/me` | – | `UserProfileResponse` | JWT | Returns `ETag: "<version>"`; `Cache-Control: no-store` |
| PATCH | `/me` | `UpdateMePayload` | `UserProfileResponse` | JWT | Supports `If-Match` optimistic locking; accepts `application/json` or `application/merge-patch+json` |
| POST | `/me/avatar` | multipart (`file`) | `AvatarUploadResponse` | JWT | Accepts PNG/JPEG/WEBP; image resized ≤512×512 |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 400 | Validation error | Bad field length, invalid preferences, bad avatar MIME |
| 401 | Unauthorized | Missing/invalid JWT |
| 403 | Forbidden | User inactive/deleted |
| 404 | Not found | Not emitted (reserved) |
| 412 | Precondition failed | `If-Match` version mismatch |
| 415 | Unsupported media type | Avatar upload with unsupported MIME |
| 500 | Server error | Unexpected failure |

---

## Validation Summary

- `displayName` ≤50 chars; sanitized server-side; `null` clears value.
- `bio` ≤500 chars; sanitized.
- `preferences` must be an object with ≤50 keys; key length ≤64; values JSON-serializable.
- Avatar uploads: PNG/JPEG/WEBP only; invalid MIME or empty file returns 400/415.
- Provide `If-Match` header when updating to avoid 412 on concurrent edits.

---

## Notes for Agents

- Always send `Authorization: Bearer <jwt>`.
- Cache the ETag from `GET /me` and reuse with `If-Match` on PATCH.
- After avatar upload, update cached avatar URL to bypass caching.
- Treat 403 as account disabled; prompt re-authentication or support escalation.
