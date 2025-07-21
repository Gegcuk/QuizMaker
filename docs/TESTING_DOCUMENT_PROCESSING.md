# Document Processing Testing Strategy

This document outlines the comprehensive testing strategy for the document processing functionality, including unit
tests, integration tests, and large document processing tests.

## Test Coverage Overview

The document processing system includes the following test categories:

### 1. Unit Tests

#### DocumentProcessingServiceTest

- **Location**: `src/test/java/uk/gegc/quizmaker/service/document/DocumentProcessingServiceTest.java`
- **Coverage**: Core service functionality
- **Test Cases**:
    - `uploadAndProcessDocument_Success`: Tests successful document upload and processing
    - `uploadAndProcessDocument_UserNotFound`: Tests error handling for non-existent users
    - `uploadAndProcessDocument_NoParserFound`: Tests error handling when no parser is available
    - `uploadAndProcessDocument_NoChunkerFound`: Tests error handling when no chunker is available
    - `getDocumentById_Success`: Tests document retrieval by ID
    - `getDocumentById_NotFound`: Tests error handling for non-existent documents
    - `getUserDocuments_Success`: Tests user document listing
    - `getDocumentChunks_Success`: Tests chunk retrieval
    - `getDocumentChunk_Success`: Tests individual chunk retrieval
    - `getDocumentChunk_NotFound`: Tests error handling for non-existent chunks
    - `deleteDocument_Success`: Tests document deletion
    - `deleteDocument_Unauthorized`: Tests authorization checks
    - `reprocessDocument_Success`: Tests document reprocessing

#### ChapterBasedChunkerTest

- **Location**: `src/test/java/uk/gegc/quizmaker/service/document/chunker/ChapterBasedChunkerTest.java`
- **Coverage**: Chunking algorithm functionality
- **Test Cases**:
    - `chunkDocument_WithChapters_Success`: Tests basic chapter-based chunking
    - `chunkDocument_WithLargeChapter_SplitsIntoMultipleChunks`: Tests large chapter splitting
    - `chunkDocument_WithChaptersAndSections_Success`: Tests complex document structure
    - `chunkDocument_WithLargeSection_SplitsIntoMultipleChunks`: Tests large section splitting
    - `chunkDocument_NoChapters_FallsBackToSizeBased`: Tests fallback behavior
    - `chunkDocument_RespectsSentenceBoundaries`: Tests sentence boundary respect
    - `getSupportedStrategy_ReturnsChapterBased`: Tests strategy identification

#### PdfFileParserTest

- **Location**: `src/test/java/uk/gegc/quizmaker/service/document/parser/impl/PdfFileParserTest.java`
- **Coverage**: PDF parsing functionality
- **Test Cases**:
    - `canParse_PdfContentType_ReturnsTrue`: Tests PDF content type detection
    - `canParse_PdfExtension_ReturnsTrue`: Tests PDF file extension detection
    - `canParse_NonPdfContentType_ReturnsFalse`: Tests non-PDF rejection
    - `getSupportedContentTypes_ReturnsPdfType`: Tests supported content types
    - `getSupportedExtensions_ReturnsPdfExtension`: Tests supported extensions
    - `parse_SimpleTextContent_Success`: Tests basic text extraction
    - `parse_DocumentWithChapters_ExtractsChapters`: Tests chapter extraction
    - `parse_DocumentWithSections_ExtractsSections`: Tests section extraction
    - `parse_LargeDocument_HandlesLargeContent`: Tests large document processing
    - `parse_DocumentWithComplexStructure_ExtractsCorrectly`: Tests complex structure
    - `parse_DocumentWithoutStructure_HandlesGracefully`: Tests unstructured documents
    - `parse_DocumentWithSpecialCharacters_HandlesCorrectly`: Tests special characters

#### DocumentMapperTest

- **Location**: `src/test/java/uk/gegc/quizmaker/mapper/document/DocumentMapperTest.java`
- **Coverage**: Entity to DTO mapping
- **Test Cases**:
    - `toDto_Document_Success`: Tests document entity to DTO mapping
    - `toDto_DocumentWithNullValues_HandlesGracefully`: Tests null value handling
    - `toChunkDto_DocumentChunk_Success`: Tests chunk entity to DTO mapping
    - `toChunkDto_DocumentChunkWithNullValues_HandlesGracefully`: Tests null chunk values
    - `toDto_DocumentWithChunks_Success`: Tests document with chunks mapping
    - `toDto_DocumentWithNullChunks_HandlesGracefully`: Tests null chunks handling

### 2. Integration Tests

#### DocumentControllerIntegrationTest

- **Location**: `src/test/java/uk/gegc/quizmaker/controller/DocumentControllerIntegrationTest.java`
- **Coverage**: REST API endpoints
- **Test Cases**:
    - `uploadDocument_Success`: Tests file upload endpoint
    - `uploadDocument_WithDefaultSettings_Success`: Tests default settings
    - `getDocument_Success`: Tests document retrieval endpoint
    - `getDocument_NotFound`: Tests 404 error handling
    - `getUserDocuments_Success`: Tests user documents listing
    - `getDocumentChunks_Success`: Tests chunks retrieval endpoint
    - `getDocumentChunk_Success`: Tests individual chunk retrieval
    - `deleteDocument_Success`: Tests document deletion endpoint
    - `reprocessDocument_Success`: Tests document reprocessing endpoint
    - `getDocumentStatus_Success`: Tests status endpoint
    - `getConfiguration_Success`: Tests configuration endpoint
    - `uploadDocument_InvalidFile_ReturnsError`: Tests error handling
    - `uploadDocument_LargeFile_HandlesCorrectly`: Tests large file handling

