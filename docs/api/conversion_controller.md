# Document Process Controller API Reference

Complete frontend integration guide for `/api/v1/documentProcess/documents` REST endpoints. This document is self-contained and includes all DTOs, validation rules, and error semantics needed to integrate document conversion, normalization, and structure extraction features.

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
  - Convert uploaded files (PDF, DOCX, TXT) into normalized text
  - Persist normalized documents with metadata
  - Serve paginated text slices without re-sending full content
  - Generate AI-powered document structures (hierarchical tree)
  - Extract text by structural node
* **Authentication**: Required for all endpoints. Uses JWT Bearer token in `Authorization` header.
* **Authorization Model**: Ownership-based. Users can only access their own documents.
* **Content Types**:
  - `application/json` for JSON ingest and responses
  - `multipart/form-data` for file uploads
* **Error Format**: All errors return `ErrorResponse` object
* **Idempotency**: No automatic deduplication - uploading same document twice creates distinct records

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
- Service layer validates ownership for all read/write operations
- 403 Forbidden returned if user doesn't own the document
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
  "text": "# Linear Algebra Lecture Notes\n\nChapter 1: Vectors\n\nA vector is...",
  "language": "en"
}
```

**Notes**:
- `text` cannot be null, empty, or only whitespace
- `language` is optional, defaults to auto-detection
- Server normalizes text (whitespace, encoding, etc.)

---

### Multipart File Upload

**Used by**: `POST /` (multipart/form-data)

Upload a document file for conversion and normalization.

| Part/Parameter | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `file` | binary | Yes | Non-empty file | Document file to upload |
| `originalName` | string (query param) | No | - | Override filename |

**Example Request**:
```
POST /api/v1/documentProcess/documents
Content-Type: multipart/form-data

file: [binary file data]
originalName: "lecture-notes.pdf" (optional query parameter)
```

**Supported File Types**:
- PDF (`.pdf`)
- Word (`.doc`, `.docx`)
- Text (`.txt`)
- Other formats may be supported (check server configuration)

**Validation**:
- File cannot be null or empty
- File size limits apply (server configured)
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

**Example (Failed Normalization)**:
```json
{
  "id": "1e0a0cb9-9b45-4d88-8a65-2ad7f93d51af",
  "status": "FAILED"
}
```

**Important**:
- Response is `201 Created` even if normalization fails
- Always check `status` field
- `FAILED` status indicates conversion/normalization error
- Document still persisted for audit/retry

---

### DocumentView

**Returned by**: `GET /{id}`, `GET /{id}/head`

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Document identifier |
| `originalName` | string | Filename (provided or detected) |
| `mime` | string | MIME type (e.g., "application/pdf") |
| `source` | `DocumentSource` enum | Origin: `UPLOAD` or `TEXT` |
| `charCount` | integer | Number of characters in normalized text |
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
  "createdAt": "2024-01-15T10:00:00Z",
  "updatedAt": "2024-01-15T10:05:30Z"
}
```

**Notes**:
- `HEAD` endpoint returns same structure (lightweight status check)
- MIME type auto-detected if not provided
- `charCount` = 0 if normalization failed

---

### TextSliceResponse

**Returned by**: `GET /{id}/text`

| Field | Type | Description |
| --- | --- | --- |
| `documentId` | UUID | Source document ID |
| `start` | integer | Inclusive start offset |
| `end` | integer | Exclusive end offset (auto-clipped) |
| `text` | string | Extracted substring |

**Example**:
```json
{
  "documentId": "1e0a0cb9-9b45-4d88-8a65-2ad7f93d51af",
  "start": 0,
  "end": 500,
  "text": "# Linear Algebra Lecture Notes\n\nChapter 1: Vectors\n\n..."
}
```

**Notes**:
- `end` clipped to document length if beyond bounds
- Use for progressive loading of large documents
- Offsets are character-based (not byte-based)

---

### StructureTreeResponse

**Returned by**: `GET /{id}/structure?format=tree`

