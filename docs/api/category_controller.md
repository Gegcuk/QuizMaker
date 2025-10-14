# Category Controller API Reference

Complete integration guide for `/api/v1/categories` REST endpoints. This document consolidates DTO contracts, validation rules, endpoint behavior, security requirements, and error semantics so frontend engineers can work without digging into backend code.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Endpoints](#endpoints)
- [Pagination Model](#pagination-model)
- [Error Handling](#error-handling)
- [Integration Notes](#integration-notes)
- [Security Considerations](#security-considerations)

---

## Overview

- **Base Path**: `/api/v1/categories`
- **Authentication**:
  - `GET` endpoints are publicly accessible (no auth header required by the controller layer).
  - `POST`, `PATCH`, and `DELETE` endpoints require a valid JWT bearer token with the `ADMIN` role.
- **Content-Type**: `application/json` for all request bodies and responses (except `204` responses).
- **Error Format**: Errors surface as `ErrorResponse` objects (see [Error Handling](#error-handling)).

---

## Authorization Matrix

| Capability | Endpoint(s) | Requirement | Notes |
| --- | --- | --- | --- |
| **List categories** | `GET /` | None | Optional pagination & sorting parameters; defaults applied server side. |
| **Read category** | `GET /{categoryId}` | None | Returns 404 when ID does not exist. |
| **Create category** | `POST /` | Authenticated admin (`ROLE_ADMIN`) | Requires bearer token; duplicate names cause conflict. |
| **Update category** | `PATCH /{categoryId}` | Authenticated admin (`ROLE_ADMIN`) | Requires bearer token; validates full payload. |
| **Delete category** | `DELETE /{categoryId}` | Authenticated admin (`ROLE_ADMIN`) | Requires bearer token; returns 204 on success. |

---

## Request DTOs

### `CreateCategoryRequest`

Used by `POST /`.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `name` | `string` | Yes | Length 3–100 characters | Display name; must be unique (case sensitive) |
| `description` | `string` | No | Max 1000 characters | Optional long description |

**Example**

```json
{
  "name": "Science",
  "description": "All science-related quizzes"
}
```

### `UpdateCategoryRequest`

Used by `PATCH /{categoryId}`. Entire payload is validated; omit fields only when you do not want to change them.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `name` | `string` | Yes | Length 3–100 characters | Updated display name (unique) |
| `description` | `string` | No | Max 1000 characters | Updated description |

**Example**

```json
{
  "name": "History",
  "description": "Historical events and figures"
}
```

> ℹ️ The backend enforces uniqueness on the `name` column, so attempting to create or rename a category to an existing value yields a `409 Conflict`.

---

## Response DTOs

### `CategoryDto`

Returned by all read endpoints.

| Field | Type | Description |
| --- | --- | --- |
| `id` | `UUID` | Category identifier |
| `name` | `string` | Category display name |
| `description` | `string` | Optional description |

**Example**

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "General",
  "description": "General knowledge topics"
}
```

### `Create`/`Update` Responses

- `POST /` returns `201 Created` with body `{ "categoryId": "<uuid>" }`.
- `PATCH /{categoryId}` returns the updated `CategoryDto` (`200 OK`).
- `DELETE /{categoryId}` returns `204 No Content` on success.

---

## Endpoints

### `GET /api/v1/categories`

Returns a pageable list of categories ordered by name ascending by default.

**Query Parameters**

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `page` | integer (0-based) | `0` | Page index |
| `size` | integer | `20` | Page size |
| `sort` | `field,dir` | `name,asc` | Field and direction |

**Response**: `200 OK` with [Pagination Model](#pagination-model) containing `CategoryDto` objects.

### `POST /api/v1/categories`

Creates a new category.

- **Auth**: Bearer token with `ROLE_ADMIN`.
- **Body**: `CreateCategoryRequest` (required).
- **Responses**:
  - `201 Created` + `{ "categoryId": "<uuid>" }`
  - `400 Bad Request` on validation errors (see [Error Handling](#error-handling)).
  - `401 Unauthorized` when the token is missing/invalid.
  - `403 Forbidden` when the caller lacks admin rights.
  - `409 Conflict` when violating the unique name constraint.

### `GET /api/v1/categories/{categoryId}`

Fetches a single category by UUID.

- **Auth**: None required.
- **Responses**:
  - `200 OK` + `CategoryDto`
  - `404 Not Found` when the ID is unknown.

### `PATCH /api/v1/categories/{categoryId}`

Updates name/description of an existing category.

- **Auth**: Bearer token with `ROLE_ADMIN`.
- **Body**: `UpdateCategoryRequest` (required).
- **Responses**:
  - `200 OK` + updated `CategoryDto`
  - `400 Bad Request` on validation failures.
  - `401 Unauthorized` / `403 Forbidden` on auth issues.
  - `404 Not Found` when the category does not exist.
  - `409 Conflict` on unique constraint violations.

### `DELETE /api/v1/categories/{categoryId}`

Deletes a category.

- **Auth**: Bearer token with `ROLE_ADMIN`.
- **Responses**:
  - `204 No Content` on success.
  - `401 Unauthorized` / `403 Forbidden` on auth issues.
  - `404 Not Found` when the category does not exist.

---

## Pagination Model

List endpoints return Spring's `Page` structure.

```json
{
  "content": [ { "id": "...", "name": "...", "description": "..." } ],
  "pageable": { "pageNumber": 0, "pageSize": 20, ... },
  "totalPages": 5,
  "totalElements": 94,
  "last": false,
  "size": 20,
  "number": 0,
  "sort": { "sorted": true, "unsorted": false, "empty": false },
  "first": true,
  "numberOfElements": 20,
  "empty": false
}
```

Use `page`, `size`, and `sort` query parameters to customize the paging behavior.

---

## Error Handling

All errors use the shared `ErrorResponse` shape:

```json
{
  "timestamp": "2024-01-30T12:34:56.789",
  "status": 404,
  "error": "Not Found",
  "details": [
    "Category 123e4567-e89b-12d3-a456-426614174000 not found"
  ]
}
```

Key scenarios:

| Status | Trigger | Notes |
| --- | --- | --- |
| `400 Bad Request` | Validation failure | Field errors reported in `details` (e.g., `name: Category name length must be between 3 and 100 characters`). |
| `401 Unauthorized` | Missing/invalid token on protected endpoints | Caller must re-authenticate. |
| `403 Forbidden` | Authenticated but lacks `ROLE_ADMIN` | Message clarifies access denial. |
| `404 Not Found` | Category ID not found | Message includes the missing UUID. |
| `409 Conflict` | Duplicate category name | Database unique constraint violation. |
| `500 Internal Server Error` | Unexpected failure | Rare; advise retry/backoff. |

---

## Integration Notes

1. **ID handling**: Persist the `categoryId` returned from `POST /` for subsequent operations.
2. **Optimistic UI**: After create/update/delete, refetch the `GET /` list (paging aware) to keep UI in sync.
3. **Validation UX**: Surface `details` messages directly—they are user-friendly.
4. **Sorting**: UI sort toggles should update the `sort` query parameter (e.g., `sort=name,desc`).
5. **Error 409 handling**: Prompt admins to choose a different name when duplicates occur.
6. **Empty descriptions**: Send `""` or omit the field—backend allows null/blank descriptions up to 1000 chars.

---

## Security Considerations

- **Role enforcement**: Create/update/delete methods require `ROLE_ADMIN`; unauthorized actors receive 401/403 responses.
- **Audit context**: The controller forwards the authenticated username to the service for auditing.
- **Token storage**: Store admin tokens securely (e.g., HTTP-only cookies or secure storage in native apps).
- **Transport security**: Always invoke endpoints over HTTPS; tokens must never traverse plain HTTP.
- **Least privilege**: Non-admin users should call read-only endpoints; admin features should be gated in the UI.