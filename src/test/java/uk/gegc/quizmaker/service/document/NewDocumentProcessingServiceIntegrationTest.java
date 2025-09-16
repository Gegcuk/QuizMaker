package uk.gegc.quizmaker.service.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.document.application.impl.DocumentProcessingServiceImpl;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentChunkRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class NewDocumentProcessingServiceIntegrationTest {

    @Autowired
    private DocumentProcessingServiceImpl documentProcessingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository chunkRepository;

    private byte[] testPdfContent;
    private String testFilename;
    private ProcessDocumentRequest testRequest;
    private String testUsername;

    @BeforeEach
    void setUp() {
        // Clean up in the correct order to avoid foreign key constraint violations
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        // Create a test user with unique username to avoid concurrency issues
        User testUser = new User();
        testUser.setUsername("testuser_" + System.currentTimeMillis());
        testUser.setEmail("testuser@email.com");
        testUser.setHashedPassword("testPassword");
        testUser.setActive(true);
        testUser.setDeleted(false);
        User savedUser = userRepository.save(testUser);

        // Update the username used in tests to match the saved user
        testUsername = savedUser.getUsername();

        // Create a simple test PDF content
        testPdfContent = createSimplePdfContent();
        testFilename = "test.pdf";

        testRequest = new ProcessDocumentRequest();
        testRequest.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        testRequest.setMaxChunkSize(4000);
        testRequest.setStoreChunks(true);
    }

    @Test
    void uploadAndProcessDocument_Success() {
        // Act
        DocumentDto result = documentProcessingService.uploadAndProcessDocument(
                testUsername, testPdfContent, testFilename, testRequest
        );

        // Assert
        assertNotNull(result);
        assertEquals(testFilename, result.getOriginalFilename());
        assertEquals("application/pdf", result.getContentType());
        assertNotNull(result.getId());
        assertNotNull(result.getUploadedAt());
    }

    @Test
    void uploadAndProcessDocument_InvalidContent_ThrowsException() {
        // Arrange - Create content that definitely won't be a valid PDF
        byte[] invalidContent = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05}; // Random bytes

        // Act & Assert
        assertThrows(Exception.class, () -> {
            documentProcessingService.uploadAndProcessDocument(
                    testUsername, invalidContent, testFilename, testRequest
            );
        });
    }

    /**
     * Creates a minimal valid PDF content for testing
     */
    private byte[] createSimplePdfContent() {
        // This is a minimal PDF content that PDFBox can parse
        String pdfContent = "%PDF-1.4\n" +
                "1 0 obj\n" +
                "<<\n" +
                "/Type /Catalog\n" +
                "/Pages 2 0 R\n" +
                ">>\n" +
                "endobj\n" +
                "2 0 obj\n" +
                "<<\n" +
                "/Type /Pages\n" +
                "/Kids [3 0 R]\n" +
                "/Count 1\n" +
                ">>\n" +
                "endobj\n" +
                "3 0 obj\n" +
                "<<\n" +
                "/Type /Page\n" +
                "/Parent 2 0 R\n" +
                "/MediaBox [0 0 612 792]\n" +
                "/Contents 4 0 R\n" +
                ">>\n" +
                "endobj\n" +
                "4 0 obj\n" +
                "<<\n" +
                "/Length 44\n" +
                ">>\n" +
                "stream\n" +
                "BT\n" +
                "/F1 12 Tf\n" +
                "72 720 Td\n" +
                "(Hello World) Tj\n" +
                "ET\n" +
                "endstream\n" +
                "endobj\n" +
                "xref\n" +
                "0 5\n" +
                "0000000000 65535 f \n" +
                "0000000009 00000 n \n" +
                "0000000058 00000 n \n" +
                "0000000115 00000 n \n" +
                "0000000204 00000 n \n" +
                "trailer\n" +
                "<<\n" +
                "/Size 5\n" +
                "/Root 1 0 R\n" +
                ">>\n" +
                "startxref\n" +
                "364\n" +
                "%%EOF\n";

        return pdfContent.getBytes();
    }
} 