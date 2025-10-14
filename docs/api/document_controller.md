# Document Controller API Reference

Complete integration guide for `/api/documents` REST endpoints. Includes all request/response DTOs, validation
requirements, enumerations, endpoint semantics, and error handling needed for frontend development.

## Table of Contents

- [Overview](#overview)
- [Authorization Model](#authorization-model)
- [DTO Catalogue](#dto-catalogue)
  - [DocumentDto](#documentdto)
  - [DocumentChunkDto](#documentchunkdto)
  - [DocumentConfigDto](#documentconfigdto)
  - [ProcessDocumentRequest](#processdocumentrequest)
- [Enumerations](#enumerations)
- [Validation Rules](#validation-rules)
- [Endpoints](#endpoints)
  - [POST /upload](#post-uploaddocuments)
  - [GET /{documentId}](#get-documentiddocuments)
  - [GET /](#get-documents)
  - [GET /{documentId}/chunks](#get-documentidchunksdocuments)
  - [GET /{documentId}/chunks/{chunkIndex}](#get-documentidchunkschunkindexdocuments)
  - [DELETE /{documentId}](#delete-documentiddocuments)
  - [POST /{documentId}/reprocess](#post-documentidreprocessdocuments)
  - [GET /{documentId}/status](#get-documentidstatusdocuments)
  - [GET /config](#get-configdocuments)
- [Error Handling](#error-handling)
- [Integration Playbooks](#integration-playbooks)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/documents`
* **Authentication**: Required for every endpoint (JWT bearer token in `Authorization: Bearer <token>` header).
* **Authorization**: Strict ownership model — users can interact only with documents they uploaded.
* **Content Types**:
  * Requests: `multipart/form-data` for uploads, `application/json` for everything else.
  * Responses: `application/json` (except `204` deletions).
* **Pagination**: Page-based pagination via `page` (0-indexed) and `size` query parameters on list endpoint.
* **Error Schema**: `GlobalExceptionHandler.ErrorResponse` record containing `timestamp`, `status`, `error`, `details` list.

---

## Authorization Model

| Capability | Endpoint(s) | Requirement |
| --- | --- | --- |
| Upload document | `POST /upload` | Authenticated user |
| Retrieve own document | `GET /{documentId}`, `GET /{documentId}/status` | Authenticated owner |
| List documents | `GET /` | Authenticated user (results filtered to owner) |
| Read chunks | `GET /{documentId}/chunks`, `GET /{documentId}/chunks/{chunkIndex}` | Authenticated owner |
| Delete document | `DELETE /{documentId}` | Authenticated owner |
| Reprocess document | `POST /{documentId}/reprocess` | Authenticated owner |
| Fetch controller config | `GET /config` | Any authenticated user |

If the authenticated user does not own the targeted document, a `403 Forbidden` is returned via
`UserNotAuthorizedException` or `Access denied` guard paths.【F:src/main/java/uk/gegc/quizmaker/features/document/api/DocumentController.java†L61-L188】【F:src/main/java/uk/gegc/quizmaker/features/document/application/impl/DocumentProcessingServiceImpl.java†L233-L328】

---

## DTO Catalogue

### `DocumentDto`

Represents a document and its metadata. Chunks are optional and only populated when explicitly returned.

| Field | Type | Description |
| --- | --- | --- |
| `id` | `UUID` | Stable identifier of the document. |
| `originalFilename` | `string` | Client-provided filename at upload time. |
| `contentType` | `string` | MIME type detected by the server. |
| `fileSize` | `number` (long) | File size in bytes. |
| `status` | `DocumentStatus` enum | Processing lifecycle stage. |
| `uploadedAt` | `string` (ISO timestamp) | Upload time. |
| `processedAt` | `string` (ISO timestamp) | Last processing completion time. |
| `title` | `string` | Title extracted from document (nullable). |
| `author` | `string` | Author extracted from document (nullable). |
| `totalPages` | `number` | Total page count (nullable). |
| `totalChunks` | `number` | Number of stored chunks. |
| `processingError` | `string` | Last processing failure message (nullable). |
| `chunks` | `DocumentChunkDto[]` | Present when chunk data is requested. |

Source: `DocumentDto` + `DocumentMapper` mapping logic.【F:src/main/java/uk/gegc/quizmaker/features/document/api/dto/DocumentDto.java†L1-L25】【F:src/main/java/uk/gegc/quizmaker/features/document/infra/mapping/DocumentMapper.java†L13-L41】

---

### `DocumentChunkDto`

Represents a normalized chunk of a document.

| Field | Type | Description |
| --- | --- | --- |
| `id` | `UUID` | Chunk identifier. |
| `chunkIndex` | `number` | Zero-based chunk ordering. |
| `title` | `string` | Chunk title or synthesized heading. |
| `content` | `string` | Raw chunk text content. |
| `startPage` | `number` | First page covered by chunk (defaults to `1` if source lacked page info). |
| `endPage` | `number` | Last page covered by chunk. |
| `wordCount` | `number` | Word count of chunk content. |
| `characterCount` | `number` | Character count of chunk content. |
| `createdAt` | `string` (ISO timestamp) | Chunk persistence timestamp. |
| `chapterTitle` | `string` | Source chapter name (nullable). |
| `sectionTitle` | `string` | Source section name (nullable). |
| `chapterNumber` | `number` | Chapter ordinal (nullable). |
| `sectionNumber` | `number` | Section ordinal (nullable). |
| `chunkType` | `DocumentChunkType` enum | Chunk derivation strategy. |

Source: `DocumentChunkDto` + `DocumentMapper`.【F:src/main/java/uk/gegc/quizmaker/features/document/api/dto/DocumentChunkDto.java†L1-L24】【F:src/main/java/uk/gegc/quizmaker/features/document/infra/mapping/DocumentMapper.java†L43-L62】

---

### `DocumentConfigDto`

Read-only configuration describing backend defaults used when processing documents.

| Field | Type | Description |
| --- | --- | --- |
| `defaultMaxChunkSize` | `number` | Default maximum chunk size in characters. |
| `defaultStrategy` | `string` | Default chunking strategy (`ProcessDocumentRequest.ChunkingStrategy`). |

Source: `DocumentConfigDto` record + controller response.【F:src/main/java/uk/gegc/quizmaker/features/document/api/dto/DocumentConfigDto.java†L1-L12】【F:src/main/java/uk/gegc/quizmaker/features/document/api/DocumentController.java†L200-L208】

---

### `ProcessDocumentRequest`

Controls chunking behavior during initial upload or manual reprocessing.

| Field | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `chunkingStrategy` | `ChunkingStrategy` enum | Required for reprocess, optional for upload overrides | Config default | Strategy used for chunk creation. |
| `maxChunkSize` | `number` | Optional | Config default (≈ `document.chunking.max-chunk-size`) | Maximum chunk size in characters. Must be within validation range when provided. |
| `minChunkSize` | `number` | Optional | `1000` (configurable) | Minimum chunk size permitted when combining segments. |
| `aggressiveCombinationThreshold` | `number` | Optional | `3000` (configurable) | Threshold below which small chunks may be combined. |
| `storeChunks` | `boolean` | Optional | `true` | Whether chunks should be persisted. |

Defaults are injected from `DocumentProcessingConfig` when no overrides are supplied.【F:src/main/java/uk/gegc/quizmaker/features/document/api/dto/ProcessDocumentRequest.java†L1-L19】【F:src/main/java/uk/gegc/quizmaker/features/document/application/DocumentProcessingConfig.java†L9-L35】

---

## Enumerations

* **`DocumentStatus`** (`Document.DocumentStatus`): `UPLOADED`, `PROCESSING`, `PROCESSED`, `FAILED`.【F:src/main/java/uk/gegc/quizmaker/features/document/domain/model/Document.java†L58-L102】
* **`DocumentChunkType`** (`DocumentChunk.ChunkType`): `CHAPTER`, `SECTION`, `PAGE_BASED`, `SIZE_BASED`.【F:src/main/java/uk/gegc/quizmaker/features/document/domain/model/DocumentChunk.java†L16-L61】
* **`ChunkingStrategy`** (`ProcessDocumentRequest.ChunkingStrategy`): `AUTO`, `CHAPTER_BASED`, `SECTION_BASED`, `SIZE_BASED`, `PAGE_BASED`.【F:src/main/java/uk/gegc/quizmaker/features/document/api/dto/ProcessDocumentRequest.java†L11-L19】

Mapping from `ChunkingStrategy` to persisted `DocumentChunkType` is managed server-side via `DocumentProcessingServiceImpl.mapChunkType`. `AUTO` maps to `SIZE_BASED`.【F:src/main/java/uk/gegc/quizmaker/features/document/application/impl/DocumentProcessingServiceImpl.java†L361-L368】

---

## Validation Rules

* **Upload prerequisites (`validateFileUpload`)**:
  * File must be present, non-empty, and readable. (`IllegalArgumentException`: "No file provided", "File is empty", or IO failure).
  * File size must not exceed 150 MB. (`IllegalArgumentException`).
  * Content type must be one of `application/pdf`, `application/epub+zip`, or `text/plain`. Unsupported types raise
    `UnsupportedFileTypeException` with `400 Bad Request`. A literal content type `invalid/content-type` is treated as
    an immediate `IllegalArgumentException`.【F:src/main/java/uk/gegc/quizmaker/features/document/application/impl/DocumentValidationServiceImpl.java†L16-L55】
  * When provided, `maxChunkSize` must be between 100 and 100 000 characters (inclusive). (`IllegalArgumentException`).【F:src/main/java/uk/gegc/quizmaker/features/document/application/impl/DocumentValidationServiceImpl.java†L57-L60】
  * Optional `chunkingStrategy` strings are case-insensitive and must map to the defined enum. (`IllegalArgumentException`).【F:src/main/java/uk/gegc/quizmaker/features/document/application/impl/DocumentValidationServiceImpl.java†L62-L69】

* **Reprocess request (`validateReprocessRequest`)**:
  * Request body cannot be `null`.
  * `chunkingStrategy` is mandatory and must be a valid enum value.
  * Optional `maxChunkSize` must remain within 100–100 000 characters. Violations raise `IllegalArgumentException` mapped to
    `400 Bad Request`.【F:src/main/java/uk/gegc/quizmaker/features/document/application/impl/DocumentValidationServiceImpl.java†L71-L90】

* **Authorization guard**: For document/chunk access, ownership is enforced by comparing the authenticated user with
  `Document.uploadedBy`. Non-matching users trigger `UserNotAuthorizedException` (`403 Forbidden`).【F:src/main/java/uk/gegc/quizmaker/features/document/application/impl/DocumentProcessingServiceImpl.java†L233-L308】

---

## Endpoints

### `POST /upload`【F:src/main/java/uk/gegc/quizmaker/features/document/api/DocumentController.java†L39-L77】

Upload and process a document for the authenticated user.

* **Headers**: `Authorization: Bearer <token>`.
* **Content-Type**: `multipart/form-data`.
* **Form Fields**:
  * `file` *(required)* – Document binary.
  * `chunkingStrategy` *(optional)* – Overrides default strategy (`AUTO`, `CHAPTER_BASED`, `SECTION_BASED`, `SIZE_BASED`, `PAGE_BASED`).
  * `maxChunkSize` *(optional, number)* – Overrides default maximum chunk size.
* **Response** (`201 Created`): `DocumentDto` including populated chunk list when `storeChunks` is true.
* **Failure Modes**:
  * `400 Bad Request` – Validation failures (`IllegalArgumentException`, `UnsupportedFileTypeException`).
  * `500 Internal Server Error` – Storage or processing issues (`DocumentStorageException`, `DocumentProcessingException`).

---

### `GET /{documentId}`【F:src/main/java/uk/gegc/quizmaker/features/document/api/DocumentController.java†L78-L101】

Fetch metadata (and cached chunks, if any) for a single document.

* **Path Params**: `documentId` (`UUID`).
* **Response** (`200 OK`): `DocumentDto`.
* **Failure Modes**:
  * `403 Forbidden` – Caller is not the uploader (`UserNotAuthorizedException` or runtime `"Access denied"`).
  * `404 Not Found` – Document missing (`DocumentNotFoundException`).
  * `500 Internal Server Error` – Unexpected runtime errors.

---

### `GET /`【F:src/main/java/uk/gegc/quizmaker/features/document/api/DocumentController.java†L103-L117】

Retrieve a paginated list of documents for the authenticated user.

* **Query Params**:
  * `page` *(optional)* – 0-indexed page (default `0`).
  * `size` *(optional)* – Page size (default `10`).
* **Response** (`200 OK`): Spring `Page<DocumentDto>` JSON structure.
* **Failure Modes**: `500 Internal Server Error` on unexpected errors.

---

### `GET /{documentId}/chunks`【F:src/main/java/uk/gegc/quizmaker/features/document/api/DocumentController.java†L119-L142】

Return all stored chunks for the given document.

* **Response** (`200 OK`): `DocumentChunkDto[]` sorted by `chunkIndex`.
* **Failure Modes**:
  * `403 Forbidden` – Unauthorized access attempt.
  * `404 Not Found` – Document absent.
  * `500 Internal Server Error` – Unexpected errors (including fallback when chunk lookup fails for other reasons).

---

### `GET /{documentId}/chunks/{chunkIndex}`【F:src/main/java/uk/gegc/quizmaker/features/document/api/DocumentController.java†L144-L168】

Fetch a single chunk by index.

* **Path Params**:
  * `documentId` (`UUID`).
  * `chunkIndex` (`number`).
* **Response** (`200 OK`): `DocumentChunkDto`.
* **Failure Modes**:
  * `403 Forbidden` – Unauthorized user.
  * `404 Not Found` – Missing document or chunk (runtime `"Chunk not found"`).
  * `500 Internal Server Error` – Unexpected errors.

---

### `DELETE /{documentId}`【F:src/main/java/uk/gegc/quizmaker/features/document/api/DocumentController.java†L171-L195】

Delete a document, its chunks, and the uploaded file.

* **Response** (`204 No Content`).
* **Failure Modes**:
  * `403 Forbidden` – Unauthorized user.
  * `404 Not Found` – Document missing.
  * `500 Internal Server Error` – Unexpected runtime errors.

---

### `POST /{documentId}/reprocess`【F:src/main/java/uk/gegc/quizmaker/features/document/api/DocumentController.java†L197-L219】

Reprocess an existing document with new chunking settings.

* **Request Body** (`application/json`): `ProcessDocumentRequest`.
* **Response** (`200 OK`): Updated `DocumentDto`.
* **Failure Modes**:
  * `400 Bad Request` – Validation failures (e.g., missing strategy, invalid chunk size).
  * `403 Forbidden` – Unauthorized user.
  * `404 Not Found` – Document missing.
  * `500 Internal Server Error` – Storage or processing failures.

---

### `GET /{documentId}/status`【F:src/main/java/uk/gegc/quizmaker/features/document/api/DocumentController.java†L221-L234】

Fetch the latest processing status for a document.

* **Response** (`200 OK`): `DocumentDto` (status-focused fields).
* **Failure Modes**:
  * `403 Forbidden` – Unauthorized user.
  * `404 Not Found` – Document missing.
  * `500 Internal Server Error` – Unexpected errors.

---

### `GET /config`【F:src/main/java/uk/gegc/quizmaker/features/document/api/DocumentController.java†L244-L250】

Retrieve server defaults for chunking configuration.

* **Response** (`200 OK`): `DocumentConfigDto`.
* **Failure Modes**: None expected (no authentication-dependent logic besides standard auth filter).

---

## Error Handling

Errors returned by these endpoints conform to `GlobalExceptionHandler.ErrorResponse` unless otherwise noted. Structure:

```json
{
  "timestamp": "2024-02-15T10:15:30.123",
  "status": 400,
  "error": "Bad Request",
  "details": ["Human-friendly message"]
}
```

Key exception → status mappings relevant to the document flow:

| Exception | HTTP Status | When it occurs |
| --- | --- | --- |
| `IllegalArgumentException` | `400 Bad Request` | Upload/reprocess validation failures, invalid chunk indices, etc. |
| `UnsupportedFileTypeException` | `400 Bad Request` | Uploading unsupported content types. |
| `DocumentNotFoundException` | `404 Not Found` | Document or chunk lookup misses. |
| Runtime `"Chunk not found"` | `404 Not Found` | Specific chunk missing. |
| `UserNotAuthorizedException` / runtime `"Access denied"` | `403 Forbidden` | Accessing another user's document. |
| `DocumentProcessingException` | `500 Internal Server Error` with error "Document Processing Error". |
| `DocumentStorageException` | `500 Internal Server Error` with error "Document Storage Error". |
| Unhandled runtime exceptions | `500 Internal Server Error` with generic message. |

Detailed mappings are implemented in `GlobalExceptionHandler`.【F:src/main/java/uk/gegc/quizmaker/shared/api/advice/GlobalExceptionHandler.java†L38-L234】

---

## Integration Playbooks

### Upload → Review Workflow

1. **Upload** via `POST /upload` (optionally override strategy/max size).
2. **Poll status** with `GET /{documentId}/status` until `status` becomes `PROCESSED` (or `FAILED`).
3. **Fetch chunks** with `GET /{documentId}/chunks` once processing completes.
4. **Handle failures** by showing `processingError` when `status` is `FAILED`.

### Reprocess Workflow

1. Collect new `ProcessDocumentRequest` settings from the user.
2. Validate on the client (e.g., chunk size range) to avoid extra round trips.
3. Call `POST /{documentId}/reprocess`.
4. Monitor `GET /{documentId}/status` for the updated processing state.

### Deletion Workflow

1. Confirm intent in UI (operation is irreversible).
2. Call `DELETE /{documentId}`.
3. Remove document from local cache or re-fetch `GET /` to refresh pagination.

---

## Security Considerations

* Ensure bearer tokens are attached to every request and refreshed when expired.
* Never expose raw chunk contents to unauthorized users — backend already enforces this, but clients should avoid caching
  sensitive content unnecessarily.
* Surface `403` responses as access-denied messaging; do not retry automatically.
* When surfacing errors, prefer messages from `details[]` for end-user display and log the `error`/`status` for diagnostics.