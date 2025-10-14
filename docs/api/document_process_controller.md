# Document Process Controller API Reference

Complete frontend integration guide for `/api/v1/documentProcess/documents` REST endpoints. This document is self-contained and includes all DTOs, validation rules, and error semantics needed to integrate document ingestion, conversion, and structure extraction features.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Enumerations](#enumerations)
- [Endpoints](#endpoints)
  - [Document Ingestion](#document-ingestion)
  - [Document Retrieval](#document-retrieval)
  - [Text Slicing](#text-slicing)
  - [Structure Management](#structure-management)
  - [Node Extraction](#node-extraction)
- [Error Handling](#error-handling)
- [Integration Guide](#integration-guide)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/v1/documentProcess/documents`
* **Primary Capabilities**:
  - Convert uploaded files (PDF, EPUB, TXT) into normalized text
  - Persist normalized documents with metadata
  - Serve paginated text slices
  - Generate AI-powered document structures (hierarchical tree)
  - Extract text by structural node
* **Authentication**: Required for all endpoints. Uses JWT Bearer token in `Authorization` header.
* **Authorization Model**: Ownership-based. Users can only access their own documents.
* **Content Types**:
  - `application/json` for JSON ingest and responses
  - `multipart/form-data` for file uploads
* **Error Format**: All errors return `ErrorResponse` object (except some structure format errors)
* **Idempotency**: No automatic deduplication - same document uploaded twice creates distinct records

---

## Authorization Matrix

All endpoints require authentication and enforce ownership-based access control.

| Capability | Endpoint(s) | Authorization Rule | Notes |
| --- | --- | --- | --- |
| **Ingest text** | `POST /` (JSON) | Authenticated user | Creates document owned by requester |
| **Upload file** | `POST /` (multipart) | Authenticated user | Converts and normalizes file |
| **View metadata** | `GET /{id}`, `GET /{id}/head` | User must own document | Returns 403 if not owner |
| **Slice text** | `GET /{id}/text` | User must own document | Paginated text access |
| **View structure** | `GET /{id}/structure` | User must own document | Tree or flat format |
| **Build structure** | `POST /{id}/structure` | User must own document | Triggers AI processing |
| **Extract node** | `GET /{id}/extract` | User must own document | Node text extraction |

**Ownership Enforcement**:
- All documents automatically associated with authenticated user
- Service layer validates ownership for all operations
- 403 Forbidden returned if user doesn't own document
- No admin overrides currently implemented

---

## Request DTOs

### IngestRequest

**Used by**: `POST /` (JSON body)

Ingest raw text directly without file conversion.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `text` | string | Yes | Non-blank | Raw document text to normalize |
| `language` | string | No | Max 32 characters | Language code (e.g., "en", "es") |

**Example**:
```json
{
  "text": "# Linear Algebra Lecture Notes\n\nChapter 1: Vectors\n\nA vector is a mathematical object...",
  "language": "en"
}
```

**Notes**:
- `text` cannot be null, empty, or only whitespace
- `language` is optional, uses BCP-47 format or recognized codes
- Server normalizes text (whitespace cleanup, encoding fixes)

---

### Multipart File Upload

**Used by**: `POST /` (multipart/form-data)

Upload a document file for conversion and normalization.

| Part/Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `file` | binary | Yes | Document file to upload |
| `originalName` | string (query/form) | No | Override filename (defaults to uploaded filename or "upload.bin") |

**Example Request**:
```
POST /api/v1/documentProcess/documents
Content-Type: multipart/form-data

file: [binary file data]
originalName: "lecture-notes.pdf" (optional)
```

**Supported File Types**:
- PDF (`.pdf`)
- EPUB (`.epub`)
- Text (`.txt`)
- Other formats may be supported based on server configuration

**Validation**:
- File cannot be null or empty
- MIME type auto-detected server-side
- Unsupported formats rejected with 415

---

## Response DTOs

### IngestResponse

**Returned by**: `POST /` (both JSON and multipart)

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Newly created document identifier |
| `status` | `DocumentStatus` enum | Processing status |

**Example (Success)**:
```json
{
  "id": "1e0a0cb9-9b45-4d88-8a65-2ad7f93d51af",
  "status": "NORMALIZED"
}
```

**Example (Failed)**:
```json
{
  "id": "1e0a0cb9-9b45-4d88-8a65-2ad7f93d51af",
  "status": "FAILED"
}
```

**Important**:
- Server returns `201 Created` even if normalization fails
- Always check `status` field
- `FAILED` status indicates conversion/normalization error
- Document still persisted for troubleshooting

---

### DocumentView

**Returned by**: `GET /{id}`, `GET /{id}/head`

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Document identifier |
| `originalName` | string | Filename (provided or detected) |
| `mime` | string | MIME type (e.g., "application/pdf", "text/plain") |
| `source` | `DocumentSource` enum | Origin: `UPLOAD` or `TEXT` |
| `charCount` | integer (nullable) | Number of characters in normalized text |
| `language` | string (nullable) | Detected or specified language |
| `status` | `DocumentStatus` enum | Current processing status |
| `createdAt` | ISO 8601 datetime | Creation timestamp |
| `updatedAt` | ISO 8601 datetime | Last update timestamp |

**Example**:
```json
{
  "id": "1e0a0cb9-9b45-4d88-8a65-2ad7f93d51af",
  "originalName": "java-tutorial.pdf",
  "mime": "application/pdf",
  "source": "UPLOAD",
  "charCount": 45680,
  "language": "en",
  "status": "STRUCTURED",
  "createdAt": "2024-01-15T10:00:00.000Z",
  "updatedAt": "2024-01-15T10:05:30.000Z"
}
```

---

### TextSliceResponse

**Returned by**: `GET /{id}/text`

| Field | Type | Description |
| --- | --- | --- |
| `documentId` | UUID | Source document ID |
| `start` | integer | Inclusive start offset |
| `end` | integer | Exclusive end offset (auto-clipped to doc length) |
| `text` | string | Extracted substring |

**Example**:
```json
{
  "documentId": "1e0a0cb9-9b45-4d88-8a65-2ad7f93d51af",
  "start": 0,
  "end": 500,
  "text": "# Linear Algebra Lecture Notes\n\nChapter 1: Vectors\n\nA vector is a mathematical object that has both magnitude and direction..."
}
```

---

### StructureTreeResponse

**Returned by**: `GET /{id}/structure?format=tree`

| Field | Type | Description |
| --- | --- | --- |
| `documentId` | UUID | Source document ID |
| `rootNodes` | array of `NodeView` | Top-level nodes (depth 0) |
| `totalNodes` | integer (long) | Total number of nodes in tree |

**NodeView** structure (recursive):

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Node identifier |
| `documentId` | UUID | Parent document ID |
| `parentId` | UUID (nullable) | Parent node ID (null for roots) |
| `idx` | integer | Position among siblings (0-based) |
| `type` | `NodeType` enum | Semantic node type |
| `title` | string (nullable) | Node title/heading |
| `startOffset` | integer | Start character position (inclusive) |
| `endOffset` | integer | End character position (exclusive) |
| `depth` | integer (short) | Depth from root (0 = root) |
| `aiConfidence` | number (nullable) | AI confidence score (0-1) |
| `metaJson` | string (nullable) | Additional metadata as JSON |
| `children` | array of `NodeView` | Nested child nodes (sorted by idx) |

**Example**:
```json
{
  "documentId": "doc-uuid",
  "rootNodes": [
    {
      "id": "node-uuid-1",
      "documentId": "doc-uuid",
      "parentId": null,
      "idx": 0,
      "type": "CHAPTER",
      "title": "Introduction to Linear Algebra",
      "startOffset": 0,
      "endOffset": 5000,
      "depth": 0,
      "aiConfidence": 0.95,
      "metaJson": null,
      "children": [
        {
          "id": "node-uuid-2",
          "documentId": "doc-uuid",
          "parentId": "node-uuid-1",
          "idx": 0,
          "type": "SECTION",
          "title": "What are Vectors?",
          "startOffset": 100,
          "endOffset": 1200,
          "depth": 1,
          "aiConfidence": 0.92,
          "metaJson": null,
          "children": []
        },
        {
          "id": "node-uuid-3",
          "documentId": "doc-uuid",
          "parentId": "node-uuid-1",
          "idx": 1,
          "type": "SECTION",
          "title": "Vector Operations",
          "startOffset": 1200,
          "endOffset": 3500,
          "depth": 1,
          "aiConfidence": 0.89,
          "metaJson": null,
          "children": []
        }
      ]
    }
  ],
  "totalNodes": 3
}
```

---

### StructureFlatResponse

**Returned by**: `GET /{id}/structure?format=flat`

| Field | Type | Description |
| --- | --- | --- |
| `documentId` | UUID | Source document ID |
| `nodes` | array of `FlatNode` | All nodes in linear order (sorted by startOffset) |
| `totalNodes` | integer (long) | Total number of nodes |

**FlatNode** structure (non-recursive):

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Node identifier |
| `documentId` | UUID | Parent document ID |
| `parentId` | UUID (nullable) | Parent node ID |
| `idx` | integer | Sibling position |
| `type` | `NodeType` enum | Node type |
| `title` | string (nullable) | Node title |
| `startOffset` | integer | Start position (inclusive) |
| `endOffset` | integer | End position (exclusive) |
| `depth` | integer (short) | Tree depth level |
| `aiConfidence` | number (nullable) | AI confidence score |
| `metaJson` | string (nullable) | Metadata JSON |

**Example**:
```json
{
  "documentId": "doc-uuid",
  "nodes": [
    {
      "id": "node-uuid-1",
      "documentId": "doc-uuid",
      "parentId": null,
      "idx": 0,
      "type": "CHAPTER",
      "title": "Introduction",
      "startOffset": 0,
      "endOffset": 5000,
      "depth": 0,
      "aiConfidence": 0.95,
      "metaJson": null
    },
    {
      "id": "node-uuid-2",
      "documentId": "doc-uuid",
      "parentId": "node-uuid-1",
      "idx": 0,
      "type": "SECTION",
      "title": "Overview",
      "startOffset": 100,
      "endOffset": 1200,
      "depth": 1,
      "aiConfidence": 0.92,
      "metaJson": null
    }
  ],
  "totalNodes": 2
}
```

---

### ExtractResponse

**Returned by**: `GET /{id}/extract`

| Field | Type | Description |
| --- | --- | --- |
| `documentId` | UUID | Document identifier |
| `nodeId` | UUID | Extracted node identifier |
| `title` | string (nullable) | Node title |
| `start` | integer | Start offset (inclusive) |
| `end` | integer | End offset (exclusive) |
| `text` | string | Extracted text content |

**Example**:
```json
{
  "documentId": "doc-uuid",
  "nodeId": "node-uuid-2",
  "title": "Overview",
  "start": 100,
  "end": 1200,
  "text": "This chapter provides an overview of linear algebra concepts including vectors, matrices, and linear transformations..."
}
```

---

### StructureBuildResponse

**Returned by**: `POST /{id}/structure`

| Field | Type | Description |
| --- | --- | --- |
| `status` | string | "STRUCTURED", "FAILED", or "ERROR" |
| `message` | string | Human-readable result message |

**Example (Success)**:
```json
{
  "status": "STRUCTURED",
  "message": "Document structure generated successfully with 24 nodes"
}
```

**Example (Failure)**:
```json
{
  "status": "FAILED",
  "message": "Document must be NORMALIZED before structure generation"
}
```

**Example (Error)**:
```json
{
  "status": "ERROR",
  "message": "An unexpected error occurred during structure generation"
}
```

**HTTP Status Codes**:
- `status: "STRUCTURED"` → HTTP 200
- `status: "FAILED"` → HTTP 400
- `status: "ERROR"` → HTTP 500

---

## Enumerations

### DocumentStatus

Processing lifecycle states.

| Value | Description |
| --- | --- |
| `PENDING` | Document registered, conversion in progress (transient) |
| `NORMALIZED` | Conversion and normalization succeeded |
| `FAILED` | Conversion or normalization failed |
| `STRUCTURED` | Structure generation completed successfully |

**Status Flow**:
```
Text Ingest:   TEXT → NORMALIZED → STRUCTURED
                         ↓
File Upload:   UPLOAD → PENDING → NORMALIZED → STRUCTURED
                           ↓          ↓
                        FAILED     FAILED
```

---

### DocumentSource

| Value | Description |
| --- | --- |
| `UPLOAD` | Created from file upload |
| `TEXT` | Created from direct text input |

---

### NodeType

Semantic classification of document structure nodes.

| Value | Description | Typical Use |
| --- | --- | --- |
| `BOOK` | Entire book container | Top-level for books |
| `PART` | Large multi-section grouping | Book parts (Part I, Part II) |
| `CHAPTER` | Chapter-level divisions | Standard chapters |
| `SECTION` | Section headings | Within chapters |
| `SUBSECTION` | Nested subsections | Deeper structure |
| `PARAGRAPH` | Paragraph-level content | Fine-grained structure |
| `UTTERANCE` | Transcript sentences/lines | For dialogue/transcripts |
| `OTHER` | Unknown/unclassified | Fallback type |

---

## Endpoints

### Document Ingestion

#### 1. Ingest Text (JSON)

```
POST /api/v1/documentProcess/documents
Content-Type: application/json
```

**Query Parameters** (optional):
- `originalName` (string) - Override filename in metadata

**Request Body**: `IngestRequest`
```json
{
  "text": "# Machine Learning Basics\n\nMachine learning is a subset of AI...",
  "language": "en"
}
```

**Success Response**: `201 Created`
```json
{
  "id": "1e0a0cb9-9b45-4d88-8a65-2ad7f93d51af",
  "status": "NORMALIZED"
}
```

**Headers**:
- `Location: /api/v1/documentProcess/documents/{id}`

**Error Responses**:
- `400` - Validation error (blank text, invalid language)
- `401` - Unauthorized
- `422` - Normalization failed
- `500` - Unexpected error

**Notes**:
- Returns 201 even if normalization fails (check `status`)
- Text is immediately normalized
- Document ready for text slicing right away

---

#### 2. Upload File (Multipart)

```
POST /api/v1/documentProcess/documents
Content-Type: multipart/form-data
```

**Form Fields**:
- `file` (required) - Document file
- `originalName` (optional, query parameter) - Custom filename

**Success Response**: `201 Created` - `IngestResponse`

**Headers**:
- `Location: /api/v1/documentProcess/documents/{id}`

**Error Responses**:
- `400` - Missing file, empty file, invalid parameters
- `401` - Unauthorized
- `415` - Unsupported file type
- `422` - Conversion failed, normalization failed
- `500` - Unexpected error

**Example (JavaScript)**:
```javascript
const uploadFile = async (file) => {
  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch('/api/v1/documentProcess/documents', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`
    },
    body: formData
  });

  if (response.status === 415) {
    throw new Error('Unsupported file type');
  }

  if (!response.ok) {
    throw new Error('Upload failed');
  }

  const result = await response.json();
  
  if (result.status === 'FAILED') {
    throw new Error('Document conversion failed');
  }

  return result.id;
};
```

---

### Document Retrieval

#### 3. Get Document Metadata

```
GET /api/v1/documentProcess/documents/{id}
```

**Path Parameters**:
- `{id}` - Document UUID

**Success Response**: `200 OK` - `DocumentView`

**Error Responses**:
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document not found

**Notes**:
- Returns full metadata including timestamps
- Does NOT include text content (use text endpoint)
- Use for status checks and document info display

---

#### 4. Get Document Head (Lightweight)

```
GET /api/v1/documentProcess/documents/{id}/head
```

**Path Parameters**:
- `{id}` - Document UUID

**Success Response**: `200 OK` - `DocumentView`

**Error Responses**:
- Same as GET /{id}

**Notes**:
- Returns same data as `GET /{id}`
- Intended for lightweight status polling
- Use when you only need status, not full metadata

---

### Text Slicing

#### 5. Get Text Slice

```
GET /api/v1/documentProcess/documents/{id}/text
```

**Path Parameters**:
- `{id}` - Document UUID

**Query Parameters**:
- `start` (integer, optional) - Start offset (inclusive), default: 0, min: 0
- `end` (integer, optional) - End offset (exclusive), default: document length, min: 0

**Success Response**: `200 OK` - `TextSliceResponse`

**Example URLs**:
```
GET /api/v1/documentProcess/documents/{id}/text
GET /api/v1/documentProcess/documents/{id}/text?start=0&end=1000
GET /api/v1/documentProcess/documents/{id}/text?start=1000&end=2000
```

**Error Responses**:
- `400` - Invalid offsets (start < 0, end < start)
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document not found
- `422` - Document has no normalized text

**Notes**:
- `end` auto-clipped if exceeds document length
- Omitting `end` returns text from `start` to end of document
- Use for progressive loading of large documents
- Offsets are character-based (not byte-based)

---

### Structure Management

#### 6. Get Document Structure

```
GET /api/v1/documentProcess/documents/{id}/structure
```

**Path Parameters**:
- `{id}` - Document UUID

**Query Parameters**:
- `format` (string, optional) - "tree" or "flat", default: "tree"

**Success Response**: `200 OK`
- format=tree: `StructureTreeResponse`
- format=flat: `StructureFlatResponse`

**Error Responses**:
- `400` - Invalid format value (returns plain text: "Invalid format. Use 'tree' or 'flat'")
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document not found

**Notes**:
- Returns empty nodes if structure not yet generated (`totalNodes: 0`)
- Tree format: Hierarchical with nested children
- Flat format: Linear list sorted by startOffset
- Use tree for navigation UI, flat for analysis

---

#### 7. Build Document Structure

```
POST /api/v1/documentProcess/documents/{id}/structure
```

**Path Parameters**:
- `{id}` - Document UUID

**No Request Body**

**Success Response**: `200 OK` - `StructureBuildResponse`
```json
{
  "status": "STRUCTURED",
  "message": "Document structure generated successfully with 24 nodes"
}
```

**Error Responses**:
- `400` - Pre-condition failure (document not NORMALIZED)
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document not found
- `422` - AI generation failed mid-process
- `500` - Unexpected error

**Example**:
```javascript
const buildStructure = async (documentId) => {
  const response = await fetch(
    `/api/v1/documentProcess/documents/${documentId}/structure`,
    {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}` }
    }
  );

  const result = await response.json();

  if (result.status === 'STRUCTURED') {
    console.log('✓ Structure built:', result.message);
    return true;
  } else if (result.status === 'FAILED') {
    console.error('✗ Build failed:', result.message);
    return false;
  } else {
    console.error('✗ Error:', result.message);
    return false;
  }
};
```

**Notes**:
- Synchronous operation (may take several seconds for large documents)
- Requires document status = NORMALIZED
- Uses AI to detect document structure
- Creates hierarchical node tree
- Show loading indicator during processing
- Document status transitions to STRUCTURED on success

---

### Node Extraction

#### 8. Extract Node Text

```
GET /api/v1/documentProcess/documents/{id}/extract
```

**Path Parameters**:
- `{id}` - Document UUID

**Query Parameters**:
- `nodeId` (UUID, required) - Node to extract text from

**Success Response**: `200 OK` - `ExtractResponse`

**Example URL**:
```
GET /api/v1/documentProcess/documents/{id}/extract?nodeId=node-uuid-here
```

**Error Responses**:
- `400` - Node belongs to different document or invalid UUID
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document or node not found
- `422` - Node lacks offsets or document has no text

**Example**:
```javascript
const extractNodeText = async (documentId, nodeId) => {
  const response = await fetch(
    `/api/v1/documentProcess/documents/${documentId}/extract?nodeId=${nodeId}`,
    { headers: { 'Authorization': `Bearer ${token}` } }
  );

  if (!response.ok) {
    throw new Error('Failed to extract node');
  }

  const extraction = await response.json();
  return extraction.text;
};
```

---

## Error Handling

### ErrorResponse Format

```json
{
  "timestamp": "2024-01-15T10:30:00.000Z",
  "status": 400,
  "error": "Bad Request",
  "details": [
    "text: must not be blank"
  ]
}
```

### Common HTTP Status Codes

| Code | Meaning | When to Expect |
| --- | --- | --- |
| `200` | OK | Successful GET or structure build |
| `201` | Created | Successful ingestion (even if normalization failed) |
| `400` | Bad Request | Validation errors, invalid parameters, pre-condition failures |
| `401` | Unauthorized | Missing or invalid authentication token |
| `403` | Forbidden | Not document owner |
| `404` | Not Found | Document or node doesn't exist |
| `415` | Unsupported Media Type | Unsupported file format |
| `422` | Unprocessable Entity | Conversion failed, normalization failed, missing text |
| `500` | Internal Server Error | Unexpected errors |

### Common Error Scenarios

**Blank Text Input**:
```json
{
  "status": 400,
  "error": "Bad Request",
  "details": ["text: must not be blank"]
}
```

**Unsupported File Type**:
```json
{
  "status": 415,
  "error": "Unsupported Media Type",
  "details": ["No suitable converter found for: document.xyz"]
}
```

**Invalid Text Offsets**:
```json
{
  "status": 400,
  "error": "Bad Request",
  "details": ["Start offset must be non-negative"]
}
```

**Document Not Normalized**:
```json
{
  "status": 400,
  "error": "Bad Request",
  "details": ["Document must be NORMALIZED before structure generation"]
}
```
(In StructureBuildResponse):
```json
{
  "status": "FAILED",
  "message": "Document must be NORMALIZED before structure generation"
}
```

**Not Document Owner**:
```json
{
  "status": 403,
  "error": "Forbidden",
  "details": ["Access denied to document"]
}
```

**Invalid Structure Format**:
```
Invalid format. Use 'tree' or 'flat'
```
(Plain text response, not JSON)

---

## Integration Guide

### Complete Document Processing Flow

```javascript
class DocumentProcessor {
  constructor(token) {
    this.token = token;
    this.baseUrl = '/api/v1/documentProcess/documents';
  }

  // 1. Upload file
  async uploadFile(file, originalName = null) {
    const formData = new FormData();
    formData.append('file', file);

    const url = originalName 
      ? `${this.baseUrl}?originalName=${encodeURIComponent(originalName)}`
      : this.baseUrl;

    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${this.token}` },
      body: formData
    });

    if (response.status === 415) {
      throw new Error(`Unsupported file type: ${file.type}`);
    }

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.details?.[0] || 'Upload failed');
    }

    const result = await response.json();

    if (result.status === 'FAILED') {
      throw new Error('Document conversion failed');
    }

    return result.id;
  }

  // 2. Ingest text
  async ingestText(text, language = 'en', originalName = 'text-input') {
    const url = `${this.baseUrl}?originalName=${encodeURIComponent(originalName)}`;

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ text, language })
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.details?.[0] || 'Ingest failed');
    }

    const result = await response.json();

    if (result.status === 'FAILED') {
      throw new Error('Text normalization failed');
    }

    return result.id;
  }

  // 3. Poll until normalized
  async waitForNormalization(documentId, maxAttempts = 60) {
    for (let i = 0; i < maxAttempts; i++) {
      const response = await fetch(`${this.baseUrl}/${documentId}/head`, {
        headers: { 'Authorization': `Bearer ${this.token}` }
      });

      const doc = await response.json();

      if (doc.status === 'NORMALIZED' || doc.status === 'STRUCTURED') {
        return doc;
      } else if (doc.status === 'FAILED') {
        throw new Error('Document normalization failed');
      }

      // Still PENDING
      await new Promise(r => setTimeout(r, 1000));
    }

    throw new Error('Timeout waiting for normalization');
  }

  // 4. Get metadata
  async getMetadata(documentId) {
    const response = await fetch(`${this.baseUrl}/${documentId}`, {
      headers: { 'Authorization': `Bearer ${this.token}` }
    });

    if (!response.ok) {
      throw new Error('Failed to get metadata');
    }

    return await response.json();
  }

  // 5. Get text slice
  async getTextSlice(documentId, start = 0, end = null) {
    const params = new URLSearchParams({ start: start.toString() });
    if (end !== null) {
      params.append('end', end.toString());
    }

    const response = await fetch(
      `${this.baseUrl}/${documentId}/text?${params}`,
      { headers: { 'Authorization': `Bearer ${this.token}` } }
    );

    if (!response.ok) {
      throw new Error('Failed to get text slice');
    }

    return await response.json();
  }

  // 6. Build structure
  async buildStructure(documentId) {
    const response = await fetch(`${this.baseUrl}/${documentId}/structure`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${this.token}` }
    });

    const result = await response.json();

    if (result.status !== 'STRUCTURED') {
      throw new Error(result.message);
    }

    return result;
  }

  // 7. Get structure
  async getStructure(documentId, format = 'tree') {
    const response = await fetch(
      `${this.baseUrl}/${documentId}/structure?format=${format}`,
      { headers: { 'Authorization': `Bearer ${this.token}` } }
    );

    if (!response.ok) {
      // Check for plain text error
      const contentType = response.headers.get('Content-Type');
      if (contentType?.includes('text/plain')) {
        const text = await response.text();
        throw new Error(text);
      }
      throw new Error('Failed to get structure');
    }

    return await response.json();
  }

  // 8. Extract node text
  async extractNode(documentId, nodeId) {
    const response = await fetch(
      `${this.baseUrl}/${documentId}/extract?nodeId=${nodeId}`,
      { headers: { 'Authorization': `Bearer ${this.token}` } }
    );

    if (!response.ok) {
      throw new Error('Failed to extract node');
    }

    return await response.json();
  }

  // Complete workflow
  async processFile(file) {
    console.log('1. Uploading file...');
    const documentId = await this.uploadFile(file);

    console.log('2. Waiting for normalization...');
    const doc = await this.waitForNormalization(documentId);
    console.log(`✓ Normalized: ${doc.charCount} characters`);

    console.log('3. Building structure...');
    const structureResult = await this.buildStructure(documentId);
    console.log(`✓ ${structureResult.message}`);

    console.log('4. Loading structure...');
    const structure = await this.getStructure(documentId, 'tree');
    console.log(`✓ Loaded ${structure.totalNodes} nodes`);

    return { documentId, document: doc, structure };
  }
}

// Usage
const processor = new DocumentProcessor(token);
const result = await processor.processFile(selectedFile);
```

---

### Text Viewer with Pagination

```javascript
const DocumentTextViewer = ({ documentId, token }) => {
  const [metadata, setMetadata] = useState(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [textSlice, setTextSlice] = useState(null);
  const [loading, setLoading] = useState(true);
  const pageSize = 2000; // characters per page

  useEffect(() => {
    loadMetadata();
  }, [documentId]);

  useEffect(() => {
    if (metadata) {
      loadPage();
    }
  }, [currentPage, metadata]);

  const loadMetadata = async () => {
    const response = await fetch(
      `/api/v1/documentProcess/documents/${documentId}`,
      { headers: { 'Authorization': `Bearer ${token}` } }
    );

    const data = await response.json();
    setMetadata(data);
  };

  const loadPage = async () => {
    setLoading(true);

    const start = currentPage * pageSize;
    const end = start + pageSize;

    try {
      const response = await fetch(
        `/api/v1/documentProcess/documents/${documentId}/text?start=${start}&end=${end}`,
        { headers: { 'Authorization': `Bearer ${token}` } }
      );

      const slice = await response.json();
      setTextSlice(slice);
    } catch (error) {
      console.error('Failed to load text:', error);
    } finally {
      setLoading(false);
    }
  };

  if (!metadata) return <div>Loading...</div>;

  const totalPages = Math.ceil(metadata.charCount / pageSize);

  return (
    <div className="text-viewer">
      <div className="header">
        <h3>{metadata.originalName}</h3>
        <div className="meta">
          <span>Source: {metadata.source}</span>
          <span>Language: {metadata.language || 'auto'}</span>
          <span>Characters: {metadata.charCount.toLocaleString()}</span>
          <span>Status: {metadata.status}</span>
        </div>
      </div>

      {loading ? (
        <div>Loading page...</div>
      ) : (
        <div className="content">
          <pre>{textSlice?.text}</pre>
        </div>
      )}

      <div className="pagination">
        <button 
          onClick={() => setCurrentPage(p => Math.max(0, p - 1))}
          disabled={currentPage === 0 || loading}
        >
          Previous
        </button>
        <span>Page {currentPage + 1} of {totalPages}</span>
        <button 
          onClick={() => setCurrentPage(p => p + 1)}
          disabled={currentPage >= totalPages - 1 || loading}
        >
          Next
        </button>
      </div>
    </div>
  );
};
```

---

### Structure Tree Navigation

```javascript
const StructureNavigator = ({ documentId, token }) => {
  const [structure, setStructure] = useState(null);
  const [selectedNode, setSelectedNode] = useState(null);
  const [extractedText, setExtractedText] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadStructure();
  }, [documentId]);

  const loadStructure = async () => {
    try {
      const response = await fetch(
        `/api/v1/documentProcess/documents/${documentId}/structure?format=tree`,
        { headers: { 'Authorization': `Bearer ${token}` } }
      );

      const data = await response.json();
      setStructure(data);
    } catch (error) {
      console.error('Failed to load structure:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleNodeClick = async (node) => {
    setSelectedNode(node);
    setExtractedText(null);

    try {
      const response = await fetch(
        `/api/v1/documentProcess/documents/${documentId}/extract?nodeId=${node.id}`,
        { headers: { 'Authorization': `Bearer ${token}` } }
      );

      const extraction = await response.json();
      setExtractedText(extraction.text);
    } catch (error) {
      console.error('Failed to extract node:', error);
    }
  };

  const renderNode = (node, level = 0) => (
    <div key={node.id} style={{ marginLeft: `${level * 20}px` }}>
      <div
        className={`node ${selectedNode?.id === node.id ? 'selected' : ''}`}
        onClick={() => handleNodeClick(node)}
      >
        <span className="icon">{getTypeIcon(node.type)}</span>
        <span className="title">{node.title || 'Untitled'}</span>
        <span className="type">{node.type}</span>
        {node.aiConfidence && (
          <span className="confidence">
            {(node.aiConfidence * 100).toFixed(0)}%
          </span>
        )}
        <span className="size">
          {(node.endOffset - node.startOffset).toLocaleString()} chars
        </span>
      </div>
      {node.children?.map(child => renderNode(child, level + 1))}
    </div>
  );

  const getTypeIcon = (type) => {
    const icons = {
      'BOOK': '📖',
      'CHAPTER': '📑',
      'SECTION': '📄',
      'SUBSECTION': '📃',
      'PARAGRAPH': '¶',
      'OTHER': '•'
    };
    return icons[type] || '•';
  };

  if (loading) return <div>Loading structure...</div>;

  if (!structure || structure.totalNodes === 0) {
    return (
      <div className="no-structure">
        <p>No structure generated yet.</p>
        <button onClick={() => buildAndReload()}>Build Structure</button>
      </div>
    );
  }

  return (
    <div className="structure-navigator">
      <div className="tree-panel">
        <h3>Document Structure ({structure.totalNodes} nodes)</h3>
        {structure.rootNodes.map(node => renderNode(node))}
      </div>

      {selectedNode && (
        <div className="preview-panel">
          <h4>{selectedNode.title || 'Selected Node'}</h4>
          <div className="meta">
            <p>Type: {selectedNode.type}</p>
            <p>Depth: {selectedNode.depth}</p>
            <p>Position: {selectedNode.idx}</p>
            <p>Offsets: {selectedNode.startOffset} - {selectedNode.endOffset}</p>
            {selectedNode.aiConfidence && (
              <p>AI Confidence: {(selectedNode.aiConfidence * 100).toFixed(1)}%</p>
            )}
          </div>
          
          {extractedText ? (
            <div className="text-content">
              <pre>{extractedText}</pre>
            </div>
          ) : (
            <div>Loading text...</div>
          )}
        </div>
      )}
    </div>
  );
};
```

---

### File Upload with Validation

```javascript
const DocumentUploadForm = ({ onComplete }) => {
  const [file, setFile] = useState(null);
  const [originalName, setOriginalName] = useState('');
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState(null);

  const validateFile = (file) => {
    if (!file) {
      return 'Please select a file';
    }

    if (file.size === 0) {
      return 'File is empty';
    }

    const allowedTypes = ['application/pdf', 'application/epub+zip', 'text/plain'];
    const allowedExtensions = ['.pdf', '.epub', '.txt'];

    const hasValidType = allowedTypes.includes(file.type);
    const hasValidExtension = allowedExtensions.some(ext =>
      file.name.toLowerCase().endsWith(ext)
    );

    if (!hasValidType && !hasValidExtension) {
      return 'Unsupported file type. Please upload PDF, EPUB, or TXT files.';
    }

    return null;
  };

  const handleFileChange = (e) => {
    const selectedFile = e.target.files[0];
    const validationError = validateFile(selectedFile);

    if (validationError) {
      setError(validationError);
      setFile(null);
    } else {
      setFile(selectedFile);
      setError(null);
      setOriginalName(selectedFile.name);
    }
  };

  const handleUpload = async () => {
    if (!file) return;

    setUploading(true);
    setError(null);

    try {
      const formData = new FormData();
      formData.append('file', file);

      const url = originalName && originalName !== file.name
        ? `/api/v1/documentProcess/documents?originalName=${encodeURIComponent(originalName)}`
        : '/api/v1/documentProcess/documents';

      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` },
        body: formData
      });

      if (response.status === 415) {
        throw new Error('Unsupported file type');
      }

      if (!response.ok) {
        const error = await response.json();
        throw new Error(error.details?.[0] || 'Upload failed');
      }

      const result = await response.json();

      if (result.status === 'FAILED') {
        throw new Error('Document conversion failed');
      }

      onComplete(result.id);
      setFile(null);
      setOriginalName('');
    } catch (err) {
      setError(err.message);
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="upload-form">
      <input
        type="file"
        accept=".pdf,.epub,.txt"
        onChange={handleFileChange}
        disabled={uploading}
      />

      <input
        type="text"
        placeholder="Custom filename (optional)"
        value={originalName}
        onChange={(e) => setOriginalName(e.target.value)}
        disabled={uploading}
      />

      <button onClick={handleUpload} disabled={!file || uploading}>
        {uploading ? 'Uploading...' : 'Upload Document'}
      </button>

      {error && <div className="error">{error}</div>}

      <p className="hint">Supported: PDF, EPUB, TXT</p>
    </div>
  );
};
```

---

### Structure Build with Progress

```javascript
const StructureBuilder = ({ documentId, token, onComplete }) => {
  const [building, setBuilding] = useState(false);
  const [progress, setProgress] = useState('');
  const [error, setError] = useState(null);

  const handleBuild = async () => {
    setBuilding(true);
    setError(null);
    setProgress('Checking document status...');

    try {
      // 1. Verify document is ready
      const metaResponse = await fetch(
        `/api/v1/documentProcess/documents/${documentId}`,
        { headers: { 'Authorization': `Bearer ${token}` } }
      );

      const metadata = await metaResponse.json();

      if (metadata.status !== 'NORMALIZED') {
        if (metadata.status === 'PENDING') {
          throw new Error('Document still processing. Please wait.');
        } else if (metadata.status === 'FAILED') {
          throw new Error('Document processing failed. Cannot build structure.');
        } else if (metadata.status === 'STRUCTURED') {
          setProgress('Structure already exists');
          onComplete();
          return;
        }
      }

      // 2. Build structure
      setProgress('Building document structure (this may take a moment)...');

      const buildResponse = await fetch(
        `/api/v1/documentProcess/documents/${documentId}/structure`,
        {
          method: 'POST',
          headers: { 'Authorization': `Bearer ${token}` }
        }
      );

      const result = await buildResponse.json();

      if (result.status === 'STRUCTURED') {
        setProgress(`✓ ${result.message}`);
        setTimeout(() => onComplete(result), 1000);
      } else if (result.status === 'FAILED') {
        throw new Error(result.message);
      } else {
        throw new Error(result.message);
      }

    } catch (err) {
      setError(err.message);
      setProgress('');
    } finally {
      setBuilding(false);
    }
  };

  return (
    <div className="structure-builder">
      <button onClick={handleBuild} disabled={building}>
        {building ? 'Building Structure...' : 'Build Document Structure'}
      </button>

      {progress && <div className="progress">{progress}</div>}
      {error && <div className="error">{error}</div>}

      <p className="hint">
        Uses AI to detect chapters, sections, and document structure
      </p>
    </div>
  );
};
```

---

### Flat Structure List View

```javascript
const FlatStructureView = ({ documentId, token }) => {
  const [nodes, setNodes] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadFlatStructure();
  }, [documentId]);

  const loadFlatStructure = async () => {
    try {
      const response = await fetch(
        `/api/v1/documentProcess/documents/${documentId}/structure?format=flat`,
        { headers: { 'Authorization': `Bearer ${token}` } }
      );

      const data = await response.json();
      setNodes(data.nodes);
    } catch (error) {
      console.error('Failed to load structure:', error);
    } finally {
      setLoading(false);
    }
  };

  if (loading) return <div>Loading...</div>;

  if (nodes.length === 0) {
    return <div>No structure available. Build structure first.</div>;
  }

  return (
    <div className="flat-structure">
      <h3>Document Outline ({nodes.length} nodes)</h3>
      
      <table>
        <thead>
          <tr>
            <th>Type</th>
            <th>Title</th>
            <th>Depth</th>
            <th>Position</th>
            <th>Size</th>
            <th>Confidence</th>
          </tr>
        </thead>
        <tbody>
          {nodes.map(node => (
            <tr key={node.id} style={{ paddingLeft: `${node.depth * 20}px` }}>
              <td>{node.type}</td>
              <td>{node.title || '-'}</td>
              <td>{node.depth}</td>
              <td>{node.startOffset}</td>
              <td>{(node.endOffset - node.startOffset).toLocaleString()}</td>
              <td>
                {node.aiConfidence 
                  ? `${(node.aiConfidence * 100).toFixed(0)}%`
                  : '-'
                }
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};
```

---

### Status Polling Utility

```javascript
const pollDocumentStatus = async (documentId, token, targetStatus = 'NORMALIZED') => {
  const maxAttempts = 60;
  const pollInterval = 1000; // 1 second

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const response = await fetch(
      `/api/v1/documentProcess/documents/${documentId}/head`,
      { headers: { 'Authorization': `Bearer ${token}` } }
    );

    if (!response.ok) {
      throw new Error('Failed to check status');
    }

    const doc = await response.json();

    // Check for target status
    if (doc.status === targetStatus) {
      return doc;
    }

    // Check for terminal statuses
    if (doc.status === 'STRUCTURED' && targetStatus === 'NORMALIZED') {
      return doc; // STRUCTURED is also acceptable
    }

    if (doc.status === 'FAILED') {
      throw new Error('Document processing failed');
    }

    // Still processing, wait and retry
    await new Promise(resolve => setTimeout(resolve, pollInterval));
  }

  throw new Error(`Timeout waiting for status: ${targetStatus}`);
};

// Usage
try {
  const doc = await pollDocumentStatus(documentId, token, 'NORMALIZED');
  console.log('Document ready:', doc.charCount, 'characters');
} catch (error) {
  console.error('Polling failed:', error);
}
```

---

## Security Considerations

### Document Ownership

1. **Automatic Assignment**: Documents auto-owned by creating user
2. **Ownership Validation**: All operations validate ownership
3. **No Sharing**: Currently no cross-user document access
4. **403 Enforcement**: Non-owners receive Forbidden error

### File Upload Security

1. **Type Validation**: Only supported file types accepted
2. **MIME Detection**: Server validates MIME type
3. **Content Scanning**: Files processed safely
4. **Malware Protection**: Consider virus scanning before upload

### Data Privacy

1. **User Isolation**: Users cannot access others' documents
2. **Secure Storage**: Document text stored securely
3. **Access Control**: Ownership verified on every request
4. **Audit Trail**: Operations logged with user context

### Text Security

1. **Offset Validation**: Server validates all text slice offsets
2. **Bounds Checking**: Automatic clipping prevents out-of-bounds
3. **No Leakage**: Error messages don't expose document content
4. **Node Validation**: Node extraction validates document ownership

### Best Practices

**Frontend Implementation**:
- Validate file types client-side before upload
- Show supported file types clearly
- Implement upload progress indicators
- Handle long-running structure generation
- Use HTTPS for all uploads
- Clear file inputs after successful upload
- Cache document metadata appropriately
- Implement text pagination for large documents

**Error Handling**:
- Handle 415 errors with clear "unsupported type" message
- Parse `details` array for validation errors
- Show user-friendly messages for conversion failures
- Implement retry for 500 errors
- Handle ownership errors appropriately
- Check response Content-Type (some errors are plain text)

**Performance**:
- Use text slicing for large documents
- Load structure on-demand
- Cache document metadata
- Implement lazy loading for node extraction
- Debounce structure refresh requests
- Use HEAD endpoint for status checks

**Text Display**:
- Implement virtual scrolling for large texts
- Use pagination or infinite scroll
- Cache loaded text slices
- Handle whitespace and formatting properly
- Show character positions for reference

**Structure Generation**:
- Show loading indicator (can take 5-30 seconds)
- Disable button during processing
- Handle failures gracefully
- Allow retry after failure
- Explain AI-powered detection to users

**Testing**:
- Test with various file types
- Test with large files
- Verify ownership validation
- Test text slicing edge cases
- Test structure generation success/failure
- Verify error handling
- Test node extraction

---

## Validation Summary

### Client-Side Validation Checklist

**Before Upload**:
- [ ] File is selected
- [ ] File is not empty
- [ ] File type is supported (.pdf, .epub, .txt)
- [ ] MIME type matches expected types
- [ ] Filename is reasonable (if overriding)

**Before Text Ingest**:
- [ ] Text is not empty or whitespace-only
- [ ] Language code is valid (if provided)
- [ ] Language code ≤ 32 characters

**Before Text Slice Request**:
- [ ] Start offset >= 0
- [ ] End offset >= start (if provided)
- [ ] Offsets within document length (if known)

**Before Structure Build**:
- [ ] Document status is NORMALIZED
- [ ] Document has text content

**Before Node Extract**:
- [ ] NodeId is valid UUID
- [ ] Node belongs to correct document
- [ ] Structure has been built

---

## Status Transitions

### Document Lifecycle

**Text Ingestion**:
```
Create → NORMALIZED (immediate)
```

**File Upload**:
```
Upload → PENDING → NORMALIZED
           ↓
        FAILED
```

**Structure Generation**:
```
NORMALIZED → Build → STRUCTURED
                ↓
             FAILED (status stays NORMALIZED)
```

### When to Enable Operations

| Operation | Required Status | UI Behavior |
| --- | --- | --- |
| **View metadata** | Any | Always enabled |
| **View text** | NORMALIZED or STRUCTURED | Disable if PENDING/FAILED |
| **Build structure** | NORMALIZED | Disable if PENDING/FAILED/STRUCTURED |
| **View structure** | STRUCTURED | Show "build first" if not STRUCTURED |
| **Extract nodes** | STRUCTURED | Only available after build |

---

## Common Use Cases

### 1. Simple File Upload

```javascript
const uploadAndWait = async (file, token) => {
  // Upload
  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch('/api/v1/documentProcess/documents', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` },
    body: formData
  });

  const { id, status } = await response.json();

  if (status === 'FAILED') {
    throw new Error('Conversion failed');
  }

  // Poll for NORMALIZED
  while (true) {
    const statusResponse = await fetch(
      `/api/v1/documentProcess/documents/${id}/head`,
      { headers: { 'Authorization': `Bearer ${token}` } }
    );

    const doc = await statusResponse.json();

    if (doc.status === 'NORMALIZED') {
      return doc;
    } else if (doc.status === 'FAILED') {
      throw new Error('Normalization failed');
    }

    await new Promise(r => setTimeout(r, 1000));
  }
};
```

---

### 2. Text Ingest

```javascript
const ingestText = async (text, language = 'en') => {
  const response = await fetch('/api/v1/documentProcess/documents', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ text, language })
  });

  const { id, status } = await response.json();

  if (status === 'FAILED') {
    throw new Error('Text normalization failed');
  }

  return id;
};
```

---

### 3. Extract Specific Section

```javascript
const extractSection = async (documentId, sectionTitle) => {
  // Get flat structure
  const response = await fetch(
    `/api/v1/documentProcess/documents/${documentId}/structure?format=flat`,
    { headers: { 'Authorization': `Bearer ${token}` } }
  );

  const structure = await response.json();

  // Find section
  const section = structure.nodes.find(
    node => node.type === 'SECTION' && 
            node.title?.toLowerCase().includes(sectionTitle.toLowerCase())
  );

  if (!section) {
    throw new Error(`Section "${sectionTitle}" not found`);
  }

  // Extract text
  const extractResponse = await fetch(
    `/api/v1/documentProcess/documents/${documentId}/extract?nodeId=${section.id}`,
    { headers: { 'Authorization': `Bearer ${token}` } }
  );

  const extraction = await extractResponse.json();
  return extraction.text;
};
```

---

## Troubleshooting

### Common Issues

**1. 415 Unsupported Media Type**
- **Cause**: File type not supported
- **Solution**: Convert to PDF, EPUB, or TXT
- **Prevention**: Validate file extension client-side

**2. Status Stays PENDING**
- **Cause**: File conversion taking time or stuck
- **Solution**: Wait longer (large PDFs can take 30-60s), check server logs
- **Timeout**: Implement reasonable timeout (90-120s)

**3. Status = FAILED After Upload**
- **Cause**: Corrupted file, unsupported PDF features
- **Solution**: Try re-exporting PDF, use different file
- **Debug**: Check if file opens correctly in PDF reader

**4. Structure Build Fails**
- **Cause**: Document not NORMALIZED, no text content
- **Solution**: Ensure document fully processed first
- **Prevention**: Check status before building

**5. Text Slice Returns 422**
- **Cause**: Document has no normalized text
- **Solution**: Wait for normalization, check if status = FAILED
- **Debug**: Verify document status is NORMALIZED

**6. Node Extract Fails**
- **Cause**: Node doesn't exist or belongs to different document
- **Solution**: Verify nodeId is correct
- **Prevention**: Only use nodeIds from structure response

**7. Invalid Format Error (Plain Text)**
- **Cause**: Using invalid value for `format` parameter
- **Solution**: Use only "tree" or "flat"
- **Note**: This error returns plain text, not JSON

### Debug Checklist

- [ ] Valid authentication token provided
- [ ] User owns the document
- [ ] File type is supported (.pdf, .epub, .txt)
- [ ] File is not empty or corrupted
- [ ] Document status appropriate for operation
- [ ] Text offsets valid (start >= 0, end >= start)
- [ ] NodeId is valid UUID from structure response
- [ ] Network connection stable
- [ ] HTTPS used for uploads
- [ ] Polling timeout sufficient for large documents

---

## API Design Notes

### Why Two Document Controllers?

- **`/api/documents`**: User-facing document management with chunking for quizzes
- **`/api/v1/documentProcess/documents`**: Lower-level normalization and structure extraction

Both serve different purposes in the document pipeline.

### Normalization Process

Server normalizes all ingested content:
- Whitespace cleanup
- Encoding standardization (UTF-8)
- Text extraction from binary formats
- Language detection

### Structure Generation

AI-powered structure detection:
- Identifies chapters, sections, paragraphs
- Creates hierarchical tree
- Assigns confidence scores
- Supports navigation and targeted extraction

### Why Offset-Based?

Offsets allow:
- Efficient text slicing without loading full content
- Precise node-to-text mapping
- Stable references after normalization
- Progressive loading of large documents

---

## Advanced Integration Patterns

### Complete Document Workflow Manager

```typescript
interface DocumentWorkflow {
  id: string;
  status: DocumentStatus;
  metadata?: DocumentView;
  structure?: StructureTreeResponse;
}

class DocumentWorkflowManager {
  private baseUrl = '/api/v1/documentProcess/documents';
  
  constructor(private token: string) {}

  async createFromFile(file: File): Promise<DocumentWorkflow> {
    // Upload
    const id = await this.uploadFile(file);
    
    // Wait for normalization
    const metadata = await this.waitForStatus(id, 'NORMALIZED');
    
    // Build structure
    await this.buildStructure(id);
    
    // Load structure
    const structure = await this.getStructure(id);
    
    return { id, status: 'STRUCTURED', metadata, structure };
  }

  async createFromText(text: string, language?: string): Promise<DocumentWorkflow> {
    const id = await this.ingestText(text, language);
    const metadata = await this.getMetadata(id);
    
    return { id, status: metadata.status as DocumentStatus, metadata };
  }

  private async uploadFile(file: File): Promise<string> {
    const formData = new FormData();
    formData.append('file', file);

    const response = await fetch(this.baseUrl, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${this.token}` },
      body: formData
    });

    const { id } = await response.json();
    return id;
  }

  private async waitForStatus(
    id: string, 
    targetStatus: DocumentStatus
  ): Promise<DocumentView> {
    // Implementation...
  }

  // ... other methods
}
```

---

## Future Enhancements

### Planned Features

- **Streaming Upload**: Support for large file uploads with progress
- **WebSocket Updates**: Real-time processing status updates
- **Document Sharing**: Share documents between users
- **Version History**: Track document changes over time
- **Collaborative Editing**: Multi-user document annotation
- **Advanced Structure**: Table of contents extraction, image extraction
- **Export**: Export structured documents in various formats
- **Batch Processing**: Upload and process multiple documents
- **Template Support**: Document templates for common formats

### Current Limitations

1. **No Streaming**: Entire file must be uploaded at once
2. **No Progress**: Cannot track upload/conversion percentage
3. **Synchronous Processing**: Must poll for status
4. **No Sharing**: Cannot share documents with other users
5. **No Versioning**: No history of document changes
6. **Single Format**: Structure in one format per request