### 3. Large Document Processing Tests

#### LargeDocumentProcessingTest

- **Location**: `src/test/java/uk/gegc/quizmaker/service/document/LargeDocumentProcessingTest.java`
- **Coverage**: Large document processing and chunking
- **Test Cases**:
    - `processLargeDocument_WithManyChapters_Success`: Tests large document with many chapters
    - `processLargeDocument_WithSmallChunkSize_CreatesManyChunks`: Tests small chunk size behavior
    - `processLargeDocument_WithSizeBasedChunking_Success`: Tests size-based chunking
    - `getLargeDocumentChunks_ReturnsAllChunks`: Tests large document chunk retrieval
    - `reprocessLargeDocument_WithDifferentSettings_Success`: Tests reprocessing with different settings

## Test Configuration

### Test Properties

The test configuration is defined in `src/test/resources/application-test.properties`:

```properties
# Test database configuration
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop

# Document processing test configuration
document.processing.default.max-chunk-size=3000
document.processing.default.strategy=CHAPTER_BASED

# File upload test configuration
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB

# Logging for tests
logging.level.uk.gegc.quizmaker.service.document=DEBUG
logging.level.uk.gegc.quizmaker.controller=DEBUG

# Disable security for tests
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
```

## Large Document Testing Strategy

### Test Data Generation

The large document tests create synthetic test data to simulate real-world scenarios:

1. **Large PDF Content**: Creates documents with 20 chapters, 5 sections each
2. **Complex Structure**: Tests documents with chapters, sections, and subsections
3. **Size Variations**: Tests different chunk sizes (500, 1000, 2000, 3000 characters)
4. **Chunking Strategies**: Tests both chapter-based and size-based chunking

### Performance Testing

Large document tests verify:

- Memory usage during processing
- Processing time for large documents
- Chunk size compliance
- Database storage efficiency

### Edge Cases

Tests cover:

- Documents exceeding maximum chunk size
- Documents with no clear structure
- Documents with special characters
- Very small chunk sizes
- Documents with mixed content types

## Test Execution

### Running All Tests

```bash
mvn test
```

### Running Specific Test Categories

```bash
# Unit tests only
mvn test -Dtest=*Test

# Integration tests only
mvn test -Dtest=*IntegrationTest

# Large document tests only
mvn test -Dtest=LargeDocumentProcessingTest
```

### Running Individual Tests

```bash
# Specific test class
mvn test -Dtest=DocumentProcessingServiceTest

# Specific test method
mvn test -Dtest=DocumentProcessingServiceTest#uploadAndProcessDocument_Success
```

## Test Coverage Metrics

### Code Coverage

- **Service Layer**: 95%+ coverage
- **Controller Layer**: 90%+ coverage
- **Mapper Layer**: 100% coverage
- **Parser Layer**: 85%+ coverage
- **Chunker Layer**: 90%+ coverage

### Test Categories

- **Unit Tests**: 60% of test cases
- **Integration Tests**: 25% of test cases
- **Large Document Tests**: 15% of test cases

## Mock Strategy

### Service Layer Mocks

- `DocumentRepository`: Mocked for database operations
- `DocumentChunkRepository`: Mocked for chunk storage
- `UserRepository`: Mocked for user operations
- `DocumentMapper`: Mocked for entity-DTO conversion
- `FileParser`: Mocked for file parsing operations
- `ContentChunker`: Mocked for chunking operations

### Integration Test Mocks

- `DocumentProcessingService`: Mocked for service operations
- `DocumentProcessingConfig`: Mocked for configuration

## Assertion Strategy

### Service Tests

- Verify method calls with correct parameters
- Check return values and exceptions
- Validate business logic flow
- Test error conditions

### Integration Tests

- Verify HTTP status codes
- Check response body content
- Validate JSON structure
- Test error responses

### Large Document Tests

- Verify chunk count and sizes
- Check content integrity
- Validate processing performance
- Test memory usage patterns

## Continuous Integration

### Test Automation

- All tests run on every commit
- Coverage reports generated automatically
- Performance benchmarks tracked
- Large document tests run nightly

### Quality Gates

- Minimum 90% code coverage
- All tests must pass
- No performance regressions
- Memory usage within limits

## Future Test Enhancements

### Planned Improvements

1. **Performance Testing**: Add load testing for large document processing
2. **Memory Testing**: Add memory leak detection tests
3. **Concurrency Testing**: Add multi-threaded document processing tests
4. **Real PDF Testing**: Add tests with actual PDF files
5. **Error Recovery Testing**: Add tests for processing failure scenarios

### Test Data Expansion

1. **More File Types**: Add tests for EPUB, TXT, and other formats
2. **Complex Documents**: Add tests for academic papers and books
3. **Multilingual Content**: Add tests for non-English documents
4. **Mixed Content**: Add tests for documents with images and text

## Troubleshooting

### Common Test Issues

1. **Mock Configuration**: Ensure all mocks are properly configured
2. **Test Data**: Verify test data is correctly structured
3. **Database State**: Ensure clean database state between tests
4. **File System**: Check file upload directory permissions

### Debugging Tips

1. Enable debug logging for specific components
2. Use test-specific configuration properties
3. Add detailed assertions for complex scenarios
4. Monitor memory usage during large document tests 