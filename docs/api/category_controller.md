# CategoryController API Spec

Base path: `/api/v1/categories`\
Content type: `application/json`

## Auth

| Operation | Auth | Roles/Permissions |
| ---------- | ---- | ----------------- |
| GET `/` | none | public |
| GET `/{categoryId}` | none | public |
| POST `/` | JWT | `ROLE_ADMIN` |
| PATCH `/{categoryId}` | JWT | `ROLE_ADMIN` |
| DELETE `/{categoryId}` | JWT | `ROLE_ADMIN` |

---

## DTOs

**CategoryDto**

```ts
{ id: string; name: string; description?: string | null; }
```

**CreateCategoryRequest / UpdateCategoryRequest**

```ts
type CreateCategoryRequest = {
  name: string;         // 3-100 chars, unique (case-insensitive)
  description?: string; // optional, ≤1000 chars
};

type UpdateCategoryRequest = CreateCategoryRequest;
```

**CreateCategoryResponse**

```ts
{ categoryId: string; }
```

**ErrorResponse**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
```

---

## Endpoints

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| GET | `/` | – | `Page<CategoryDto>` | none | `page`, `size`, `sort` (defaults `name,asc`) |
| GET | `/{categoryId}` | – | `CategoryDto` | none | 404 if missing |
| POST | `/` | `CreateCategoryRequest` | `CreateCategoryResponse` | admin | 409 on duplicate name |
| PATCH | `/{categoryId}` | `UpdateCategoryRequest` | `CategoryDto` | admin | Full update; returns updated dto |
| DELETE | `/{categoryId}` | – | 204 | admin | 400 if deleting default category |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 400 | Validation error | Includes attempt to delete default category |
| 401 | Unauthorized | Missing/invalid JWT |
| 403 | Forbidden | Caller lacks `ROLE_ADMIN` |
| 404 | Not found | Category id absent |
| 409 | Conflict | Duplicate category name |
| 500 | Server error | Unexpected failure |

---

## Validation Summary

- `name` required, 3–100 chars, unique (case-insensitive).
- `description` optional up to 1000 chars.
- Update must send full payload; set description to `null`/empty string to clear.
- Delete rejects the configured default category.

---

## Notes for Agents

- Public reads need no headers; admin writes require `Authorization: Bearer <jwt>`.
- After create/update/delete, refresh cached category lists in UI.
- Treat 409 as duplicate name; prompt for a unique value.