| Field | Type | Description |
| --- | --- | --- |
| `documentId` | UUID | Source document ID |
| `rootNodes` | array of `NodeView` | Top-level nodes (depth 0) |
| `totalNodes` | integer | Total number of nodes |

**NodeView** structure (recursive):

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Node identifier |
| `documentId` | UUID | Parent document ID |
| `parentId` | UUID (nullable) | Parent node ID (null for roots) |
| `idx` | integer | Position among siblings (0-based) |
| `type` | `NodeType` enum | Semantic node type |
| `title` | string (nullable) | Node title/heading |
| `startOffset` | integer | Start character position |
| `endOffset` | integer | End character position |
| `depth` | integer | Depth from root (0 = root) |
| `aiConfidence` | number (nullable) | AI confidence score (0-1) |
| `metaJson` | string (nullable) | Additional metadata as JSON |
| `children` | array of `NodeView` | Nested child nodes |

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
      "title": "Introduction",
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
          "title": "Overview",
          "startOffset": 100,
          "endOffset": 1200,
          "depth": 1,
          "aiConfidence": 0.92,
          "metaJson": null,
          "children": []
        }
      ]
    }
  ],
  "totalNodes": 15
}
```

---

### StructureFlatResponse

**Returned by**: `GET /{id}/structure?format=flat`

| Field | Type | Description |
| --- | --- | --- |
| `documentId` | UUID | Source document ID |
| `nodes` | array of `FlatNode` | All nodes in linear order |
| `totalNodes` | integer | Total number of nodes |

**FlatNode** structure (non-recursive):

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Node identifier |
| `documentId` | UUID | Parent document ID |
| `parentId` | UUID (nullable) | Parent node ID |
| `idx` | integer | Sibling position |
| `type` | `NodeType` enum | Node type |
| `title` | string (nullable) | Node title |
| `startOffset` | integer | Start position |
| `endOffset` | integer | End position |
| `depth` | integer | Tree depth |
| `aiConfidence` | number (nullable) | AI confidence |
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
| `start` | integer | Start offset |
| `end` | integer | End offset |
| `text` | string | Extracted text content |

**Example**:
```json
{
  "documentId": "doc-uuid",
  "nodeId": "node-uuid-2",
  "title": "Overview",
  "start": 100,
  "end": 1200,
  "text": "This chapter provides an overview of linear algebra concepts..."
}
```

---

### StructureBuildResponse

**Returned by**: `POST /{id}/structure`

| Field | Type | Description |
| --- | --- | --- |
| `status` | string | `"STRUCTURED"`, `"FAILED"`, or `"ERROR"` |
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
  "message": "Unexpected error during structure generation: AI service timeout"
}
```

---

## Enumerations

### DocumentStatus

| Value | Description |
| --- | --- |
| `PENDING` | Document registered but not yet normalized (transient) |
| `NORMALIZED` | Conversion and normalization succeeded |
| `FAILED` | Conversion or normalization failed |
| `STRUCTURED` | Structure generation completed |

