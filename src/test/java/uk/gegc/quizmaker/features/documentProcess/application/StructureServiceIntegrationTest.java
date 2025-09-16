package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gegc.quizmaker.BaseIntegrationTest;

import java.util.concurrent.atomic.AtomicInteger;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class StructureServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private StructureService structureService;

    @Autowired
    private DocumentNodeRepository nodeRepository;

    @Autowired
    private NormalizedDocumentRepository documentRepository;

    @MockitoBean
    private LlmClient llmClient;

    private UUID documentId;
    private NormalizedDocument document;

    @BeforeEach
    void setUp() {
        // Clean up any existing data
        nodeRepository.deleteAll();
        documentRepository.deleteAll();
        
        document = new NormalizedDocument();
        document.setOriginalName("integration-test.txt");
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document.setNormalizedText(createTestDocumentText());
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setCharCount(createTestDocumentText().length());
        
        document = documentRepository.save(document);
        documentId = document.getId();
        
        // Set up LlmClient mock to return test nodes based on document text
        when(llmClient.generateStructure(anyString(), any(LlmClient.StructureOptions.class)))
            .thenAnswer(invocation -> {
                String documentText = invocation.getArgument(0);
                return createTestNodesForDocument(documentText);
            });
    }

    @Test
    @DisplayName("Should build structure with real database persistence")
    void shouldBuildStructureWithRealDatabasePersistence() {
        // Given - Document is already saved in setUp()
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
        assertThat(savedNodes).hasSizeGreaterThan(0);
        
        // Verify document status was updated
        NormalizedDocument updatedDocument = documentRepository.findById(documentId).orElseThrow();
        assertThat(updatedDocument.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.STRUCTURED);
    }

    @Test
    @DisplayName("Should handle document not found in database")
    void shouldHandleDocumentNotFoundInDatabase() {
        // Given
        UUID nonExistentId = UUID.randomUUID();
        
        // When & Then
        assertThatThrownBy(() -> structureService.buildStructure(nonExistentId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Document not found");
    }

    @Test
    @DisplayName("Should handle document in wrong status")
    void shouldHandleDocumentInWrongStatus() {
        // Given
        document.setStatus(NormalizedDocument.DocumentStatus.STRUCTURED);
        documentRepository.save(document);
        
        // When & Then
        assertThatThrownBy(() -> structureService.buildStructure(documentId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Document must be in NORMALIZED status");
    }

    @Test
    @DisplayName("Should persist nodes with correct relationships")
    void shouldPersistNodesWithCorrectRelationships() {
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
        
        // Verify parent-child relationships are established
        DocumentNode rootNode = savedNodes.stream()
            .filter(node -> node.getDepth() == 0)
            .findFirst()
            .orElse(null);
        
        assertThat(rootNode).isNotNull();
        assertThat(rootNode.getParent()).isNull(); // Root should have no parent
        
        // Verify child nodes have parent relationships
        List<DocumentNode> childNodes = savedNodes.stream()
            .filter(node -> node.getDepth() > 0)
            .toList();
        
        for (DocumentNode child : childNodes) {
            assertThat(child.getParent()).isNotNull();
            assertThat(child.getParent().getDepth()).isLessThan(child.getDepth());
        }
    }

    @Test
    @DisplayName("Should handle concurrent access to same document")
    void shouldHandleConcurrentAccessToSameDocument() throws InterruptedException {
        // Given
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        Runnable buildStructureTask = () -> {
            try {
                structureService.buildStructure(documentId);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
                // Expected for concurrent access - one might fail due to race condition
            }
        };
        
        // When - Start multiple threads
        Thread thread1 = new Thread(buildStructureTask);
        Thread thread2 = new Thread(buildStructureTask);
        
        thread1.start();
        thread2.start();
        
        thread1.join();
        thread2.join();
        
        // Then - At least one should succeed OR if both fail, we should have no nodes
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        
        if (successCount.get() > 0) {
            // At least one thread succeeded, so we should have nodes
            assertThat(savedNodes).isNotEmpty();
        } else {
            // Both threads failed, so we should have no nodes
            assertThat(savedNodes).isEmpty();
            assertThat(failureCount.get()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("Should handle large document text")
    void shouldHandleLargeDocumentText() {
        // Given
        String largeText = "A".repeat(50000); // 50KB text
        document.setNormalizedText(largeText);
        document.setCharCount(largeText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
        
        // Verify document status was updated
        NormalizedDocument updatedDocument = documentRepository.findById(documentId).orElseThrow();
        assertThat(updatedDocument.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.STRUCTURED);
    }

    @Test
    @DisplayName("Should handle document with special characters")
    void shouldHandleDocumentWithSpecialCharacters() {
        // Given
        String specialText = "Document with special chars: !@#$%^&*()_+-=[]{}|;':\",./<>? \n\t\r";
        document.setNormalizedText(specialText);
        document.setCharCount(specialText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle document with unicode characters")
    void shouldHandleDocumentWithUnicodeCharacters() {
        // Given
        String unicodeText = "Document with unicode: 你好世界 🌍 🚀 💻 中文测试";
        document.setNormalizedText(unicodeText);
        document.setCharCount(unicodeText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle document with HTML-like content")
    void shouldHandleDocumentWithHtmlLikeContent() {
        // Given
        String htmlText = "<h1>Title</h1><p>This is a paragraph with <strong>bold</strong> and <em>italic</em> text.</p><ul><li>Item 1</li><li>Item 2</li></ul>";
        document.setNormalizedText(htmlText);
        document.setCharCount(htmlText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle document with markdown-like content")
    void shouldHandleDocumentWithMarkdownLikeContent() {
        // Given
        String markdownText = "# Main Title\n\n## Section 1\n\nThis is a paragraph with **bold** and *italic* text.\n\n### Subsection\n\n- List item 1\n- List item 2\n\n```java\npublic class Test {\n    // code here\n}\n```";
        document.setNormalizedText(markdownText);
        document.setCharCount(markdownText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle document with code blocks")
    void shouldHandleDocumentWithCodeBlocks() {
        // Given
        String codeText = "Here is some Java code:\n\n```java\npublic class Example {\n    private String name;\n    \n    public Example(String name) {\n        this.name = name;\n    }\n    \n    public String getName() {\n        return name;\n    }\n}\n```\n\nAnd some SQL:\n\n```sql\nSELECT * FROM users WHERE active = true;\n```";
        document.setNormalizedText(codeText);
        document.setCharCount(codeText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle document with tables")
    void shouldHandleDocumentWithTables() {
        // Given
        String tableText = "Here is a table:\n\n| Column 1 | Column 2 | Column 3 |\n|----------|----------|----------|\n| Data 1   | Data 2   | Data 3   |\n| Data 4   | Data 5   | Data 6   |\n\nAnd another table:\n\nName | Age | City\n-----|-----|-----\nJohn | 25  | NYC\nJane | 30  | LA";
        document.setNormalizedText(tableText);
        document.setCharCount(tableText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle document with mathematical expressions")
    void shouldHandleDocumentWithMathematicalExpressions() {
        // Given
        String mathText = "Here are some mathematical expressions:\n\n1. E = mc²\n2. ∫ f(x) dx\n3. ∑(i=1 to n) x_i\n4. (a + b)² = a² + 2ab + b²\n5. π ≈ 3.14159\n\nAnd some equations:\n\n```\ny = mx + b\nx = (-b ± √(b² - 4ac)) / 2a\n```";
        document.setNormalizedText(mathText);
        document.setCharCount(mathText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle document with URLs and links")
    void shouldHandleDocumentWithUrlsAndLinks() {
        // Given
        String urlText = "Here are some links:\n\n1. https://www.example.com\n2. http://localhost:8080/api/v1/users\n3. ftp://files.example.com/download\n4. mailto:user@example.com\n\nAnd some markdown links:\n\n[Google](https://www.google.com)\n[GitHub](https://github.com)\n[Stack Overflow](https://stackoverflow.com)";
        document.setNormalizedText(urlText);
        document.setCharCount(urlText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle document with email addresses")
    void shouldHandleDocumentWithEmailAddresses() {
        // Given
        String emailText = "Contact information:\n\n- Primary: john.doe@example.com\n- Secondary: jane.smith@company.org\n- Support: help@service.net\n- Admin: admin@domain.co.uk\n\nEmail patterns:\n\nuser+tag@domain.com\nuser.name@subdomain.domain.com";
        document.setNormalizedText(emailText);
        document.setCharCount(emailText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle document with phone numbers")
    void shouldHandleDocumentWithPhoneNumbers() {
        // Given
        String phoneText = "Contact numbers:\n\n- US: +1-555-123-4567\n- UK: +44 20 7946 0958\n- International: +33 1 42 86 20 00\n- Local: (555) 123-4567\n- Extension: 555-123-4567 ext. 123\n\nEmergency: 911 (US) / 999 (UK)";
        document.setNormalizedText(phoneText);
        document.setCharCount(phoneText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle document with dates and times")
    void shouldHandleDocumentWithDatesAndTimes() {
        // Given
        String dateText = "Important dates:\n\n- Meeting: 2024-01-15 at 14:30\n- Deadline: 2024/02/28 23:59:59\n- Event: January 15, 2024\n- Time: 3:30 PM EST\n- ISO: 2024-01-15T14:30:00Z\n- Custom: 15-Jan-2024";
        document.setNormalizedText(dateText);
        document.setCharCount(dateText.length());
        documentRepository.save(document);
        
        // When
        structureService.buildStructure(documentId);
        
        // Then
        List<DocumentNode> savedNodes = nodeRepository.findByDocument_IdOrderByStartOffset(documentId);
        assertThat(savedNodes).isNotEmpty();
    }

    private String createTestDocumentText() {
        return "This is a comprehensive test document for integration testing. " +
               "It contains multiple sections and subsections with various content types. " +
               "The document is structured in a hierarchical manner to test the system thoroughly. " +
               "Each level has specific content and structure to validate the integration. " +
               "This includes different types of content like paragraphs, lists, and structured data. " +
               "The system should be able to process this document and create a proper structure. " +
               "Testing various scenarios helps ensure robustness and reliability.";
    }

    private List<DocumentNode> createTestNodes() {
        return createTestNodesForDocument(createTestDocumentText());
    }
    
    private List<DocumentNode> createTestNodesForDocument(String documentText) {
        List<DocumentNode> nodes = new ArrayList<>();
        
        // Create root node with anchors that exist in the document
        DocumentNode rootNode = new DocumentNode();
        rootNode.setId(UUID.randomUUID());
        rootNode.setTitle("Test Document");
        rootNode.setType(DocumentNode.NodeType.CHAPTER);
        rootNode.setDepth((short) 0);
        
        // Find appropriate anchors in the document text (at least 20 characters)
        String startAnchor = findStartAnchor(documentText);
        String endAnchor = findEndAnchor(documentText);
        
        rootNode.setStartAnchor(startAnchor);
        rootNode.setEndAnchor(endAnchor);
        rootNode.setAiConfidence(BigDecimal.valueOf(0.9));
        nodes.add(rootNode);
        
        return nodes;
    }
    
    private String findStartAnchor(String documentText) {
        // Try to find a good starting anchor (at least 20 characters)
        if (documentText.contains("This is a comprehensive test document")) {
            return "This is a comprehensive test document";
        } else if (documentText.contains("Contact information")) {
            return "Contact information";
        } else if (documentText.contains("Contact numbers")) {
            return "Contact numbers";
        } else if (documentText.contains("Important dates")) {
            return "Important dates";
        } else if (documentText.contains("Document with unicode")) {
            return "Document with unicode";
        } else if (documentText.contains("Document with special")) {
            return "Document with special";
        } else if (documentText.contains("Document with code")) {
            return "Document with code";
        } else if (documentText.contains("Document with HTML")) {
            return "Document with HTML";
        } else if (documentText.contains("Document with Markdown")) {
            return "Document with Markdown";
        } else if (documentText.contains("Document with URLs")) {
            return "Document with URLs";
        } else if (documentText.contains("Document with tables")) {
            return "Document with tables";
        } else if (documentText.contains("Document with mathematical")) {
            return "Document with mathematical";
        } else {
            // Fallback: use first 50 characters
            return documentText.substring(0, Math.min(50, documentText.length()));
        }
    }
    
    private String findEndAnchor(String documentText) {
        // Try to find a good ending anchor (at least 20 characters)
        if (documentText.contains("robustness and reliability")) {
            return "robustness and reliability";
        } else if (documentText.contains("admin@domain.co.uk")) {
            return "admin@domain.co.uk";
        } else if (documentText.contains("999 (UK)")) {
            return "999 (UK)";
        } else if (documentText.contains("15-Jan-2024")) {
            return "15-Jan-2024";
        } else if (documentText.contains("ф╕нцЦЗц╡ЛшпХ")) {
            return "ф╕нцЦЗц╡ЛшпХ";
        } else if (documentText.contains("special characters")) {
            return "special characters";
        } else if (documentText.contains("```")) {
            return "```";
        } else if (documentText.contains("</html>")) {
            return "</html>";
        } else if (documentText.contains("## Section")) {
            return "## Section";
        } else if (documentText.contains("https://example.com")) {
            return "https://example.com";
        } else if (documentText.contains("| Column 3 |")) {
            return "| Column 3 |";
        } else if (documentText.contains("= 15")) {
            return "= 15";
        } else {
            // Fallback: use last 50 characters, but ensure it doesn't exceed document length
            int start = Math.max(0, documentText.length() - 50);
            return documentText.substring(start);
        }
    }
    

}
