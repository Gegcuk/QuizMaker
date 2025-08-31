# Document Process Feature Documentation

## Overview

The Document Process feature allows users to upload, process, and structure large documents for quiz generation. The system automatically chunks large documents, processes them with AI to identify chapters, and provides endpoints for document management and content extraction.

## Base URL

```
http://localhost:8080/api/v1/documentProcess/documents
```

## Authentication

All endpoints require authentication. Include the JWT token in the Authorization header:
```
Authorization: Bearer <your-jwt-token>
```

## Endpoints

### 1. Upload Document (File)

**POST** `/` (with `Content-Type: multipart/form-data`)

Upload a new document file for processing.

#### Request
- **Content-Type**: `multipart/form-data`
- **Body**: 
  - `file`: The document file (PDF, DOCX, TXT)
  - `originalName`: Original filename (optional)

#### Response
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "document.pdf",
  "charCount": 1024000,
  "status": "INGESTED"
}
```

#### Status Codes
- `201`: Created successfully
- `400`: Bad request (invalid file format, missing file)
- `500`: Internal server error

---

### 2. Upload Document (JSON Text)

**POST** `/` (with `Content-Type: application/json`)

Upload document content as JSON text.

#### Request
- **Content-Type**: `application/json`
- **Body**: 
```json
{
  "text": "Document content as text...",
  "language": "en"
}
```
- **Query Parameters**:
  - `originalName`: Original filename (optional)

#### Response
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "text-input",
  "charCount": 1024000,
  "status": "INGESTED"
}
```

#### Status Codes
- `201`: Created successfully
- `400`: Bad request (invalid JSON)
- `500`: Internal server error

---

### 3. Get Document Metadata

**GET** `/{id}`

Get document metadata by ID.

#### Path Parameters
- `id`: UUID of the document

#### Response
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "document.pdf",
  "charCount": 1024000,
  "status": "INGESTED"
}
```

#### Status Codes
- `200`: Success
- `404`: Document not found

---

### 4. Get Document Head

**GET** `/{id}/head`

Get lightweight document info without loading full text.

#### Path Parameters
- `id`: UUID of the document

#### Response
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "document.pdf",
  "charCount": 1024000,
  "status": "INGESTED"
}
```

#### Status Codes
- `200`: Success
- `404`: Document not found

---

### 5. Get Text Slice

**GET** `/{id}/text`

Extract a slice of text from the document.

#### Path Parameters
- `id`: UUID of the document

#### Query Parameters
- `start`: Starting character position (default: 0)
- `end`: Ending character position (optional - defaults to end of text)

#### Response
```json
{
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "start": 0,
  "end": 1000,
  "text": "Extracted text content from position 0 to 1000..."
}
```

#### Status Codes
- `200`: Success
- `400`: Invalid range parameters
- `404`: Document not found

---

### 6. Get Document Structure

**GET** `/{id}/structure`

Retrieve the document structure.

#### Path Parameters
- `id`: UUID of the document

#### Query Parameters
- `format`: Structure format - "tree" (hierarchical) or "flat" (linear, default: "tree")

#### Response (Tree Format)
```json
{
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "structure": [
    {
      "id": "node-1-uuid",
      "title": "Chapter 1: Introduction",
      "type": "CHAPTER",
      "depth": 0,
      "startOffset": 0,
      "endOffset": 15000,
      "startAnchor": "Chapter 1: Introduction\n\nThis chapter provides...",
      "endAnchor": "...concludes the introduction to our topic.",
      "aiConfidence": 0.95
    }
  ]
}
```

#### Response (Flat Format)
```json
{
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "nodes": [
    {
      "id": "node-1-uuid",
      "title": "Chapter 1: Introduction",
      "type": "CHAPTER",
      "depth": 0,
      "startOffset": 0,
      "endOffset": 15000,
      "aiConfidence": 0.95
    }
  ]
}
```

#### Status Codes
- `200`: Success
- `400`: Invalid format parameter
- `404`: Document not found

---

### 7. Build Document Structure

**POST** `/{id}/structure`

Trigger AI-powered document structure generation.

#### Path Parameters
- `id`: UUID of the document

#### Response
```json
{
  "status": "STRUCTURED",
  "message": "Structure built successfully"
}
```

#### Status Codes
- `200`: Structure built successfully
- `400`: Document not found or already processed
- `500`: Internal server error

---

### 8. Extract Content by Node

**GET** `/{id}/extract`

Extract text content for a specific document node.

#### Path Parameters
- `id`: UUID of the document

#### Query Parameters
- `nodeId`: UUID of the node to extract (required)

#### Response
```json
{
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "nodeId": "node-1-uuid",
  "nodeTitle": "Chapter 1: Introduction",
  "nodeType": "CHAPTER",
  "startOffset": 0,
  "endOffset": 15000,
  "content": "Chapter 1: Introduction\n\nThis chapter provides a comprehensive overview...",
  "contentLength": 15000
}
```

#### Status Codes
- `200`: Success
- `400`: Missing nodeId parameter
- `404`: Document or node not found

---

## Data Transfer Objects (DTOs)

### IngestRequest (JSON Upload)
```json
{
  "text": "string (required)",
  "language": "string (required, e.g., 'en')"
}
```

### IngestResponse
```json
{
  "id": "UUID",
  "name": "string",
  "charCount": "long",
  "status": "DocumentStatus enum"
}
```

