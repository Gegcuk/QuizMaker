# Document Conversion & Processing Controller API Reference

This guide documents the `/api/v1/documentProcess/documents` REST surface that powers document ingestion, conversion, and structure retrieval. It is intended to be a one-stop reference for frontend engineers and includes DTO specs, validation rules, endpoint behavior, and error semantics.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [DTO Catalogue](#dto-catalogue)
  - [Request DTOs](#request-dtos)
  - [Response DTOs](#response-dtos)
  - [Enumerations](#enumerations)
- [Endpoints](#endpoints)
  - [POST /api/v1/documentProcess/documents (JSON ingest)](#post-apiv1documentprocessdocuments-json-ingest)
  - [POST /api/v1/documentProcess/documents (file ingest)](#post-apiv1documentprocessdocuments-file-ingest)
  - [GET /api/v1/documentProcess/documents/{id}](#get-apiv1documentprocessdocumentsid)
  - [GET /api/v1/documentProcess/documents/{id}/head](#get-apiv1documentprocessdocumentsidhead)
  - [GET /api/v1/documentProcess/documents/{id}/text](#get-apiv1documentprocessdocumentsidtext)
  - [GET /api/v1/documentProcess/documents/{id}/structure](#get-apiv1documentprocessdocumentsidstructure)
  - [POST /api/v1/documentProcess/documents/{id}/structure](#post-apiv1documentprocessdocumentsidstructure)
  - [GET /api/v1/documentProcess/documents/{id}/extract](#get-apiv1documentprocessdocumentsidextract)
- [Common Error Semantics](#common-error-semantics)
- [Integration Workflows](#integration-workflows)
- [Security Notes](#security-notes)

---

## Overview

* **Base path:** `/api/v1/documentProcess/documents`
* **Primary capabilities:**
  * Convert uploaded files into normalized text.
  * Persist normalized documents and expose metadata.
  * Serve paginated text slices without re-sending the full payload.
  * Manage AI-generated document structures (tree & flat views, node extraction).
* **Content types:**
  * `application/json` for JSON ingest and most responses.
  * `multipart/form-data` for binary file ingest.
* **Response format:** JSON bodies for success and error payloads (unless explicitly noted).
* **Idempotency:** Uploading the same document twice creates distinct records—no deduplication occurs server-side.

---

## Authorization Matrix

All endpoints require a valid JWT unless otherwise stated. Security is enforced globally in `SecurityConfig`: any request not explicitly whitelisted must be authenticated.

| Capability | Endpoint(s) | AuthN | Ownership model | Notes |
| --- | --- | --- | --- | --- |
| Ingest raw text | `POST /` (JSON) | Required | Not resource-scoped | Produces a new `NormalizedDocument` owned by the requester. |
| Ingest file | `POST /` (multipart) | Required | Not resource-scoped | Converts before normalization; rejects unsupported formats. |
| Fetch document metadata | `GET /{id}`, `GET /{id}/head` | Required | User must own the document (enforced in service/repository layer). |
| Slice document text | `GET /{id}/text` | Required | Ownership enforced. |
| Structure views | `GET /{id}/structure` | Required | Ownership enforced. Returns tree or flat nodes. |
| Build structure | `POST /{id}/structure` | Required | Ownership enforced. Invokes AI/LLM pipeline. |
| Extract node text | `GET /{id}/extract` | Required | Ownership enforced; requires previously generated nodes. |

> **Admin overrides:** The codebase does not expose special admin routes for this feature. Any cross-user access would need to be implemented separately.

---

## DTO Catalogue

### Request DTOs

#### `IngestRequest`
Used for JSON ingestion. Validation occurs via Jakarta Bean Validation annotations.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `text` | `string` | Yes | `@NotBlank` | Raw document text to normalize and persist. |
| `language` | `string` | No | `@Size(max = 32)` | BCP-47 language tag or any backend-recognized code. |

Example:
```json
{
  "text": "# Linear Algebra Lecture Notes\n...",
  "language": "en"
}
```

#### Multipart ingest payload
For binary uploads the controller expects a `multipart/form-data` body:

| Part | Type | Required | Notes |
| --- | --- | --- | --- |
| `file` | binary | Yes | Raw file bytes. Empty files are rejected. |
| `originalName` | text | No | Overrides the server-detected filename (query parameter). |

### Response DTOs

The controller maps domain objects into the following DTOs via `DocumentMapper` and `DocumentNodeMapper`.

#### `IngestResponse`
```json
{
  "id": "1e0a0cb9-9b45-4d88-8a65-2ad7f93d51af",
  "status": "NORMALIZED"
}
```
| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID string | Identifier of the newly persisted normalized document. |
| `status` | `DocumentStatus` | Current processing state (see [Enumerations](#enumerations)). |

> A status of `FAILED` indicates that conversion or normalization failed; the document record still exists to allow audit/retry flows.

#### `DocumentView`
Full metadata projection.

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID string | Document identifier. |
| `originalName` | string | Filename provided or detected. |
| `mime` | string | MIME type determined via `MimeTypeDetector`. Defaults to `application/octet-stream` if unknown. |
| `source` | `DocumentSource` | Indicates whether the document originated from an upload or raw text. |
| `charCount` | integer | Number of characters in the normalized text. |
| `language` | string | Detected or user-specified language (nullable). |
| `status` | `DocumentStatus` | Current processing status. |
| `createdAt` | ISO-8601 timestamp | Creation timestamp. |
| `updatedAt` | ISO-8601 timestamp | Last update timestamp. |

#### `TextSliceResponse`
Represents a bounded substring of the normalized text.

| Field | Type | Description |
| --- | --- | --- |
| `documentId` | UUID string | Source document ID. |
| `start` | integer | Inclusive start offset. |
| `end` | integer | Exclusive end offset. (Will be clipped to the text length if the caller requests beyond the end.) |
| `text` | string | Extracted substring. |

#### `StructureTreeResponse`

```json
{
  "documentId": "...",
  "rootNodes": [NodeView, ...],
  "totalNodes": 24
}
```
| Field | Type | Description |
| --- | --- | --- |
| `documentId` | UUID string | Source document. |
| `rootNodes` | `NodeView[]` | Hierarchical structure starting at depth 0. Children are pre-sorted by `idx`. |
| `totalNodes` | integer | Total nodes persisted for the document. |

#### `StructureFlatResponse`

```json
{
  "documentId": "...",
  "nodes": [FlatNode, ...],
  "totalNodes": 24
}
```
| Field | Type | Description |
| --- | --- | --- |
| `nodes` | `FlatNode[]` | Linearized nodes sorted by `startOffset`. |

#### `NodeView`
Recursive representation used inside `StructureTreeResponse`.

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID string | Node ID. |
| `documentId` | UUID string | Owning document ID. |
| `parentId` | UUID string | Parent node ID (nullable for roots). |
| `idx` | integer | Position among siblings (0-based). |
| `type` | `NodeType` | Semantic node category. |
| `title` | string | Title extracted/assigned by AI. Nullable. |
| `startOffset` | integer | Inclusive character offset. |
| `endOffset` | integer | Exclusive character offset. |
| `depth` | integer | Depth from root (root = 0). |
| `aiConfidence` | decimal | Optional model confidence score (0–1). |
| `metaJson` | string | Raw metadata blob (JSON). |
| `children` | `NodeView[]` | Recursively nested children (sorted by `idx`). |

#### `FlatNode`
Non-recursive structure nodes returned by the flat format.

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID string | Node ID. |
| `documentId` | UUID string | Owning document ID. |
| `parentId` | UUID string | Parent node ID (nullable). |
| `idx` | integer | Sibling index. |
| `type` | `NodeType` | Semantic type. |
| `title` | string | Node label. |
| `startOffset` | integer | Inclusive offset. |
| `endOffset` | integer | Exclusive offset. |
| `depth` | integer | Depth level. |
| `aiConfidence` | decimal | Optional confidence value. |
| `metaJson` | string | Raw metadata JSON (nullable). |

#### `ExtractResponse`
Payload returned by `GET /{id}/extract`.

| Field | Type | Description |
| --- | --- | --- |
| `documentId` | UUID string | Document ID. |
| `nodeId` | UUID string | Extracted node ID. |
| `title` | string | Node title (nullable). |
| `start` | integer | Inclusive offset. |
| `end` | integer | Exclusive offset. |
| `text` | string | Text slice corresponding to the node. |

#### `StructureBuildResponse`
Returned by structure build requests.

| Field | Type | Description |
| --- | --- | --- |
| `status` | string | `"STRUCTURED"` on success, `"FAILED"` on validation issues, `"ERROR"` on unexpected failures. |
| `message` | string | Human-readable summary. |

### Enumerations

#### `DocumentStatus`
Defined on `NormalizedDocument`.

| Value | Meaning |
| --- | --- |
| `PENDING` | Document has been registered but not yet normalized (transient state during ingestion). |
| `NORMALIZED` | Conversion + normalization succeeded; text is available for slicing and structuring. |
| `FAILED` | Conversion or normalization failed; no text is available. |
| `STRUCTURED` | Structure generation succeeded and nodes were persisted. |

#### `DocumentSource`

| Value | Meaning |
| --- | --- |
| `UPLOAD` | Document originated from a file upload. |
| `TEXT` | Document was created from direct text input. |

#### `NodeType`

| Value | Example usage |
| --- | --- |
| `PART` | Large multi-section grouping. |
| `BOOK` | Entire book container. |
| `CHAPTER` | Chapter-level nodes. |
| `SECTION` | Standard section headings. |
| `SUBSECTION` | Nested subsection headings. |
| `PARAGRAPH` | Paragraph-level content. |
| `UTTERANCE` | Transcript sentences/lines. |
| `OTHER` | Fallback type when classification is unknown. |

---

## Endpoints

### POST /api/v1/documentProcess/documents (JSON ingest)

**Purpose:** Persist a text document without file conversion.

**Request headers:**
- `Authorization: Bearer <token>`
- `Content-Type: application/json`

**Body:** `IngestRequest`.

**Success response:**
- `201 Created`
- `Location` header: `/api/v1/documentProcess/documents/{id}`
- Body: `IngestResponse`

**Failure modes:**
- `400 Bad Request` — Missing/blank `text`, invalid `language`, malformed JSON.
- `401 Unauthorized` — Missing or invalid JWT.
- `500 Internal Server Error` — Unexpected ingestion failure (rare; ingestion service internally downgrades to `FAILED` status otherwise).

> Even when normalization fails, the backend persists a `FAILED` document and still responds with `201`. Clients should inspect `status` and surface failure states to the user.

### POST /api/v1/documentProcess/documents (file ingest)

**Purpose:** Convert an uploaded file to text, normalize it, and persist the document.

**Request headers:**
- `Authorization: Bearer <token>`
- `Content-Type: multipart/form-data`

**Body parts:** `file` (required), optional query or form field `originalName`.

**Success response:**
- `201 Created`
- `Location`: `/api/v1/documentProcess/documents/{id}`
- Body: `IngestResponse`

**Failure modes:**
- `400 Bad Request` — Missing `file` part, empty upload, illegal `nodeId` format, or other validation errors.
- `401 Unauthorized` — Missing/invalid JWT.
- `415 Unsupported Media Type` — `UnsupportedFormatException` thrown when no converter supports the file extension/MIME.
- `422 Unprocessable Entity` — Conversion pipeline failed (e.g., corrupt PDF), normalization failure, or illegal document state.
- `500 Internal Server Error` — Unhandled server error.

### GET /api/v1/documentProcess/documents/{id}

**Purpose:** Retrieve complete metadata for a normalized document.

**Request headers:** `Authorization: Bearer <token>`

**Success response:**
- `200 OK`
- Body: `DocumentView`

**Failure modes:**
- `401 Unauthorized`
- `403 Forbidden` — Authenticated user is not allowed to access the document (enforced downstream).
- `404 Not Found` — Document ID not present.

### GET /api/v1/documentProcess/documents/{id}/head

**Purpose:** Lightweight metadata fetch that avoids loading the full text body. Returns the same payload as `/documents/{id}` but is intended for quick status polls.

**Success/Failure:** Same semantics as `GET /{id}`.

### GET /api/v1/documentProcess/documents/{id}/text

**Purpose:** Fetch a substring of the normalized text.

**Query parameters:**

| Name | Type | Default | Validation | Notes |
| --- | --- | --- | --- | --- |
| `start` | integer | `0` | `@Min(0)` | Inclusive offset. |
| `end` | integer | `null` (defaults to char count) | `@Min(0)` | Exclusive offset. If omitted or larger than the text length, it will be clipped to the end. |

**Success response:**
- `200 OK`
- Body: `TextSliceResponse`

**Failure modes:**
- `400 Bad Request` — `start < 0`, `end < start`, or offsets exceed document length (raised as `ValidationErrorException`).
- `401 Unauthorized`
- `403 Forbidden`
- `404 Not Found` — Document missing.
- `422 Unprocessable Entity` — Document exists but has no normalized text yet (`IllegalStateException`).

### GET /api/v1/documentProcess/documents/{id}/structure

**Purpose:** Retrieve stored structure nodes in tree or flat format.

**Query parameters:**

| Name | Values | Default | Notes |
| --- | --- | --- | --- |
| `format` | `tree` \| `flat` | `tree` | Determines whether to return nested `NodeView` objects or a flat list. |

**Success responses:**
- `200 OK`
  - Body: `StructureTreeResponse` when `format=tree`.
  - Body: `StructureFlatResponse` when `format=flat`.

**Failure modes:**
- `400 Bad Request` — Unsupported `format` value (string body message).
- `401 Unauthorized`
- `403 Forbidden`
- `404 Not Found` — Document absent.

> Structure responses include whatever nodes currently exist. If no AI processing has been run, both `rootNodes`/`nodes` will be empty with `totalNodes = 0`.

### POST /api/v1/documentProcess/documents/{id}/structure

**Purpose:** Trigger AI-backed structure generation.

**Request headers:** `Authorization: Bearer <token>`

**Success response:**
- `200 OK`
- Body: `StructureBuildResponse` with `status = "STRUCTURED"`

**Failure modes:**
- `400 Bad Request` — Illegal document state (e.g., document not `NORMALIZED`, missing text) returns `status = "FAILED"`.
- `401 Unauthorized`
- `403 Forbidden`
- `404 Not Found` — Document ID not found.
- `422 Unprocessable Entity` — AI generation failed mid-way (surfaced as `IllegalStateException`).
- `500 Internal Server Error` — Unexpected exceptions return `status = "ERROR"`.

> Structure generation is synchronous. Large documents may take multiple seconds as the service chunk-splits text and aggregates AI responses.

### GET /api/v1/documentProcess/documents/{id}/extract

**Purpose:** Retrieve the exact text span represented by a persisted node.

**Query parameters:**

| Name | Type | Required | Notes |
| --- | --- | --- | --- |
| `nodeId` | UUID string | Yes | Must belong to the target document. |

**Success response:**
- `200 OK`
- Body: `ExtractResponse`

**Failure modes:**
- `400 Bad Request` — Node belongs to another document or invalid query parameter.
- `401 Unauthorized`
- `403 Forbidden`
- `404 Not Found` — Document or node ID missing.
- `422 Unprocessable Entity` — Node lacks offsets or the parent document has no normalized text.

---

## Common Error Semantics

All controller methods rely on the shared `GlobalExceptionHandler`. Error payloads follow a uniform structure:

```json
{
  "timestamp": "2024-03-18T09:41:27.522431",
  "status": 415,
  "error": "Unsupported Format",
  "details": [
    "No suitable converter found for: syllabus.pages"
  ]
}
```

Key mappings relevant to conversion & processing:

| Status | Trigger |
| --- | --- |
| `400 Bad Request` | Validation issues (`ValidationErrorException`, `IllegalArgumentException`, unsupported structure format). |
| `401 Unauthorized` | Missing/invalid JWT. |
| `403 Forbidden` | Authorization failure. |
| `404 Not Found` | `ResourceNotFoundException` (document/node missing). |
| `415 Unsupported Media Type` | `UnsupportedFormatException` during file ingest. |
| `422 Unprocessable Entity` | `ConversionFailedException`, `NormalizationFailedException`, `IllegalStateException` (e.g., node offsets invalid, document lacks text). |
| `500 Internal Server Error` | Unhandled runtime exceptions during ingestion or structure builds (controller catches some and returns `status = "ERROR"`). |

When `POST /{id}/structure` handles errors locally it returns `StructureBuildResponse` with `status = FAILED` or `ERROR` alongside `400` or `500` respectively—still inspect the body for user messaging.

---

## Integration Workflows

### 1. Text ingestion + review
1. Call `POST /documents` (JSON) with raw text.
2. Store `id` from `IngestResponse`. If `status = FAILED`, prompt the user to revise their input.
3. Poll `GET /documents/{id}/head` to watch for status transitions (e.g., from `PENDING` to `NORMALIZED`).
4. Use `GET /documents/{id}` when you need full metadata.
5. Present text slices via `GET /documents/{id}/text` with paging offsets.

### 2. File upload + structure visualization
1. Upload the file via `POST /documents` (multipart). Handle `415` for unsupported extensions.
2. Wait for `status = NORMALIZED`.
3. Trigger structure generation using `POST /documents/{id}/structure`.
4. Fetch hierarchical nodes with `GET /documents/{id}/structure?format=tree`.
5. Allow users to click nodes and call `GET /documents/{id}/extract?nodeId=...` for contextual previews.

### 3. Handling large documents
- Use `GET /documents/{id}/text` to fetch progressive slices instead of requesting the entire body.
- Monitor `StructureBuildResponse`—the backend automatically chunk-splits large inputs, but failures are surfaced as `status = FAILED/ERROR` with descriptive messages.
- If structure generation fails, surfaces the message and allow retry after the user edits content or waits.

---

## Security Notes

- **Authentication:** All routes are behind JWT authentication; ensure tokens are refreshed ahead of expiry.
- **Ownership:** The services invoked by this controller enforce that users only access their own documents. Treat `403` as a signal that the document belongs to another account.
- **Data sensitivity:** Do not cache normalized text or structure responses in shared storage without considering document confidentiality.
- **Uploads:** Always use HTTPS for file uploads. Client-side validation (size/type) helps users receive quicker feedback before the server rejects unsupported files.
- **Error transparency:** Show `details` messages from error payloads when appropriate—they are phrased for user comprehension (e.g., "Document conversion failed: ...").

---

Need more detail? Reach out to the backend team with the document ID and request correlation logs for investigation.