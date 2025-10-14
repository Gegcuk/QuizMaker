# Document Controller API Reference

Complete frontend integration guide for `/api/documents` REST endpoints. This document is self-contained and includes all DTOs, validation rules, chunking strategies, and error semantics needed to integrate document upload and processing features.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Enumerations](#enumerations)
- [Endpoints](#endpoints)
  - [Document Upload](#document-upload)
  - [Document Retrieval](#document-retrieval)
  - [Chunk Access](#chunk-access)
  - [Document Management](#document-management)
  - [Configuration](#configuration)
- [Error Handling](#error-handling)
- [Integration Guide](#integration-guide)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/documents`
* **Authentication**: Required for all endpoints. Uses JWT Bearer token in `Authorization` header.
* **Authorization Model**: Ownership-based. Users can only access documents they uploaded.
* **Content Types**:
  - Upload: `multipart/form-data`
  - Other requests: `application/json`
  - Responses: `application/json` (except `204` responses)
* **Error Format**: All errors return `ErrorResponse` object
* **Processing**: Documents are chunked for AI quiz generation
* **Pagination**: List endpoint supports page-based pagination

---

## Authorization Matrix

All document endpoints require authentication and enforce ownership-based access control.

| Capability | Endpoint(s) | Authorization Rule | Notes |
| --- | --- | --- | --- |
| **Upload document** | `POST /upload` | Authenticated user | Document automatically owned by uploader |
| **View document** | `GET /{documentId}`, `GET /{documentId}/status` | User must own document | Returns 403 if not owner |
| **List documents** | `GET /` | Authenticated user | Auto-filtered to user's documents |
| **View chunks** | `GET /{documentId}/chunks`, `GET /{documentId}/chunks/{index}` | User must own document | Returns 403 if not owner |
| **Delete document** | `DELETE /{documentId}` | User must own document | Irreversible deletion |
| **Reprocess document** | `POST /{documentId}/reprocess` | User must own document | Re-chunk with new settings |
| **Get config** | `GET /config` | Authenticated user | Server configuration |

**Ownership Enforcement**:
- Documents linked to uploading user
- All operations validate ownership
- No cross-user document access
- 403 Forbidden returned for unauthorized access

---

## Request DTOs

### ProcessDocumentRequest

**Used by**: `POST /upload` (form parameters), `POST /{documentId}/reprocess` (JSON body)

| Field | Type | Required | Validation | Default | Description |
| --- | --- | --- | --- | --- | --- |
| `chunkingStrategy` | `ChunkingStrategy` enum | No (upload), Yes (reprocess) | Valid enum value | `AUTO` | How to split document |
| `maxChunkSize` | integer | No | 100-100,000 characters | Config value (~5,000) | Max characters per chunk |
| `minChunkSize` | integer | No | > 0 | 1,000 | Min chunk size |
| `aggressiveCombinationThreshold` | integer | No | > 0 | 3,000 | Threshold for combining small chunks |
| `storeChunks` | boolean | No | - | `true` | Whether to persist chunks |

**Form Parameters (Upload)**:
```
POST /api/documents/upload
Content-Type: multipart/form-data

file: [binary file data]
chunkingStrategy: CHAPTER_BASED (optional)
maxChunkSize: 5000 (optional)
```

**JSON Body (Reprocess)**:
```json
{
  "chunkingStrategy": "CHAPTER_BASED",
  "maxChunkSize": 7500,
  "minChunkSize": 1000,
  "aggressiveCombinationThreshold": 3000,
  "storeChunks": true
}
```

**Notes**:
- For upload: All parameters optional (uses config defaults)
- For reprocess: `chunkingStrategy` is required
- `maxChunkSize` must be between 100 and 100,000

---

## Response DTOs

### DocumentDto

**Returned by**: `POST /upload`, `GET /{documentId}`, `GET /{documentId}/status`, `POST /{documentId}/reprocess`

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Document identifier |
| `originalFilename` | string | Uploaded filename |
| `contentType` | string | MIME type (e.g., "application/pdf") |
| `fileSize` | integer (long) | File size in bytes |
| `status` | `DocumentStatus` enum | Processing status |
| `uploadedAt` | ISO 8601 datetime | Upload timestamp |
| `processedAt` | ISO 8601 datetime (nullable) | Processing completion time |
| `title` | string (nullable) | Extracted document title |
| `author` | string (nullable) | Extracted author name |
| `totalPages` | integer (nullable) | Total page count |
| `totalChunks` | integer | Number of generated chunks |
| `processingError` | string (nullable) | Error message if failed |
| `chunks` | array of `DocumentChunkDto` (nullable) | Chunks (when included) |

**Example (Processed)**:
```json
{
  "id": "doc-uuid-here",
  "originalFilename": "java-tutorial.pdf",
  "contentType": "application/pdf",
  "fileSize": 1024000,
  "status": "PROCESSED",
  "uploadedAt": "2024-01-15T10:00:00Z",
  "processedAt": "2024-01-15T10:00:45Z",
  "title": "Java Programming Tutorial",
  "author": "John Doe",
  "totalPages": 150,
  "totalChunks": 12,
  "processingError": null,
  "chunks": null
}
```

**Example (Failed)**:
```json
{
  "id": "doc-uuid-here",
  "originalFilename": "corrupted.pdf",
  "contentType": "application/pdf",
  "fileSize": 512000,
  "status": "FAILED",
  "uploadedAt": "2024-01-15T10:00:00Z",
  "processedAt": "2024-01-15T10:00:15Z",
  "title": null,
  "author": null,
  "totalPages": null,
  "totalChunks": 0,
  "processingError": "PDF parsing failed: Invalid PDF structure",
  "chunks": []
}
```

---

### DocumentChunkDto

**Returned by**: `GET /{documentId}/chunks`, `GET /{documentId}/chunks/{index}`

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Chunk identifier |
| `chunkIndex` | integer | Zero-based chunk position |
| `title` | string | Chunk title/heading |
| `content` | string | Chunk text content |
| `startPage` | integer | First page in chunk |
| `endPage` | integer | Last page in chunk |
| `wordCount` | integer | Number of words |
| `characterCount` | integer | Number of characters |
| `createdAt` | ISO 8601 datetime | Creation timestamp |
| `chapterTitle` | string (nullable) | Chapter name if detected |
| `sectionTitle` | string (nullable) | Section name if detected |
| `chapterNumber` | integer (nullable) | Chapter number if detected |
| `sectionNumber` | integer (nullable) | Section number if detected |
| `chunkType` | `DocumentChunkType` enum | How chunk was created |

**Example**:
```json
{
  "id": "chunk-uuid-1",
  "chunkIndex": 0,
  "title": "Chapter 1: Introduction",
  "content": "Java is a high-level programming language...",
  "startPage": 1,
  "endPage": 5,
  "wordCount": 850,
  "characterCount": 5240,
  "createdAt": "2024-01-15T10:00:45Z",
  "chapterTitle": "Introduction",
  "sectionTitle": null,
  "chapterNumber": 1,
  "sectionNumber": null,
  "chunkType": "CHAPTER"
}
```

---

### DocumentConfigDto

**Returned by**: `GET /config`

| Field | Type | Description |
| --- | --- | --- |
| `defaultMaxChunkSize` | integer | Default max chunk size in characters |
| `defaultStrategy` | string | Default chunking strategy |

**Example**:
```json
{
  "defaultMaxChunkSize": 5000,
  "defaultStrategy": "CHAPTER_BASED"
}
```

---

### Page\<DocumentDto\>

**Returned by**: `GET /`

Standard Spring pagination wrapper.

| Field | Type | Description |
| --- | --- | --- |
| `content` | array of `DocumentDto` | Page of documents |
| `totalElements` | integer | Total document count |
| `totalPages` | integer | Total page count |
| `number` | integer | Current page (0-indexed) |
| `size` | integer | Page size |
| `first` | boolean | Whether first page |
| `last` | boolean | Whether last page |

**Example**:
```json
{
  "content": [
    { /* DocumentDto */ },
    { /* DocumentDto */ }
  ],
  "totalElements": 25,
  "totalPages": 3,
  "number": 0,
  "size": 10,
  "first": true,
  "last": false
}
```

---

## Enumerations

### DocumentStatus

| Value | Description |
| --- | --- |
| `UPLOADED` | File uploaded, not yet processed |
| `PROCESSING` | Currently being processed and chunked |
| `PROCESSED` | Successfully processed and chunked |
| `FAILED` | Processing failed (see `processingError`) |

**Status Flow**:
```
UPLOADED → PROCESSING → PROCESSED
              ↓
           FAILED
```

---

### ChunkingStrategy

| Value | Description |
| --- | --- |
| `AUTO` | Server decides best strategy (usually SIZE_BASED) |
| `CHAPTER_BASED` | Split by detected chapters |
| `SECTION_BASED` | Split by detected sections |
| `SIZE_BASED` | Split by character count |
| `PAGE_BASED` | Split by page boundaries |

**Recommendations**:
- `CHAPTER_BASED`: Best for well-structured documents with clear chapters
- `SECTION_BASED`: For documents with section headings
- `SIZE_BASED`: Reliable fallback for any document
- `PAGE_BASED`: For documents with meaningful page breaks
- `AUTO`: Let server choose (maps to SIZE_BASED)

---

### DocumentChunkType

| Value | Description |
| --- | --- |
| `CHAPTER` | Created from chapter detection |
| `SECTION` | Created from section detection |
| `PAGE_BASED` | Created from page breaks |
| `SIZE_BASED` | Created by size splitting |

---

## Endpoints

### Document Upload

#### 1. Upload Document

```
POST /api/documents/upload
Content-Type: multipart/form-data
```

**Form Fields**:
- `file` (required) - Document file
- `chunkingStrategy` (optional) - Chunking strategy enum value
- `maxChunkSize` (optional) - Max chunk size (100-100,000)

**Success Response**: `201 Created` - `DocumentDto`

**Example Request (JavaScript)**:
```javascript
const formData = new FormData();
formData.append('file', fileInput.files[0]);
formData.append('chunkingStrategy', 'CHAPTER_BASED');
formData.append('maxChunkSize', '7500');

const response = await fetch('/api/documents/upload', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`
  },
  body: formData
});

const document = await response.json();
```

**Supported File Types**:
- PDF: `application/pdf`
- EPUB: `application/epub+zip`
- Plain Text: `text/plain`

**File Size Limit**: 150 MB

**Error Responses**:
- `400` - Validation error (empty file, unsupported type, invalid chunk size)
- `401` - Unauthorized
- `500` - Storage or processing error

**Example Errors**:

**Empty File**:
```json
{
  "status": 400,
  "error": "Bad Request",
  "details": ["File is empty"]
}
```

**Unsupported Type**:
```json
{
  "status": 400,
  "error": "Bad Request",
  "details": ["Unsupported file type: application/zip"]
}
```

**File Too Large**:
```json
{
  "status": 400,
  "error": "Bad Request",
  "details": ["File size exceeds maximum of 150 MB"]
}
```

---

### Document Retrieval

#### 2. Get Document by ID

```
GET /api/documents/{documentId}
```

**Path Parameters**:
- `{documentId}` - Document UUID

**Success Response**: `200 OK` - `DocumentDto`

**Error Responses**:
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document not found

---

#### 3. List User Documents

```
GET /api/documents
```

**Query Parameters**:
- `page` (integer, optional) - Page number (0-indexed), default: 0
- `size` (integer, optional) - Page size (1-100), default: 10

**Example URLs**:
```
GET /api/documents
GET /api/documents?page=0&size=20
GET /api/documents?page=1&size=10
```

**Success Response**: `200 OK` - `Page<DocumentDto>`

**Error Responses**:
- `401` - Unauthorized
- `500` - Unexpected error

**Notes**:
- Results automatically filtered to authenticated user's documents
- Sorted by upload time descending (most recent first)

---

#### 4. Get Document Status

```
GET /api/documents/{documentId}/status
```

**Path Parameters**:
- `{documentId}` - Document UUID

**Success Response**: `200 OK` - `DocumentDto` (status-focused)

**Error Responses**:
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document not found

**Notes**:
- Use for polling processing status
- Lighter weight than full document retrieval
- Check `status` field to determine if processing complete

---

### Chunk Access

#### 5. Get Document Chunks

```
GET /api/documents/{documentId}/chunks
```

**Path Parameters**:
- `{documentId}` - Document UUID

**Success Response**: `200 OK` - Array of `DocumentChunkDto`

**Example Response**:
```json
[
  {
    "id": "chunk-uuid-1",
    "chunkIndex": 0,
    "title": "Chapter 1: Introduction",
    "content": "Java is a high-level...",
    "startPage": 1,
    "endPage": 5,
    "wordCount": 850,
    "characterCount": 5240,
    "createdAt": "2024-01-15T10:00:45Z",
    "chapterTitle": "Introduction",
    "chapterNumber": 1,
    "chunkType": "CHAPTER"
  },
  {
    "id": "chunk-uuid-2",
    "chunkIndex": 1,
    "title": "Chapter 2: Variables",
    "content": "Variables in Java...",
    "startPage": 6,
    "endPage": 12,
    "wordCount": 920,
    "characterCount": 5680,
    "createdAt": "2024-01-15T10:00:45Z",
    "chapterTitle": "Variables",
    "chapterNumber": 2,
    "chunkType": "CHAPTER"
  }
]
```

**Error Responses**:
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document not found

**Notes**:
- Chunks sorted by `chunkIndex` ascending
- Use for quiz generation scope selection
- Empty array if document not yet processed

---

#### 6. Get Single Chunk

```
GET /api/documents/{documentId}/chunks/{chunkIndex}
```

**Path Parameters**:
- `{documentId}` - Document UUID
- `{chunkIndex}` - Chunk index (0-based integer)

**Success Response**: `200 OK` - `DocumentChunkDto`

**Error Responses**:
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document or chunk not found

**Example**:
```javascript
const getChunk = async (documentId, chunkIndex) => {
  const response = await fetch(
    `/api/documents/${documentId}/chunks/${chunkIndex}`,
    { headers: { 'Authorization': `Bearer ${token}` } }
  );
  
  if (!response.ok) {
    throw new Error('Chunk not found');
  }
  
  return await response.json();
};
```

---

### Document Management

#### 7. Delete Document

```
DELETE /api/documents/{documentId}
```

**Path Parameters**:
- `{documentId}` - Document UUID

**Success Response**: `204 No Content`

**Error Responses**:
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document not found

**Notes**:
- Deletes document, all chunks, and uploaded file
- Operation is irreversible
- Confirm with user before deletion

---

#### 8. Reprocess Document

```
POST /api/documents/{documentId}/reprocess
```

**Path Parameters**:
- `{documentId}` - Document UUID

**Request Body**: `ProcessDocumentRequest`
```json
{
  "chunkingStrategy": "SECTION_BASED",
  "maxChunkSize": 6000,
  "storeChunks": true
}
```

**Success Response**: `200 OK` - `DocumentDto` (updated)

**Error Responses**:
- `400` - Validation error (missing strategy, invalid chunk size)
- `401` - Unauthorized
- `403` - Not document owner
- `404` - Document not found
- `500` - Processing error

**Notes**:
- Re-chunks document with new settings
- Previous chunks are replaced
- Original file is retained
- Status changes to PROCESSING then PROCESSED/FAILED

---

### Configuration

#### 9. Get Configuration

```
GET /api/documents/config
```

**Success Response**: `200 OK` - `DocumentConfigDto`

**Example Response**:
```json
{
  "defaultMaxChunkSize": 5000,
  "defaultStrategy": "CHAPTER_BASED"
}
```

**Error Responses**:
- `401` - Unauthorized

**Notes**:
- Returns server defaults for document processing
- Use to show defaults in UI
- Helps users understand chunking behavior

---

## Error Handling

### ErrorResponse Format

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "details": [
    "File size exceeds maximum of 150 MB"
  ]
}
```

### Common HTTP Status Codes

| Code | Meaning | When to Expect |
| --- | --- | --- |
| `200` | OK | Successful GET or POST reprocess |
| `201` | Created | Successful upload |
| `204` | No Content | Successful deletion |
| `400` | Bad Request | Validation errors, unsupported file types, invalid parameters |
| `401` | Unauthorized | Missing or invalid authentication token |
| `403` | Forbidden | Not document owner |
| `404` | Not Found | Document or chunk doesn't exist |
| `500` | Internal Server Error | Storage errors, processing errors |

### Common Error Scenarios

**No File Provided**:
```json
{
  "status": 400,
  "error": "Bad Request",
  "details": ["No file provided"]
}
```

**Invalid Chunk Size**:
```json
{
  "status": 400,
  "error": "Bad Request",
  "details": ["Max chunk size must be between 100 and 100,000 characters"]
}
```

**Document Not Found**:
```json
{
  "status": 404,
  "error": "Not Found",
  "details": ["Document doc-uuid-here not found"]
}
```

**Not Document Owner**:
```json
{
  "status": 403,
  "error": "Forbidden",
  "details": ["Access denied"]
}
```

**Processing Failed**:
```json
{
  "status": 500,
  "error": "Internal Server Error",
  "details": ["Failed to process document: PDF extraction error"]
}
```

---

## Integration Guide

### Complete Upload and Processing Flow

```javascript
class DocumentManager {
  constructor(token) {
    this.token = token;
    this.baseUrl = '/api/documents';
  }

  // 1. Upload document
  async upload(file, options = {}) {
    const formData = new FormData();
    formData.append('file', file);
    
    if (options.chunkingStrategy) {
      formData.append('chunkingStrategy', options.chunkingStrategy);
    }
    if (options.maxChunkSize) {
      formData.append('maxChunkSize', options.maxChunkSize.toString());
    }

    const response = await fetch(`${this.baseUrl}/upload`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.token}`
      },
      body: formData
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.details?.[0] || 'Upload failed');
    }

    return await response.json();
  }

  // 2. Poll status
  async waitForProcessing(documentId, maxAttempts = 60) {
    for (let i = 0; i < maxAttempts; i++) {
      const response = await fetch(`${this.baseUrl}/${documentId}/status`, {
        headers: { 'Authorization': `Bearer ${this.token}` }
      });

      const doc = await response.json();

      if (doc.status === 'PROCESSED') {
        return doc;
      } else if (doc.status === 'FAILED') {
        throw new Error(doc.processingError || 'Processing failed');
      }

      // Still processing
      await new Promise(r => setTimeout(r, 1000));
    }

    throw new Error('Processing timeout');
  }

  // 3. Get chunks
  async getChunks(documentId) {
    const response = await fetch(`${this.baseUrl}/${documentId}/chunks`, {
      headers: { 'Authorization': `Bearer ${this.token}` }
    });

    if (!response.ok) {
      throw new Error('Failed to get chunks');
    }

    return await response.json();
  }

  // 4. Reprocess
  async reprocess(documentId, chunkingStrategy, maxChunkSize) {
    const response = await fetch(`${this.baseUrl}/${documentId}/reprocess`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        chunkingStrategy,
        maxChunkSize,
        storeChunks: true
      })
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.details?.[0] || 'Reprocess failed');
    }

    return await response.json();
  }

  // 5. Delete
  async delete(documentId) {
    const response = await fetch(`${this.baseUrl}/${documentId}`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${this.token}` }
    });

    if (!response.ok && response.status !== 204) {
      const error = await response.json();
      throw new Error(error.details?.[0] || 'Delete failed');
    }

    return true;
  }

  // Complete workflow
  async uploadAndProcess(file, chunkingStrategy = 'CHAPTER_BASED') {
    // Upload
    const doc = await this.upload(file, { chunkingStrategy });
    console.log('Uploaded:', doc.id);

    // Wait for processing
    const processed = await this.waitForProcessing(doc.id);
    console.log('Processed:', processed.totalChunks, 'chunks');

    // Get chunks
    const chunks = await this.getChunks(doc.id);
    console.log('Loaded', chunks.length, 'chunks');

    return { document: processed, chunks };
  }
}
```

---

### Upload with Progress Tracking

```javascript
const DocumentUploader = ({ onComplete }) => {
  const [file, setFile] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [processing, setProcessing] = useState(false);
  const [progress, setProgress] = useState('');
  const [error, setError] = useState(null);
  const [chunkingStrategy, setChunkingStrategy] = useState('CHAPTER_BASED');

  const handleUpload = async () => {
    if (!file) {
      setError('Please select a file');
      return;
    }

    setUploading(true);
    setError(null);

    try {
      // 1. Upload
      setProgress('Uploading...');
      
      const formData = new FormData();
      formData.append('file', file);
      formData.append('chunkingStrategy', chunkingStrategy);

      const uploadResponse = await fetch('/api/documents/upload', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` },
        body: formData
      });

      if (!uploadResponse.ok) {
        const error = await uploadResponse.json();
        throw new Error(error.details?.[0] || 'Upload failed');
      }

      const document = await uploadResponse.json();
      setUploading(false);
      setProcessing(true);

      // 2. Poll status
      setProgress('Processing document...');
      
      let attempts = 0;
      while (attempts < 60) {
        const statusResponse = await fetch(
          `/api/documents/${document.id}/status`,
          { headers: { 'Authorization': `Bearer ${token}` } }
        );

        const status = await statusResponse.json();

        if (status.status === 'PROCESSED') {
          setProgress(`Complete! ${status.totalChunks} chunks created`);
          setTimeout(() => onComplete(status), 1000);
          return;
        } else if (status.status === 'FAILED') {
          throw new Error(status.processingError || 'Processing failed');
        }

        attempts++;
        await new Promise(r => setTimeout(r, 1000));
      }

      throw new Error('Processing timeout');

    } catch (err) {
      setError(err.message);
    } finally {
      setUploading(false);
      setProcessing(false);
    }
  };

  return (
    <div className="uploader">
      <input
        type="file"
        accept=".pdf,.epub,.txt"
        onChange={(e) => setFile(e.target.files[0])}
        disabled={uploading || processing}
      />

      <select
        value={chunkingStrategy}
        onChange={(e) => setChunkingStrategy(e.target.value)}
        disabled={uploading || processing}
      >
        <option value="AUTO">Auto (Default)</option>
        <option value="CHAPTER_BASED">By Chapter</option>
        <option value="SECTION_BASED">By Section</option>
        <option value="SIZE_BASED">By Size</option>
        <option value="PAGE_BASED">By Page</option>
      </select>

      <button onClick={handleUpload} disabled={!file || uploading || processing}>
        Upload
      </button>

      {(uploading || processing) && (
        <div className="progress">{progress}</div>
      )}

      {error && <div className="error">{error}</div>}
    </div>
  );
};
```

---

### Document List with Pagination

```javascript
const DocumentList = ({ token }) => {
  const [documents, setDocuments] = useState([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);

  const loadDocuments = async () => {
    setLoading(true);

    try {
      const response = await fetch(
        `/api/documents?page=${page}&size=10`,
        { headers: { 'Authorization': `Bearer ${token}` } }
      );

      const data = await response.json();
      setDocuments(data.content);
      setTotalPages(data.totalPages);
    } catch (error) {
      console.error('Failed to load documents:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDocuments();
  }, [page]);

  const handleDelete = async (documentId, filename) => {
    if (!confirm(`Delete "${filename}"? This cannot be undone.`)) {
      return;
    }

    try {
      await fetch(`/api/documents/${documentId}`, {
        method: 'DELETE',
        headers: { 'Authorization': `Bearer ${token}` }
      });

      loadDocuments(); // Refresh list
    } catch (error) {
      alert('Failed to delete document');
    }
  };

  if (loading) return <div>Loading...</div>;

  return (
    <div>
      <h2>My Documents</h2>
      
      <table>
        <thead>
          <tr>
            <th>Filename</th>
            <th>Status</th>
            <th>Chunks</th>
            <th>Uploaded</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {documents.map(doc => (
            <tr key={doc.id}>
              <td>{doc.originalFilename}</td>
              <td>
                <span className={`status status-${doc.status.toLowerCase()}`}>
                  {doc.status}
                </span>
              </td>
              <td>{doc.totalChunks}</td>
              <td>{new Date(doc.uploadedAt).toLocaleString()}</td>
              <td>
                <button onClick={() => viewChunks(doc.id)}>View</button>
                <button onClick={() => handleDelete(doc.id, doc.originalFilename)}>
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="pagination">
        <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}>
          Previous
        </button>
        <span>Page {page + 1} of {totalPages}</span>
        <button onClick={() => setPage(p => p + 1)} disabled={page >= totalPages - 1}>
          Next
        </button>
      </div>
    </div>
  );
};
```

---

### Chunk Selection for Quiz Generation

```javascript
const ChunkSelector = ({ documentId, token, onSelect }) => {
  const [chunks, setChunks] = useState([]);
  const [selectedIndices, setSelectedIndices] = useState([]);

  useEffect(() => {
    loadChunks();
  }, [documentId]);

  const loadChunks = async () => {
    const response = await fetch(`/api/documents/${documentId}/chunks`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });

    const chunksData = await response.json();
    setChunks(chunksData);
  };

  const toggleChunk = (index) => {
    setSelectedIndices(prev => 
      prev.includes(index)
        ? prev.filter(i => i !== index)
        : [...prev, index]
    );
  };

  const handleConfirm = () => {
    onSelect(selectedIndices);
  };

  return (
    <div className="chunk-selector">
      <h3>Select Chapters for Quiz</h3>
      
      <div className="chunks">
        {chunks.map(chunk => (
          <div 
            key={chunk.id}
            className={`chunk ${selectedIndices.includes(chunk.chunkIndex) ? 'selected' : ''}`}
            onClick={() => toggleChunk(chunk.chunkIndex)}
          >
            <input
              type="checkbox"
              checked={selectedIndices.includes(chunk.chunkIndex)}
              onChange={() => {}}
            />
            <div className="info">
              <h4>{chunk.title}</h4>
              <p>
                {chunk.wordCount} words | 
                Pages {chunk.startPage}-{chunk.endPage} | 
                Type: {chunk.chunkType}
              </p>
              {chunk.chapterTitle && (
                <p className="meta">Chapter: {chunk.chapterTitle}</p>
              )}
            </div>
          </div>
        ))}
      </div>

      <div className="actions">
        <button onClick={() => setSelectedIndices([])}>
          Clear Selection
        </button>
        <button onClick={() => setSelectedIndices(chunks.map(c => c.chunkIndex))}>
          Select All
        </button>
        <button onClick={handleConfirm} disabled={selectedIndices.length === 0}>
          Generate Quiz from {selectedIndices.length} Chapter(s)
        </button>
      </div>
    </div>
  );
};
```

---

### Reprocess with Different Strategy

```javascript
const ReprocessDocument = ({ documentId, token, onComplete }) => {
  const [strategy, setStrategy] = useState('CHAPTER_BASED');
  const [maxChunkSize, setMaxChunkSize] = useState(5000);
  const [processing, setProcessing] = useState(false);

  const handleReprocess = async () => {
    if (!confirm('Reprocess document? This will replace existing chunks.')) {
      return;
    }

    setProcessing(true);

    try {
      // Start reprocessing
      const response = await fetch(`/api/documents/${documentId}/reprocess`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          chunkingStrategy: strategy,
          maxChunkSize: maxChunkSize,
          storeChunks: true
        })
      });

      if (!response.ok) {
        const error = await response.json();
        throw new Error(error.details?.[0] || 'Reprocess failed');
      }

      // Poll for completion
      let attempts = 0;
      while (attempts < 60) {
        await new Promise(r => setTimeout(r, 1000));
        
        const statusResponse = await fetch(
          `/api/documents/${documentId}/status`,
          { headers: { 'Authorization': `Bearer ${token}` } }
        );

        const doc = await statusResponse.json();

        if (doc.status === 'PROCESSED') {
          alert(`Reprocessed successfully! ${doc.totalChunks} chunks created`);
          onComplete(doc);
          return;
        } else if (doc.status === 'FAILED') {
          throw new Error(doc.processingError || 'Reprocessing failed');
        }

        attempts++;
      }

      throw new Error('Reprocessing timeout');

    } catch (error) {
      alert(error.message);
    } finally {
      setProcessing(false);
    }
  };

  return (
    <div className="reprocess">
      <h3>Reprocess Document</h3>
      
      <label>
        Chunking Strategy:
        <select value={strategy} onChange={(e) => setStrategy(e.target.value)}>
          <option value="AUTO">Auto</option>
          <option value="CHAPTER_BASED">Chapter-Based</option>
          <option value="SECTION_BASED">Section-Based</option>
          <option value="SIZE_BASED">Size-Based</option>
          <option value="PAGE_BASED">Page-Based</option>
        </select>
      </label>

      <label>
        Max Chunk Size:
        <input
          type="number"
          value={maxChunkSize}
          onChange={(e) => setMaxChunkSize(Number(e.target.value))}
          min={100}
          max={100000}
          step={500}
        />
        <span className="hint">100-100,000 characters</span>
      </label>

      <button onClick={handleReprocess} disabled={processing}>
        {processing ? 'Reprocessing...' : 'Reprocess'}
      </button>
    </div>
  );
};
```

---

### Document Browser Component

```javascript
const DocumentBrowser = () => {
  const [documents, setDocuments] = useState([]);
  const [selectedDoc, setSelectedDoc] = useState(null);
  const [chunks, setChunks] = useState([]);
  const [page, setPage] = useState(0);

  const loadDocuments = async () => {
    const response = await fetch(`/api/documents?page=${page}&size=10`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    const data = await response.json();
    setDocuments(data.content);
  };

  const selectDocument = async (documentId) => {
    // Load document details
    const docResponse = await fetch(`/api/documents/${documentId}`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    const doc = await docResponse.json();
    setSelectedDoc(doc);

    // Load chunks if processed
    if (doc.status === 'PROCESSED') {
      const chunksResponse = await fetch(`/api/documents/${documentId}/chunks`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });
      const chunksData = await chunksResponse.json();
      setChunks(chunksData);
    }
  };

  useEffect(() => {
    loadDocuments();
  }, [page]);

  return (
    <div className="document-browser">
      <div className="document-list">
        <h3>Your Documents</h3>
        {documents.map(doc => (
          <div 
            key={doc.id}
            className={`document-item ${selectedDoc?.id === doc.id ? 'active' : ''}`}
            onClick={() => selectDocument(doc.id)}
          >
            <h4>{doc.originalFilename}</h4>
            <p>Status: {doc.status} | Chunks: {doc.totalChunks}</p>
            <p className="date">
              {new Date(doc.uploadedAt).toLocaleDateString()}
            </p>
          </div>
        ))}
      </div>

      {selectedDoc && (
        <div className="document-details">
          <h3>{selectedDoc.originalFilename}</h3>
          <div className="meta">
            <p><strong>Title:</strong> {selectedDoc.title || 'N/A'}</p>
            <p><strong>Author:</strong> {selectedDoc.author || 'N/A'}</p>
            <p><strong>Pages:</strong> {selectedDoc.totalPages || 'N/A'}</p>
            <p><strong>Size:</strong> {(selectedDoc.fileSize / 1024).toFixed(2)} KB</p>
            <p><strong>Chunks:</strong> {selectedDoc.totalChunks}</p>
            <p><strong>Status:</strong> {selectedDoc.status}</p>
          </div>

          {selectedDoc.status === 'PROCESSED' && chunks.length > 0 && (
            <div className="chunks">
              <h4>Chunks</h4>
              {chunks.map(chunk => (
                <div key={chunk.id} className="chunk">
                  <h5>{chunk.title}</h5>
                  <p>
                    Index: {chunk.chunkIndex} | 
                    Words: {chunk.wordCount} | 
                    Type: {chunk.chunkType}
                  </p>
                  {chunk.chapterTitle && <p>Chapter: {chunk.chapterTitle}</p>}
                </div>
              ))}
            </div>
          )}

          {selectedDoc.status === 'FAILED' && (
            <div className="error">
              <p>Processing failed: {selectedDoc.processingError}</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
```

---

### File Validation

```javascript
const validateFile = (file) => {
  const errors = [];

  // Check if file exists
  if (!file) {
    errors.push('Please select a file');
    return { valid: false, errors };
  }

  // Check file size (150 MB limit)
  const maxSize = 150 * 1024 * 1024; // 150 MB in bytes
  if (file.size > maxSize) {
    errors.push(`File too large. Maximum size is 150 MB (file is ${(file.size / 1024 / 1024).toFixed(2)} MB)`);
  }

  if (file.size === 0) {
    errors.push('File is empty');
  }

  // Check file type
  const allowedTypes = [
    'application/pdf',
    'application/epub+zip',
    'text/plain'
  ];

  const allowedExtensions = ['.pdf', '.epub', '.txt'];
  
  const hasValidType = allowedTypes.includes(file.type);
  const hasValidExtension = allowedExtensions.some(ext => 
    file.name.toLowerCase().endsWith(ext)
  );

  if (!hasValidType && !hasValidExtension) {
    errors.push('Unsupported file type. Please upload PDF, EPUB, or TXT files.');
  }

  return {
    valid: errors.length === 0,
    errors: errors
  };
};

// Usage
const handleFileSelect = (file) => {
  const validation = validateFile(file);
  
  if (!validation.valid) {
    setErrors(validation.errors);
    return;
  }

  setSelectedFile(file);
  setErrors([]);
};
```

---

## Security Considerations

### Document Ownership

1. **Automatic Assignment**: Documents auto-owned by uploader
2. **Ownership Validation**: All operations validate ownership
3. **No Sharing**: No cross-user document access
4. **403 Enforcement**: Non-owners blocked with Forbidden error

### File Upload Security

1. **Type Validation**: Only PDF, EPUB, TXT accepted
2. **Size Limits**: 150 MB maximum enforced
3. **Content Scanning**: Files processed safely server-side
4. **Malware Protection**: Consider virus scanning before upload
5. **Input Sanitization**: Filenames and metadata sanitized

### Data Privacy

1. **User Isolation**: Users see only their documents
2. **Secure Storage**: Files stored securely on server
3. **Access Control**: Ownership verified on every request
4. **Deletion**: Complete removal of file and chunks

### Best Practices

**Frontend**:
- Validate file types client-side before upload
- Show file size limits clearly
- Display file size before upload
- Implement upload progress indicators
- Use HTTPS for all uploads
- Clear file inputs after upload
- Cache document list appropriately

**Error Handling**:
- Handle unsupported file types gracefully
- Show processing errors clearly
- Implement retry for transient errors
- Display validation errors inline
- Handle ownership errors appropriately

**Performance**:
- Paginate document lists
- Lazy load chunks
- Cache processed documents
- Debounce status polling
- Implement virtual scrolling for large chunk lists

**UX**:
- Show processing progress
- Allow cancellation where possible
- Confirm destructive operations
- Provide clear status indicators
- Show chunk previews
- Enable chunk selection for quiz generation

**Testing**:
- Test with various file types and sizes
- Test with corrupted files
- Verify ownership validation
- Test chunking strategies
- Test reprocessing
- Verify deletion works correctly

---

## Common Use Cases

### 1. Simple Document Upload

```javascript
const uploadDocument = async (file, token) => {
  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch('/api/documents/upload', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` },
    body: formData
  });

  if (!response.ok) {
    throw new Error('Upload failed');
  }

  return await response.json();
};
```

---

### 2. Status Polling

```javascript
const pollUntilProcessed = async (documentId, token) => {
  let attempts = 0;
  const maxAttempts = 60;

  while (attempts < maxAttempts) {
    const response = await fetch(`/api/documents/${documentId}/status`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });

    const doc = await response.json();

    if (doc.status === 'PROCESSED') {
      return doc;
    } else if (doc.status === 'FAILED') {
      throw new Error(doc.processingError || 'Processing failed');
    }

    attempts++;
    await new Promise(r => setTimeout(r, 1000));
  }

  throw new Error('Timeout');
};
```

---

### 3. Get Specific Chapter

```javascript
const getChapterByNumber = async (documentId, chapterNumber, token) => {
  const response = await fetch(`/api/documents/${documentId}/chunks`, {
    headers: { 'Authorization': `Bearer ${token}` }
  });

  const chunks = await response.json();
  
  const chapter = chunks.find(c => c.chapterNumber === chapterNumber);
  
  if (!chapter) {
    throw new Error(`Chapter ${chapterNumber} not found`);
  }

  return chapter;
};
```

---

## Troubleshooting

### Common Issues

**1. Upload Fails with 400**
- **Causes**: Empty file, unsupported type, file too large
- **Solutions**: Validate file client-side, check file type and size
- **Prevention**: Show file requirements before upload

**2. Processing Never Completes**
- **Causes**: Large file, complex structure, server load
- **Solutions**: Increase timeout, check server status
- **Typical Time**: Simple PDFs: 5-15s, Complex: 30-60s

**3. No Chunks After Processing**
- **Causes**: Document structure not detected, SIZE_BASED fallback
- **Solutions**: Try different chunking strategy, check document structure
- **Debug**: Check `totalChunks` in response

**4. 403 on Document Access**
- **Causes**: Trying to access another user's document
- **Solutions**: Verify document ownership
- **Prevention**: Only show user's own documents

**5. Reprocess Fails**
- **Causes**: Invalid chunk size, document already processing
- **Solutions**: Validate parameters, wait for current processing to complete
- **Prevention**: Disable reprocess during processing

### Debug Checklist

- [ ] Valid authentication token provided
- [ ] User owns the document
- [ ] File type is supported (.pdf, .epub, .txt)
- [ ] File size under 150 MB
- [ ] File is not empty or corrupted
- [ ] Chunk size in valid range (100-100,000)
- [ ] Valid chunking strategy specified
- [ ] Document status is appropriate for operation
- [ ] Network connection stable
- [ ] HTTPS used for uploads

---

## Chunking Strategy Guide

### When to Use Each Strategy

**AUTO**:
- Default option, lets server decide
- Maps to SIZE_BASED internally
- Safe choice for any document

**CHAPTER_BASED**:
- Best for: Books, tutorials, manuals
- Requires: Clear chapter headings
- Produces: One chunk per chapter
- Use when: Document has well-defined chapters

**SECTION_BASED**:
- Best for: Academic papers, articles, reports
- Requires: Section headings (H2, H3 in markdown)
- Produces: One chunk per section
- Use when: Document has clear sectioning

**SIZE_BASED**:
- Best for: Unstructured text, transcripts
- Requires: Nothing specific
- Produces: Equal-sized chunks
- Use when: No clear structure or other strategies fail

**PAGE_BASED**:
- Best for: Scanned documents, slide decks
- Requires: Page information from source
- Produces: One chunk per page or page range
- Use when: Page boundaries are meaningful

### Chunk Size Guidelines

| Document Type | Recommended Max Size | Strategy |
| --- | --- | --- |
| Short articles | 3,000-5,000 | SIZE_BASED or SECTION_BASED |
| Books/Tutorials | 5,000-10,000 | CHAPTER_BASED |
| Research papers | 4,000-7,000 | SECTION_BASED |
| Transcripts | 3,000-5,000 | SIZE_BASED |
| Slide decks | 2,000-4,000 | PAGE_BASED |

---

## API Evolution Notes

### Current Limitations

1. **No Streaming**: Entire file must be uploaded at once
2. **No Progress Callbacks**: Cannot track upload percentage
3. **Synchronous Processing**: Must poll for status
4. **No Preview**: Cannot preview chunks before quiz generation
5. **No Sharing**: Cannot share documents between users

### Future Enhancements

- Chunked file uploads for large files
- WebSocket for real-time processing updates
- Document preview before processing
- Chunk merging and splitting
- Custom chunk boundaries
- Document templates
- Collaborative document access
- Document versioning
- Metadata editing
- Bulk upload

