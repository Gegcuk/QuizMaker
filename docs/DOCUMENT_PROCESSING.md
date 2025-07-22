# Document Processing and Chunking System

## Overview

The document processing system allows you to upload large files (PDF, TXT) and automatically split them into smaller,
manageable chunks that can be sent to AI services for quiz generation. The system supports intelligent chunking
strategies and can handle books, articles, and other large documents.

## Features

### Supported File Formats

- **PDF**: Full text extraction with chapter/section detection
- **TXT**: Plain text files with automatic structure detection
- **Future**: EPUB support (planned)

### Chunking Strategies

1. **AUTO** (Default): Automatically determines the best strategy based on document structure
2. **CHAPTER_BASED**: Splits by chapters, then by sections if needed
3. **SECTION_BASED**: Splits by sections only
4. **SIZE_BASED**: Splits by character count with sentence boundary awareness
5. **PAGE_BASED**: Splits by page count

### Intelligent Chunking

The system automatically:

- Detects chapter and section headers using regex patterns
- Splits large chapters/sections into smaller chunks
- Maintains sentence boundaries when possible
- Preserves document structure and metadata
- Handles documents without clear structure by falling back to size-based chunking

## Configuration

### Application Properties

All defaults are configured in `application.properties`:

```properties
# Document Processing Configuration
document.chunking.default-max-chunk-size=4000
document.chunking.default-strategy=CHAPTER_BASED
```

### Configuration Parameters

| Parameter                | Default       | Description                                 |
|--------------------------|---------------|---------------------------------------------|
| `default-max-chunk-size` | 4000          | Maximum characters per chunk (configurable) |
| `default-strategy`       | CHAPTER_BASED | Default chunking strategy (configurable)    |

**Note**: There are no hardcoded defaults in the code. All values come from the properties file.

## API Endpoints

### Upload Document

```http
POST /api/documents/upload
Content-Type: multipart/form-data

Parameters:
- file: The document file to upload
- chunkingStrategy: AUTO, CHAPTER_BASED, SECTION_BASED, SIZE_BASED, PAGE_BASED (optional)
- maxChunkSize: Maximum characters per chunk (optional, default: 4000)
```

### Get Configuration

```http
GET /api/documents/config
```

### Get Document

```http
GET /api/documents/{documentId}
```

### Get User Documents

```http
GET /api/documents?page=0&size=10
```

### Get Document Chunks

```http
GET /api/documents/{documentId}/chunks
```

### Get Specific Chunk

```http
GET /api/documents/{documentId}/chunks/{chunkIndex}
```

### Delete Document

```http
DELETE /api/documents/{documentId}
```

### Reprocess Document

```http
POST /api/documents/{documentId}/reprocess
Content-Type: application/json

{
  "chunkingStrategy": "CHAPTER_BASED",
  "maxChunkSize": 4000
}
```

### Get Document Status

```http
GET /api/documents/{documentId}/status
```

## Database Schema

### Documents Table

- `id`: UUID primary key
- `original_filename`: Original file name
- `content_type`: MIME type
- `file_size`: File size in bytes
- `file_path`: Path to stored file
- `status`: UPLOADED, PROCESSING, PROCESSED, FAILED
- `uploaded_at`: Upload timestamp
- `processed_at`: Processing completion timestamp
- `user_id`: Foreign key to users table
- `title`: Extracted document title
- `author`: Extracted document author
- `total_pages`: Number of pages
- `total_chunks`: Number of chunks created
- `processing_error`: Error message if processing failed

### Document Chunks Table

- `id`: UUID primary key
- `document_id`: Foreign key to documents table
- `chunk_index`: Sequential chunk number
- `title`: Chunk title
- `content`: Chunk text content (TEXT column)
- `start_page`: Starting page number
- `end_page`: Ending page number
- `word_count`: Number of words in chunk
- `character_count`: Number of characters in chunk
- `created_at`: Chunk creation timestamp
- `chapter_title`: Chapter title if applicable
- `section_title`: Section title if applicable
- `chapter_number`: Chapter number if applicable
- `section_number`: Section number if applicable
- `chunk_type`: CHAPTER, SECTION, PAGE_BASED, SIZE_BASED

## Usage Examples

### Upload a PDF Book

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@book.pdf" \
  -F "maxChunkSize=3000"
```

### Upload with Custom Strategy

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@book.pdf" \
  -F "chunkingStrategy=SIZE_BASED" \
  -F "maxChunkSize=2000"
```

### Get Current Configuration

```bash
curl http://localhost:8080/api/documents/config
```

### Get All Chunks for Processing

```bash
curl http://localhost:8080/api/documents/{documentId}/chunks
```

### Process Chunks for AI Quiz Generation

```javascript
// Get chunks and send to AI
const chunks = await fetch('/api/documents/' + documentId + '/chunks');
const chunkData = await chunks.json();

for (const chunk of chunkData) {
  // Send chunk.content to OpenAI for quiz generation
  const quizQuestions = await generateQuizFromChunk(chunk.content);
  // Store or return quiz questions
}
```

### Reprocess Document with New Settings

```bash
curl -X POST http://localhost:8080/api/documents/{documentId}/reprocess \
  -H "Content-Type: application/json" \
  -d '{
    "chunkingStrategy": "CHAPTER_BASED",
    "maxChunkSize": 2000
  }'
```

## File Storage

Documents are stored in the `uploads/documents/` directory with UUID-based filenames to prevent conflicts.

## Chunking Logic

### Simple and Effective Approach

1. **Chapter-based splitting**: If document has chapters, split by chapters first
2. **Section-based splitting**: If chapters have sections, split by sections
3. **Size-based splitting**: If content is too large, split by character count
4. **Sentence boundary detection**: Always respect sentence endings when splitting
5. **Automatic fallback**: If no structure found, use size-based chunking

### How It Works

1. **Upload document** → Parse and extract text
2. **Detect structure** → Find chapters and sections
3. **Split intelligently** → Respect structure but ensure chunks aren't too large
4. **Store chunks** → Save to database for AI processing

## Error Handling

The system provides comprehensive error handling:

- File format validation
- Processing status tracking
- Detailed error messages
- Automatic cleanup on failure
- Transaction rollback on errors

## Performance Considerations

- Large files are processed asynchronously
- Chunks are created in memory and batch-saved
- File storage uses efficient UUID naming
- Database queries are optimized with proper indexing
- Memory usage is controlled through streaming

## Future Enhancements

1. **EPUB Support**: Add EPUB parser for e-book processing
2. **Async Processing**: Move to background job processing
3. **Caching**: Add Redis caching for frequently accessed chunks
4. **Compression**: Add support for compressed file formats
5. **OCR**: Add OCR support for image-based PDFs
6. **Language Detection**: Automatic language detection for multi-language documents 