**Status Flow**:
```
PENDING → NORMALIZED → STRUCTURED
   ↓
FAILED
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
| `PART` | Large multi-section grouping | Book parts |
| `CHAPTER` | Chapter-level divisions | Standard chapters |
| `SECTION` | Section headings | Within chapters |
| `SUBSECTION` | Nested subsections | Deeper structure |
| `PARAGRAPH` | Paragraph-level content | Fine-grained structure |
| `UTTERANCE` | Transcript sentences/lines | For transcripts |
| `OTHER` | Unknown/unclassified | Fallback type |

---

## Endpoints

### Document Ingestion

#### 1. Ingest Text (JSON)

```
POST /api/v1/documentProcess/documents
Content-Type: application/json
```

**Request Body**: `IngestRequest`
```json
{
  "text": "# Introduction to Machine Learning\n\nMachine learning is...",
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
- `500` - Unexpected ingestion failure

**Notes**:
- Returns 201 even if normalization fails (check `status`)
- Text is normalized (whitespace cleaned, encoding fixed)
- Document immediately ready for text slicing

---

#### 2. Upload File (Multipart)

```
POST /api/v1/documentProcess/documents
Content-Type: multipart/form-data
```

**Request Body** (multipart):
- `file` - Document file (required)
- `originalName` - Custom filename (optional, query parameter)

**Success Response**: `201 Created` - `IngestResponse`

**Headers**:
- `Location: /api/v1/documentProcess/documents/{id}`

**Error Responses**:
- `400` - Missing file, empty file
- `401` - Unauthorized
- `415` - Unsupported file type
- `422` - Conversion failed, normalization failed
- `500` - Unexpected error

**Example Upload (JavaScript)**:
```javascript
const uploadDocument = async (file) => {
  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch('/api/v1/documentProcess/documents', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`
      // Don't set Content-Type - browser sets with boundary
    },
    body: formData
  });

  if (!response.ok) {
    if (response.status === 415) {
      throw new Error('Unsupported file type');
    }
    throw new Error('Upload failed');
  }

  return await response.json();
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
- Intended for status polling without loading full text
- Use for checking if processing completed

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
- `end` auto-clipped to document length if too large
- Use for pagination of large documents
- Offsets are character-based

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
- Format=tree: `StructureTreeResponse`
- Format=flat: `StructureFlatResponse`

**Error Responses**:
- `400` - Invalid format value
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document not found

**Notes**:
- Returns empty nodes if structure not yet generated
- Tree format: Hierarchical with nested children
- Flat format: Linear list sorted by startOffset
- Use tree for UI navigation, flat for analysis

---

#### 7. Build Document Structure

```
POST /api/v1/documentProcess/documents/{id}/structure
```

**Path Parameters**:
- `{id}` - Document UUID

**Success Response**: `200 OK` - `StructureBuildResponse`
```json
{
  "status": "STRUCTURED",
  "message": "Document structure generated successfully with 24 nodes"
}
```

**Error Responses**:
- `400` - Document not in NORMALIZED status
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document not found
- `422` - AI generation failed
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
    console.log('Success:', result.message);
    return true;
  } else if (result.status === 'FAILED') {
    console.error('Failed:', result.message);
    return false;
  } else {
    console.error('Error:', result.message);
    return false;
  }
};
```

**Notes**:
- Synchronous operation (may take several seconds)
- Requires document status to be `NORMALIZED`
- Uses AI to detect document structure
- Creates hierarchical node tree
- Show loading indicator during processing

---

### Node Extraction

#### 8. Extract Node Text

```
GET /api/v1/documentProcess/documents/{id}/extract?nodeId={nodeId}
```

**Path Parameters**:
- `{id}` - Document UUID

**Query Parameters**:
- `nodeId` (UUID, required) - Node to extract text from

**Success Response**: `200 OK` - `ExtractResponse`

**Error Responses**:
- `400` - Node belongs to different document or invalid UUID
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document or node not found
- `422` - Node lacks offsets or document has no text

**Example**:
```javascript
const extractNode = async (documentId, nodeId) => {
  const response = await fetch(
    `/api/v1/documentProcess/documents/${documentId}/extract?nodeId=${nodeId}`,
    {
      headers: { 'Authorization': `Bearer ${token}` }
    }
  );

  const extraction = await response.json();
  console.log(`Extracted ${extraction.title}:`, extraction.text);
  return extraction;
};
```

---

## Error Handling

### ErrorResponse Format

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 415,
  "error": "Unsupported Format",
  "details": [
    "No suitable converter found for: document.xyz"
  ]
}
```

### Common HTTP Status Codes

| Code | Meaning | When to Expect |
| --- | --- | --- |
| `200` | OK | Successful GET operations |
| `201` | Created | Successful POST ingestion (even if normalization failed) |
| `400` | Bad Request | Validation errors, invalid parameters |
| `401` | Unauthorized | Missing or invalid authentication token |
| `403` | Forbidden | Not document owner |
| `404` | Not Found | Document or node doesn't exist |
| `415` | Unsupported Media Type | Unsupported file format |
| `422` | Unprocessable Entity | Conversion failed, missing text, invalid state |
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
  "status": 422,
  "error": "Unprocessable Entity",
  "details": ["Document must be NORMALIZED before structure generation"]
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

