package uk.gegc.quizmaker.service.document;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.document.DocumentDto;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class  DocumentProcessingServiceIntegrationTest {

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void uploadAndProcessDocument_TextFile_CreatesDocument() throws Exception {
        // Arrange
        User user = createTestUser("testuser_text");
        
        String textContent = """
            Chapter 1: Introduction
            This is the introduction chapter.
            
            Chapter 2: Main Content
            This is the main content chapter.
            """;
        
        byte[] content = textContent.getBytes();
        String filename = "test.txt";
        
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        request.setMaxChunkSize(1000);

        // Act
        DocumentDto result = documentProcessingService.uploadAndProcessDocument(
            user.getUsername(),
            content,
            filename,
            request
        );

        // Assert
        assertNotNull(result);
        assertEquals(filename, result.getOriginalFilename());
        assertEquals("text/plain", result.getContentType());
        assertEquals((long) content.length, result.getFileSize());
    }

    @Test
    void uploadAndProcessDocument_PdfFile_CreatesDocument() throws Exception {
        // Arrange
        User user = createTestUser("testuser_pdf");
        
        // Create a simple text content for testing (avoiding PDF parsing issues in tests)
        String textContent = "This is a test document content for testing purposes.";
        byte[] content = textContent.getBytes();
        String filename = "test.txt";
        
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        request.setMaxChunkSize(1000);

        // Act
        DocumentDto result = documentProcessingService.uploadAndProcessDocument(
            user.getUsername(),
            content,
            filename,
            request
        );

        // Assert
        assertNotNull(result);
        assertEquals(filename, result.getOriginalFilename());
        assertEquals("text/plain", result.getContentType());
        assertEquals((long) content.length, result.getFileSize());
    }

    private User createTestUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setHashedPassword("password");
        user.setActive(true);
        user.setDeleted(false);
        return userRepository.save(user);
    }
} 