### DocumentView
```json
{
  "id": "UUID",
  "name": "string",
  "charCount": "long",
  "status": "DocumentStatus enum"
}
```

### TextSliceResponse
```json
{
  "documentId": "UUID",
  "start": "int",
  "end": "int",
  "text": "string"
}
```

### StructureTreeResponse
```json
{
  "documentId": "UUID",
  "structure": [
    {
      "id": "UUID",
      "title": "string",
      "type": "NodeType enum (CHAPTER)",
      "depth": "int",
      "startOffset": "int",
      "endOffset": "int",
      "startAnchor": "string",
      "endAnchor": "string",
      "aiConfidence": "BigDecimal (0.0-1.0)"
    }
  ]
}
```

### StructureFlatResponse
```json
{
  "documentId": "UUID",
  "nodes": [
    {
      "id": "UUID",
      "title": "string",
      "type": "NodeType enum (CHAPTER)",
      "depth": "int",
      "startOffset": "int",
      "endOffset": "int",
      "aiConfidence": "BigDecimal (0.0-1.0)"
    }
  ]
}
```

### StructureBuildResponse
```json
{
  "status": "string (STRUCTURED, FAILED, ERROR)",
  "message": "string"
}
```

### ExtractResponse
```json
{
  "documentId": "UUID",
  "nodeId": "UUID",
  "nodeTitle": "string",
  "nodeType": "NodeType enum (CHAPTER)",
  "startOffset": "int",
  "endOffset": "int",
  "content": "string",
  "contentLength": "int"
}
```

## Enums

### DocumentStatus
- `INGESTED`: Document uploaded and ingested successfully
- `STRUCTURED`: Document has been processed and structured
- `FAILED`: Processing failed

### NodeType
- `CHAPTER`: Individual chapter with clear title

### Structure Format
- `tree`: Hierarchical structure format (default)
- `flat`: Linear structure format

## Error Responses

All endpoints return consistent error responses:

```json
{
  "status": "ERROR",
  "message": "Error description",
  "errorCode": "ERROR_CODE (optional)",
  "timestamp": "ISO-8601 datetime"
}
```

### Common Error Codes
- `DOCUMENT_NOT_FOUND`: Document with specified ID not found
- `DOCUMENT_ALREADY_PROCESSED`: Document has already been structured
- `INVALID_FILE_FORMAT`: Unsupported file format
- `FILE_TOO_LARGE`: File exceeds maximum size limit
- `PROCESSING_FAILED`: Document processing failed
- `UNAUTHORIZED`: Authentication required
- `FORBIDDEN`: Insufficient permissions

## File Format Support

### Supported Formats
- **PDF**: Portable Document Format
- **DOCX**: Microsoft Word Document
- **TXT**: Plain Text File

### File Size Limits
- **Maximum file size**: 50 MB
- **Recommended file size**: < 20 MB for optimal processing

## Processing Information

### Chunking Strategy
- Large documents (>1M characters) are automatically chunked
- Chunk size: ~875k characters per chunk
- Overlap: ~87.5k characters between chunks
- Sequential processing with context awareness

### AI Processing
- Focuses exclusively on identifying **chapters**
- Ignores parts, sections, subsections, and supplementary content
- Uses configurable AI models (GPT-4o-mini recommended)
- Provides confidence scores for each identified chapter

### Processing Time Estimates
- **Small documents** (< 100k characters): 30-60 seconds
- **Medium documents** (100k-500k characters): 1-3 minutes
- **Large documents** (> 500k characters): 3-10 minutes

## Frontend Integration Guidelines

### 1. Upload Flow
1. **File Upload**: Use multipart form data with `file` parameter
2. **JSON Upload**: Send JSON with `text` and `language` fields
3. **Response**: Document is immediately available with `INGESTED` status
4. **Next Step**: Call structure building endpoint to process with AI

### 2. Document Management
- Use `GET /{id}` to get document metadata
- Use `GET /{id}/head` for lightweight document info
- Use `GET /{id}/text` for text extraction with pagination

### 3. Structure Building
- Call `POST /{id}/structure` to trigger AI processing
- Monitor response for `STRUCTURED` status
- Handle `FAILED` status with retry options

### 4. Structure Display
- Use `GET /{id}/structure?format=tree` for hierarchical view
- Use `GET /{id}/structure?format=flat` for linear view
- Show chapter titles, confidence scores, and content length
- Provide expand/collapse functionality for large structures

### 5. Content Extraction
- Use `GET /{id}/extract?nodeId={nodeId}` for chapter content
- Use `GET /{id}/text?start={start}&end={end}` for custom ranges
- Implement pagination for large content chunks

### 6. Error Handling
- Display user-friendly error messages
- Provide retry options for failed operations
- Log detailed errors for debugging

## Rate Limiting

- **Upload**: 10 files per hour per user
- **Structure generation**: 5 requests per hour per user
- **Content extraction**: 100 requests per hour per user

## Best Practices

1. **File Preparation**: Ensure documents are well-formatted with clear chapter headings
2. **Processing**: Start structure generation immediately after upload
3. **Caching**: Cache document structure and status to reduce API calls
4. **Error Recovery**: Implement retry logic for transient failures
5. **User Feedback**: Provide clear progress indicators and estimated completion times