---

## Integration Guide

### Complete Document Processing Flow

```javascript
class DocumentProcessor {
  constructor(token) {
    this.token = token;
    this.baseUrl = '/api/v1/documentProcess/documents';
  }

  // 1. Upload document
  async uploadFile(file) {
    const formData = new FormData();
    formData.append('file', file);

    const response = await fetch(this.baseUrl, {
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

  // 2. Poll status until normalized
  async waitForNormalization(documentId, maxAttempts = 30) {
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

      // Still pending, wait and retry
      await new Promise(r => setTimeout(r, 1000));
    }

    throw new Error('Timeout waiting for normalization');
  }

  // 3. Build structure
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

  // 4. Get structure
  async getStructure(documentId, format = 'tree') {
    const response = await fetch(
      `${this.baseUrl}/${documentId}/structure?format=${format}`,
      { headers: { 'Authorization': `Bearer ${this.token}` } }
    );

    return await response.json();
  }

  // 5. Extract node text
  async extractNode(documentId, nodeId) {
    const response = await fetch(
      `${this.baseUrl}/${documentId}/extract?nodeId=${nodeId}`,
      { headers: { 'Authorization': `Bearer ${this.token}` } }
    );

    return await response.json();
  }

  // Complete workflow
  async processDocument(file) {
    // Upload
    const documentId = await this.uploadFile(file);
    console.log('Uploaded:', documentId);

    // Wait for normalization
    const doc = await this.waitForNormalization(documentId);
    console.log('Normalized:', doc.charCount, 'characters');

    // Build structure
    const structureResult = await this.buildStructure(documentId);
    console.log('Structure:', structureResult.message);

    // Get tree view
    const structure = await this.getStructure(documentId, 'tree');
    console.log('Nodes:', structure.totalNodes);

    return { documentId, document: doc, structure };
  }
}
```

---

### Text Pagination Component

```javascript
const DocumentTextViewer = ({ documentId, token }) => {
  const [currentPage, setCurrentPage] = useState(0);
  const [textSlice, setTextSlice] = useState(null);
  const [docInfo, setDocInfo] = useState(null);
  const pageSize = 1000; // characters per page

  useEffect(() => {
    loadDocInfo();
  }, [documentId]);

  useEffect(() => {
    loadTextPage();
  }, [currentPage]);

  const loadDocInfo = async () => {
    const response = await fetch(
      `/api/v1/documentProcess/documents/${documentId}/head`,
      { headers: { 'Authorization': `Bearer ${token}` } }
    );
    const info = await response.json();
    setDocInfo(info);
  };

  const loadTextPage = async () => {
    const start = currentPage * pageSize;
    const end = start + pageSize;

    const response = await fetch(
      `/api/v1/documentProcess/documents/${documentId}/text?start=${start}&end=${end}`,
      { headers: { 'Authorization': `Bearer ${token}` } }
    );

    const slice = await response.json();
    setTextSlice(slice);
  };

  if (!docInfo || !textSlice) {
    return <div>Loading...</div>;
  }

  const totalPages = Math.ceil(docInfo.charCount / pageSize);

  return (
    <div className="document-viewer">
      <div className="header">
        <h3>{docInfo.originalName}</h3>
        <p>{docInfo.charCount} characters | {docInfo.language || 'auto'}</p>
        <p>Status: {docInfo.status}</p>
      </div>

      <div className="text-content">
        <pre>{textSlice.text}</pre>
      </div>

      <div className="pagination">
        <button 
          onClick={() => setCurrentPage(p => Math.max(0, p - 1))}
          disabled={currentPage === 0}
        >
          Previous
        </button>
        <span>Page {currentPage + 1} of {totalPages}</span>
        <button 
          onClick={() => setCurrentPage(p => p + 1)}
          disabled={currentPage >= totalPages - 1}
        >
          Next
        </button>
      </div>
    </div>
  );
};
```

