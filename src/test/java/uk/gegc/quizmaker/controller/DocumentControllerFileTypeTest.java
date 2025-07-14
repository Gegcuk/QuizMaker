package uk.gegc.quizmaker.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import uk.gegc.quizmaker.config.DocumentProcessingConfig;
import uk.gegc.quizmaker.dto.document.DocumentDto;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.service.document.DocumentProcessingService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
class DocumentControllerFileTypeTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private DocumentProcessingService documentProcessingService;

    @MockitoBean
    private DocumentProcessingConfig documentProcessingConfig;

    private MockMvc mockMvc;
    private DocumentDto testDocumentDto;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
                
        testDocumentDto = new DocumentDto();
        testDocumentDto.setId(UUID.randomUUID());
        testDocumentDto.setOriginalFilename("test.pdf");
        testDocumentDto.setStatus(uk.gegc.quizmaker.model.document.Document.DocumentStatus.PROCESSED);
        testDocumentDto.setTotalChunks(5);
        
        // Setup default config values
        when(documentProcessingConfig.createDefaultRequest()).thenReturn(createDefaultRequest());
    }
    
    private ProcessDocumentRequest createDefaultRequest() {
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setMaxChunkSize(4000);
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        return request;
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_PdfFile_Success() throws Exception {
        // Arrange
        MockMultipartFile pdfFile = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                createPdfContent()
        );

        when(documentProcessingService.uploadAndProcessDocument(
                eq("testuser"), any(byte[].class), eq("test.pdf"), any(ProcessDocumentRequest.class)))
                .thenReturn(testDocumentDto);

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(pdfFile)
                        .param("maxChunkSize", "3000")
                        .param("chunkingStrategy", "CHAPTER_BASED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(testDocumentDto.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("test.pdf"))
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.totalChunks").value(5));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_TxtFile_Success() throws Exception {
        // Arrange
        MockMultipartFile txtFile = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                createTxtContent()
        );

        DocumentDto txtDocumentDto = new DocumentDto();
        txtDocumentDto.setId(UUID.randomUUID());
        txtDocumentDto.setOriginalFilename("test.txt");
        txtDocumentDto.setStatus(uk.gegc.quizmaker.model.document.Document.DocumentStatus.PROCESSED);
        txtDocumentDto.setTotalChunks(3);

        when(documentProcessingService.uploadAndProcessDocument(
                eq("testuser"), any(byte[].class), eq("test.txt"), any(ProcessDocumentRequest.class)))
                .thenReturn(txtDocumentDto);

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(txtFile)
                        .param("maxChunkSize", "2000")
                        .param("chunkingStrategy", "SIZE_BASED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(txtDocumentDto.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("test.txt"))
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.totalChunks").value(3));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_PdfWithChapters_Success() throws Exception {
        // Arrange
        MockMultipartFile pdfFile = new MockMultipartFile(
                "file",
                "book.pdf",
                "application/pdf",
                createPdfWithChapters()
        );

        DocumentDto bookDocumentDto = new DocumentDto();
        bookDocumentDto.setId(UUID.randomUUID());
        bookDocumentDto.setOriginalFilename("book.pdf");
        bookDocumentDto.setStatus(uk.gegc.quizmaker.model.document.Document.DocumentStatus.PROCESSED);
        bookDocumentDto.setTotalChunks(10);
        bookDocumentDto.setTitle("Sample Book");
        bookDocumentDto.setAuthor("Test Author");

        when(documentProcessingService.uploadAndProcessDocument(
                eq("testuser"), any(byte[].class), eq("book.pdf"), any(ProcessDocumentRequest.class)))
                .thenReturn(bookDocumentDto);

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(pdfFile)
                        .param("maxChunkSize", "4000")
                        .param("chunkingStrategy", "CHAPTER_BASED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(bookDocumentDto.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("book.pdf"))
                .andExpect(jsonPath("$.title").value("Sample Book"))
                .andExpect(jsonPath("$.author").value("Test Author"))
                .andExpect(jsonPath("$.totalChunks").value(10));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_TxtWithSections_Success() throws Exception {
        // Arrange
        MockMultipartFile txtFile = new MockMultipartFile(
                "file",
                "article.txt",
                "text/plain",
                createTxtWithSections()
        );

        DocumentDto articleDocumentDto = new DocumentDto();
        articleDocumentDto.setId(UUID.randomUUID());
        articleDocumentDto.setOriginalFilename("article.txt");
        articleDocumentDto.setStatus(uk.gegc.quizmaker.model.document.Document.DocumentStatus.PROCESSED);
        articleDocumentDto.setTotalChunks(6);

        when(documentProcessingService.uploadAndProcessDocument(
                eq("testuser"), any(byte[].class), eq("article.txt"), any(ProcessDocumentRequest.class)))
                .thenReturn(articleDocumentDto);

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(txtFile)
                        .param("maxChunkSize", "1500")
                        .param("chunkingStrategy", "SECTION_BASED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(articleDocumentDto.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("article.txt"))
                .andExpect(jsonPath("$.totalChunks").value(6));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_LargePdfFile_Success() throws Exception {
        // Arrange
        MockMultipartFile largePdfFile = new MockMultipartFile(
                "file",
                "large_document.pdf",
                "application/pdf",
                createLargePdfContent()
        );

        DocumentDto largeDocumentDto = new DocumentDto();
        largeDocumentDto.setId(UUID.randomUUID());
        largeDocumentDto.setOriginalFilename("large_document.pdf");
        largeDocumentDto.setStatus(uk.gegc.quizmaker.model.document.Document.DocumentStatus.PROCESSED);
        largeDocumentDto.setTotalChunks(25);
        largeDocumentDto.setFileSize(5L * 1024 * 1024); // 5MB

        when(documentProcessingService.uploadAndProcessDocument(
                eq("testuser"), any(byte[].class), eq("large_document.pdf"), any(ProcessDocumentRequest.class)))
                .thenReturn(largeDocumentDto);

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(largePdfFile)
                        .param("maxChunkSize", "2000")
                        .param("chunkingStrategy", "SIZE_BASED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(largeDocumentDto.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("large_document.pdf"))
                .andExpect(jsonPath("$.fileSize").value(5L * 1024 * 1024))
                .andExpect(jsonPath("$.totalChunks").value(25));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_PdfWithSpecialCharacters_Success() throws Exception {
        // Arrange
        MockMultipartFile pdfFile = new MockMultipartFile(
                "file",
                "special-chars-文档.pdf",
                "application/pdf",
                createPdfWithSpecialCharacters()
        );

        DocumentDto specialDocumentDto = new DocumentDto();
        specialDocumentDto.setId(UUID.randomUUID());
        specialDocumentDto.setOriginalFilename("special-chars-文档.pdf");
        specialDocumentDto.setStatus(uk.gegc.quizmaker.model.document.Document.DocumentStatus.PROCESSED);
        specialDocumentDto.setTotalChunks(4);

        when(documentProcessingService.uploadAndProcessDocument(
                eq("testuser"), any(byte[].class), eq("special-chars-文档.pdf"), any(ProcessDocumentRequest.class)))
                .thenReturn(specialDocumentDto);

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(pdfFile)
                        .param("maxChunkSize", "3000")
                        .param("chunkingStrategy", "CHAPTER_BASED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(specialDocumentDto.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("special-chars-文档.pdf"))
                .andExpect(jsonPath("$.totalChunks").value(4));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_TxtWithUnicode_Success() throws Exception {
        // Arrange
        MockMultipartFile txtFile = new MockMultipartFile(
                "file",
                "unicode-测试.txt",
                "text/plain",
                createTxtWithUnicode()
        );

        DocumentDto unicodeDocumentDto = new DocumentDto();
        unicodeDocumentDto.setId(UUID.randomUUID());
        unicodeDocumentDto.setOriginalFilename("unicode-测试.txt");
        unicodeDocumentDto.setStatus(uk.gegc.quizmaker.model.document.Document.DocumentStatus.PROCESSED);
        unicodeDocumentDto.setTotalChunks(2);

        when(documentProcessingService.uploadAndProcessDocument(
                eq("testuser"), any(byte[].class), eq("unicode-测试.txt"), any(ProcessDocumentRequest.class)))
                .thenReturn(unicodeDocumentDto);

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(txtFile)
                        .param("maxChunkSize", "1000")
                        .param("chunkingStrategy", "SIZE_BASED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(unicodeDocumentDto.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("unicode-测试.txt"))
                .andExpect(jsonPath("$.totalChunks").value(2));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_PdfWithMixedContent_Success() throws Exception {
        // Arrange
        MockMultipartFile pdfFile = new MockMultipartFile(
                "file",
                "mixed-content.pdf",
                "application/pdf",
                createPdfWithMixedContent()
        );

        DocumentDto mixedDocumentDto = new DocumentDto();
        mixedDocumentDto.setId(UUID.randomUUID());
        mixedDocumentDto.setOriginalFilename("mixed-content.pdf");
        mixedDocumentDto.setStatus(uk.gegc.quizmaker.model.document.Document.DocumentStatus.PROCESSED);
        mixedDocumentDto.setTotalChunks(8);

        when(documentProcessingService.uploadAndProcessDocument(
                eq("testuser"), any(byte[].class), eq("mixed-content.pdf"), any(ProcessDocumentRequest.class)))
                .thenReturn(mixedDocumentDto);

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(pdfFile)
                        .param("maxChunkSize", "2500")
                        .param("chunkingStrategy", "AUTO"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(mixedDocumentDto.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("mixed-content.pdf"))
                .andExpect(jsonPath("$.totalChunks").value(8));
    }

    private byte[] createPdfContent() {
        return "PDF content for testing".getBytes();
    }

    private byte[] createTxtContent() {
        return "This is a simple text file for testing.".getBytes();
    }

    private byte[] createPdfWithChapters() {
        String content = """
            Chapter 1: Introduction
            This is the introduction chapter.
            
            Chapter 2: Main Content
            This is the main content chapter.
            
            Chapter 3: Conclusion
            This is the conclusion chapter.
            """;
        return content.getBytes();
    }

    private byte[] createTxtWithSections() {
        String content = """
            Section 1.1: Background
            This section provides background information.
            
            Section 1.2: Objectives
            This section outlines the objectives.
            
            Section 2.1: Methodology
            This section describes the methodology.
            """;
        return content.getBytes();
    }

    private byte[] createLargePdfContent() {
        StringBuilder content = new StringBuilder();
        content.append("Large PDF document content. ");
        
        // Create a large document with repeated content
        for (int i = 0; i < 1000; i++) {
            content.append("This is paragraph ").append(i).append(" of the large document. ");
            content.append("It contains multiple sentences and should be processed correctly. ");
            content.append("The content is designed to test large file handling. ");
        }
        
        return content.toString().getBytes();
    }

    private byte[] createPdfWithSpecialCharacters() {
        String content = """
            Chapter 1: Special Characters
            This document contains special characters: é, ñ, ü, ©, ®, ™, €, £, ¥, ¢.
            
            Chapter 2: Mathematical Symbols
            Mathematical symbols: α, β, γ, δ, ε, π, Σ, ∫, ∞, ±.
            
            Chapter 3: Currency Symbols
            Currency symbols: $, €, £, ¥, ¢, ₽, ₹, ₩.
            """;
        return content.getBytes();
    }

    private byte[] createTxtWithUnicode() {
        String content = """
            Chapter 1: Unicode Test
            This document contains Unicode characters: α, β, γ, δ, ε.
            
            Chapter 2: Chinese Characters
            Chinese characters: 你好世界，这是一个测试文档。
            
            Chapter 3: Japanese Characters
            Japanese characters: こんにちは世界、これはテスト文書です。
            
            Chapter 4: Korean Characters
            Korean characters: 안녕하세요 세계, 이것은 테스트 문서입니다.
            """;
        return content.getBytes();
    }

    private byte[] createPdfWithMixedContent() {
        String content = """
            Chapter 1: Text Content
            This chapter contains plain text content.
            
            Chapter 2: Mixed Content
            This chapter contains:
            - Bullet points
            - Numbered lists
            - Tables and data
            - Mathematical formulas: E = mc²
            - Special characters: ©, ®, ™
            
            Chapter 3: Complex Structure
            This chapter has:
            1.1 Subsection A
            1.2 Subsection B
            2.1 Another subsection
            2.2 Final subsection
            """;
        return content.getBytes();
    }
} 