---

### Structure Tree Visualization

```javascript
const StructureTreeView = ({ documentId, token }) => {
  const [structure, setStructure] = useState(null);
  const [selectedNode, setSelectedNode] = useState(null);
  const [nodeText, setNodeText] = useState(null);

  useEffect(() => {
    loadStructure();
  }, [documentId]);

  const loadStructure = async () => {
    const response = await fetch(
      `/api/v1/documentProcess/documents/${documentId}/structure?format=tree`,
      { headers: { 'Authorization': `Bearer ${token}` } }
    );
    const data = await response.json();
    setStructure(data);
  };

  const handleNodeClick = async (node) => {
    setSelectedNode(node);
    
    const response = await fetch(
      `/api/v1/documentProcess/documents/${documentId}/extract?nodeId=${node.id}`,
      { headers: { 'Authorization': `Bearer ${token}` } }
    );
    
    const extraction = await response.json();
    setNodeText(extraction.text);
  };

  const renderNode = (node, level = 0) => (
    <div key={node.id} style={{ marginLeft: `${level * 20}px` }}>
      <div 
        className={`node ${selectedNode?.id === node.id ? 'selected' : ''}`}
        onClick={() => handleNodeClick(node)}
      >
        <span className="type">{node.type}</span>
        <span className="title">{node.title || 'Untitled'}</span>
        {node.aiConfidence && (
          <span className="confidence">{(node.aiConfidence * 100).toFixed(0)}%</span>
        )}
      </div>
      {node.children && node.children.map(child => renderNode(child, level + 1))}
    </div>
  );

  if (!structure) return <div>Loading structure...</div>;

  if (structure.totalNodes === 0) {
    return <div>No structure generated. Click "Build Structure" to generate.</div>;
  }

  return (
    <div className="structure-view">
      <div className="tree">
        <h3>Document Structure ({structure.totalNodes} nodes)</h3>
        {structure.rootNodes.map(node => renderNode(node))}
      </div>

      {selectedNode && (
        <div className="preview">
          <h4>{selectedNode.title || 'Selected Node'}</h4>
          <p>Type: {selectedNode.type} | Depth: {selectedNode.depth}</p>
          <p>Offsets: {selectedNode.startOffset} - {selectedNode.endOffset}</p>
          {nodeText && (
            <div className="text">
              <pre>{nodeText}</pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
```

---

### File Upload with Progress

```javascript
const FileUploader = ({ onComplete }) => {
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState('');
  const [error, setError] = useState(null);

  const handleFileSelect = async (e) => {
    const file = e.target.files[0];
    if (!file) return;

    // Validate file type
    const allowedTypes = ['application/pdf', 'text/plain', 
      'application/msword', 
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document'];
    
    if (!allowedTypes.includes(file.type) && !file.name.match(/\.(pdf|txt|doc|docx)$/i)) {
      setError('Unsupported file type. Please upload PDF, DOCX, or TXT.');
      return;
    }

    setUploading(true);
    setError(null);
    setProgress('Uploading...');

    try {
      // 1. Upload
      const formData = new FormData();
      formData.append('file', file);

      const uploadResponse = await fetch('/api/v1/documentProcess/documents', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` },
        body: formData
      });

      if (uploadResponse.status === 415) {
        throw new Error('Unsupported file type');
      }

      if (!uploadResponse.ok) {
        throw new Error('Upload failed');
      }

      const { id, status } = await uploadResponse.json();

      if (status === 'FAILED') {
        throw new Error('Document conversion failed');
      }

      setProgress('Normalizing...');

      // 2. Wait for normalization
      let attempts = 0;
      while (attempts < 30) {
        await new Promise(r => setTimeout(r, 1000));
        
        const response = await fetch(
          `/api/v1/documentProcess/documents/${id}/head`,
          { headers: { 'Authorization': `Bearer ${token}` } }
        );

        const doc = await response.json();

        if (doc.status === 'NORMALIZED') {
          setProgress('Building structure...');
          
          // 3. Build structure
          const structureResponse = await fetch(
            `/api/v1/documentProcess/documents/${id}/structure`,
            {
              method: 'POST',
              headers: { 'Authorization': `Bearer ${token}` }
            }
          );

          const structureResult = await structureResponse.json();
          
          if (structureResult.status === 'STRUCTURED') {
            setProgress('Complete!');
            onComplete(id);
            return;
          } else {
            setError(structureResult.message);
            return;
          }
        } else if (doc.status === 'FAILED') {
          throw new Error('Normalization failed');
        }

        attempts++;
      }

      throw new Error('Timeout waiting for processing');

    } catch (err) {
      setError(err.message);
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="file-uploader">
      <input 
        type="file" 
        onChange={handleFileSelect}
        accept=".pdf,.txt,.doc,.docx"
        disabled={uploading}
      />
      
      {uploading && <div className="progress">{progress}</div>}
      {error && <div className="error">{error}</div>}
      
      <p className="hint">
        Supported formats: PDF, DOCX, TXT
      </p>
    </div>
  );
};
```

---

### Structure Navigation

```javascript
const DocumentExplorer = ({ documentId, token }) => {
  const [structure, setStructure] = useState(null);
  const [breadcrumb, setBreadcrumb] = useState([]);
  const [currentNode, setCurrentNode] = useState(null);

  const loadStructure = async () => {
    const response = await fetch(
      `/api/v1/documentProcess/documents/${documentId}/structure?format=tree`,
      { headers: { 'Authorization': `Bearer ${token}` } }
    );
    const data = await response.json();
    setStructure(data);
  };

  const navigate To = (node, parents = []) => {
    setCurrentNode(node);
    setBreadcrumb([...parents, node]);
  };

  const navigateUp = () => {
    if (breadcrumb.length > 1) {
      const newBreadcrumb = breadcrumb.slice(0, -1);
      setBreadcrumb(newBreadcrumb);
      setCurrentNode(newBreadcrumb[newBreadcrumb.length - 1]);
    } else {
      setCurrentNode(null);
      setBreadcrumb([]);
    }
  };

  const nodesToShow = currentNode ? currentNode.children : structure?.rootNodes || [];

  return (
    <div className="explorer">
      <div className="breadcrumb">
        <button onClick={() => { setCurrentNode(null); setBreadcrumb([]); }}>
          Root
        </button>
        {breadcrumb.map((node, idx) => (
          <span key={node.id}>
            {' > '}
            <button onClick={() => navigateTo(node, breadcrumb.slice(0, idx))}>
              {node.title || node.type}
            </button>
          </span>
        ))}
      </div>

      <div className="nodes">
        {nodesToShow.map(node => (
          <div key={node.id} className="node-card" onClick={() => navigateTo(node, breadcrumb)}>
            <h4>{node.title || 'Untitled'}</h4>
            <p>Type: {node.type} | Depth: {node.depth}</p>
            <p>{node.endOffset - node.startOffset} characters</p>
            {node.children && node.children.length > 0 && (
              <p>{node.children.length} sub-items</p>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};
```

---

### Text Ingest from Editor

```javascript
const TextDocumentCreator = ({ onComplete }) => {
  const [text, setText] = useState('');
  const [language, setLanguage] = useState('en');
  const [creating, setCreating] = useState(false);

  const handleCreate = async () => {
    if (!text.trim()) {
      alert('Please enter some text');
      return;
    }

    setCreating(true);

    try {
      const response = await fetch('/api/v1/documentProcess/documents', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          text: text,
          language: language
        })
      });

      if (!response.ok) {
        throw new Error('Failed to create document');
      }

      const result = await response.json();

      if (result.status === 'FAILED') {
        alert('Document normalization failed');
        return;
      }

      onComplete(result.id);
    } catch (error) {
      alert(error.message);
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="text-creator">
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="Paste or type document text here..."
        rows={20}
        disabled={creating}
      />

      <div className="controls">
        <label>
          Language:
          <select value={language} onChange={(e) => setLanguage(e.target.value)}>
            <option value="en">English</option>
            <option value="es">Spanish</option>
            <option value="fr">French</option>
            <option value="de">German</option>
          </select>
        </label>

        <button onClick={handleCreate} disabled={creating || !text.trim()}>
          {creating ? 'Creating...' : 'Create Document'}
        </button>
      </div>

      <p>{text.length} characters</p>
    </div>
  );
};
```

---

### Progressive Text Loading

```javascript
const useProgressiveText = (documentId, token) => {
  const [fullText, setFullText] = useState('');
  const [loading, setLoading] = useState(false);
  const chunkSize = 5000;

  const loadAllText = async () => {
    setLoading(true);

    try {
      // Get document info first
      const infoResponse = await fetch(
        `/api/v1/documentProcess/documents/${documentId}/head`,
        { headers: { 'Authorization': `Bearer ${token}` } }
      );
      const info = await infoResponse.json();

      if (info.status !== 'NORMALIZED' && info.status !== 'STRUCTURED') {
        throw new Error('Document not ready');
      }

      // Load in chunks
      let currentPos = 0;
      let accumulated = '';

      while (currentPos < info.charCount) {
        const end = Math.min(currentPos + chunkSize, info.charCount);
        
        const response = await fetch(
          `/api/v1/documentProcess/documents/${documentId}/text?start=${currentPos}&end=${end}`,
          { headers: { 'Authorization': `Bearer ${token}` } }
        );

        const slice = await response.json();
        accumulated += slice.text;
        setFullText(accumulated);

        currentPos = end;
      }

    } catch (error) {
      console.error('Error loading text:', error);
    } finally {
      setLoading(false);
    }
  };

  return { fullText, loading, loadAllText };
};
```

---

## Security Considerations

### Document Ownership

1. **Automatic Assignment**: Documents automatically owned by uploading user
2. **Ownership Validation**: All operations validate ownership
3. **No Sharing**: Currently no cross-user document sharing
4. **403 Enforcement**: Non-owners receive Forbidden error

### File Upload Security

1. **Type Validation**: Only supported file types accepted
2. **Size Limits**: Server enforces maximum file size
3. **Content Scanning**: Files processed safely
4. **Malware Protection**: Consider virus scanning before upload

### Data Privacy

1. **User Isolation**: Users cannot access others' documents
2. **No Public Access**: All endpoints require authentication
3. **Secure Storage**: Document text stored securely
4. **Audit Trail**: Operations logged with user context

### Best Practices

**Frontend**:
- Validate file types client-side before upload
- Show file size limits to users
- Implement progress indicators for uploads
- Handle long-running structure generation
- Use HTTPS for all uploads
- Clear file inputs after successful upload
- Cache document metadata to reduce requests

**Error Handling**:
- Handle 415 errors with clear "unsupported type" message
- Parse `details` array for validation errors
- Show user-friendly messages for conversion failures
- Implement retry for 500 errors
- Handle ownership errors appropriately

**Performance**:
- Use text slicing for large documents
- Load structure on-demand
- Cache document metadata
- Implement lazy loading for node extraction
- Debounce structure refresh requests

**Text Display**:
- Implement virtual scrolling for large texts
- Use pagination or infinite scroll
- Cache loaded text slices
- Handle whitespace and formatting properly

**Structure Generation**:
- Show loading indicator (can take several seconds)
- Disable during processing
- Handle failures gracefully
- Allow retry after failure

**Testing**:
- Test with various file types
- Test with large files
- Verify ownership validation
- Test text slicing edge cases
- Test structure generation success/failure
- Verify error handling

---

## Common Use Cases

### 1. Simple File Upload

```javascript
const simpleUpload = async (file, token) => {
  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch('/api/v1/documentProcess/documents', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` },
    body: formData
  });

  if (!response.ok) {
    throw new Error('Upload failed');
  }

  const { id, status } = await response.json();
  return { documentId: id, status };
};
```

---

### 2. Status Polling

```javascript
const pollDocumentStatus = async (documentId, token) => {
  const response = await fetch(
    `/api/v1/documentProcess/documents/${documentId}/head`,
    { headers: { 'Authorization': `Bearer ${token}` } }
  );

  const doc = await response.json();
  return doc.status;
};

// Usage with interval
const waitForStatus = async (documentId, targetStatus, maxWait = 30000) => {
  const startTime = Date.now();

  while (Date.now() - startTime < maxWait) {
    const status = await pollDocumentStatus(documentId, token);
    
    if (status === targetStatus) {
      return true;
    } else if (status === 'FAILED') {
      throw new Error('Document processing failed');
    }

    await new Promise(r => setTimeout(r, 1000));
  }

  throw new Error('Timeout waiting for status');
};
```

---

### 3. Extract Chapter Text

```javascript
const extractChapterText = async (documentId, chapterTitle, token) => {
  // 1. Get structure
  const response = await fetch(
    `/api/v1/documentProcess/documents/${documentId}/structure?format=flat`,
    { headers: { 'Authorization': `Bearer ${token}` } }
  );

  const structure = await response.json();

  // 2. Find chapter node
  const chapterNode = structure.nodes.find(
    node => node.type === 'CHAPTER' && 
            node.title?.toLowerCase().includes(chapterTitle.toLowerCase())
  );

  if (!chapterNode) {
    throw new Error(`Chapter "${chapterTitle}" not found`);
  }

  // 3. Extract text
  const extractResponse = await fetch(
    `/api/v1/documentProcess/documents/${documentId}/extract?nodeId=${chapterNode.id}`,
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
- **Cause**: File type not supported by server
- **Solution**: Convert file to PDF or TXT first
- **Prevention**: Validate file extension client-side

**2. Document Status = FAILED**
- **Cause**: Conversion or normalization failed
- **Solution**: Try different file or check file corruption
- **Debug**: Check error logs on server

**3. Structure Generation Takes Too Long**
- **Cause**: Large document requires extensive AI processing
- **Solution**: Show loading indicator, be patient
- **Timeout**: Implement 60-second timeout, allow retry

**4. 403 on Document Access**
- **Cause**: Trying to access another user's document
- **Solution**: Verify document ownership
- **Prevention**: Only show user's own documents

**5. Invalid Text Offsets**
- **Cause**: Requesting slice beyond document length
- **Solution**: Validate offsets against charCount
- **Prevention**: Auto-clip end offset

### Debug Checklist

- [ ] Valid authentication token provided
- [ ] User owns the document being accessed
- [ ] File type is supported (.pdf, .docx, .txt)
- [ ] File is not empty
- [ ] Document status is NORMALIZED before text slicing
- [ ] Document status is NORMALIZED before structure building
- [ ] Text offsets are valid (start >= 0, end > start)
- [ ] NodeId belongs to correct document
- [ ] Network connection stable
- [ ] HTTPS used for file uploads

---

## API Design Notes

### Why Ownership-Based?

Documents are user-owned because:
- Contain potentially sensitive content
- Support personal learning workflows
- Enable private quiz generation
- Maintain data privacy

### Normalization Process

Server normalizes all documents:
- Whitespace cleanup
- Encoding standardization
- Text extraction (from PDF/DOCX)
- Language detection

### Structure Generation

AI-powered structure detection:
- Identifies chapters, sections, paragraphs
- Creates hierarchical tree
- Assigns confidence scores
- Enables intelligent chunking for quiz generation

### Future Enhancements

Potential improvements:
- Document sharing between users
- Collaborative document editing
- Version history
- Document templates
- OCR for scanned PDFs
- Table and image extraction
- Citation detection
- Document merging
- Bulk upload

