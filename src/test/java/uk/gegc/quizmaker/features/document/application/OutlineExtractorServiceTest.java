package uk.gegc.quizmaker.features.document.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import uk.gegc.quizmaker.features.document.api.dto.DocumentOutlineDto;
import uk.gegc.quizmaker.features.document.api.dto.OutlineNodeDto;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutlineExtractorServiceTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatResponse chatResponse;

    @Mock
    private ChatClient.ChatClientRequestSpec chatClientRequestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    private Generation generation;

    private OutlineExtractorService outlineExtractorService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outlineExtractorService = new OutlineExtractorService(chatClient, objectMapper);
    }

    @Test
    void extractOutline_Success() {
        // Given
        String documentContent = """
            Chapter 1: Introduction
            This chapter introduces the main concepts.
            
            Section 1.1: Background
            The background provides context for the study.
            
            Section 1.2: Objectives
            The objectives outline the goals of this work.
            
            Chapter 2: Literature Review
            This chapter reviews existing literature.
            """;

        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Chapter 1: Introduction",
                  "start_anchor": "Chapter 1: Introduction",
                  "end_anchor": "goals of this work",
                  "children": [
                    {
                      "type": "SECTION",
                      "title": "Section 1.1: Background",
                      "start_anchor": "Section 1.1: Background",
                      "end_anchor": "context for the study",
                      "children": []
                    },
                    {
                      "type": "SECTION",
                      "title": "Section 1.2: Objectives",
                      "start_anchor": "Section 1.2: Objectives",
                      "end_anchor": "goals of this work",
                      "children": []
                    }
                  ]
                },
                {
                  "type": "CHAPTER",
                  "title": "Chapter 2: Literature Review",
                  "start_anchor": "Chapter 2: Literature Review",
                  "end_anchor": "existing literature",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(documentContent);

        // Then
        assertNotNull(result);
        assertNotNull(result.nodes());
        assertEquals(2, result.nodes().size());

        // Verify first chapter
        OutlineNodeDto firstChapter = result.nodes().get(0);
        assertEquals("CHAPTER", firstChapter.type());
        assertEquals("Chapter 1: Introduction", firstChapter.title());
        assertEquals("Chapter 1: Introduction", firstChapter.startAnchor());
        assertEquals("goals of this work", firstChapter.endAnchor());
        assertEquals(2, firstChapter.children().size());

        // Verify first section
        OutlineNodeDto firstSection = firstChapter.children().get(0);
        assertEquals("SECTION", firstSection.type());
        assertEquals("Section 1.1: Background", firstSection.title());
        assertEquals("Section 1.1: Background", firstSection.startAnchor());
        assertEquals("context for the study", firstSection.endAnchor());
        assertTrue(firstSection.children().isEmpty());
    }

    @Test
    void extractOutline_WithMarkdownFormatting() {
        // Given
        String documentContent = "Test Chapter content with end of chapter";
        String aiResponse = """
            ```json
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "Test Chapter",
                  "end_anchor": "end of chapter",
                  "children": []
                }
              ]
            }
            ```
            """;

        setupMockResponse(aiResponse);

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(documentContent);

        // Then
        assertNotNull(result);
        assertEquals(1, result.nodes().size());
        assertEquals("CHAPTER", result.nodes().get(0).type());
    }

    @Test
    void extractOutline_WithArrayResponse() {
        // Given
        String documentContent = "Test Chapter content with end of chapter";
        String aiResponse = """
            [
              {
                "type": "CHAPTER",
                "title": "Test Chapter",
                "start_anchor": "Test Chapter",
                "end_anchor": "end of chapter",
                "children": []
              }
            ]
            """;

        setupMockResponse(aiResponse);

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(documentContent);

        // Then
        assertNotNull(result);
        assertEquals(1, result.nodes().size());
        assertEquals("CHAPTER", result.nodes().get(0).type());
    }

    @Test
    void extractOutline_WithSingleNodeResponse() {
        // Given
        String documentContent = "Test Chapter content with end of chapter";
        String aiResponse = """
            {
              "type": "CHAPTER",
              "title": "Test Chapter",
              "start_anchor": "Test Chapter",
              "end_anchor": "end of chapter",
              "children": []
            }
            """;

        setupMockResponse(aiResponse);

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(documentContent);

        // Then
        assertNotNull(result);
        assertEquals(1, result.nodes().size());
        assertEquals("CHAPTER", result.nodes().get(0).type());
    }

    @Test
    void extractOutline_WithWrapperPattern() {
        // Given
        String documentContent = "Test Chapter content with end of chapter";
        String aiResponse = """
            {
              "outline": {
                "nodes": [
                  {
                    "type": "CHAPTER",
                    "title": "Test Chapter",
                    "start_anchor": "Test Chapter",
                    "end_anchor": "end of chapter",
                    "children": []
                  }
                ]
              }
            }
            """;

        setupMockResponse(aiResponse);

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(documentContent);

        // Then
        assertNotNull(result);
        assertEquals(1, result.nodes().size());
        assertEquals("CHAPTER", result.nodes().get(0).type());
    }

    @Test
    void extractOutline_WithQuotesInAnchors() {
        // Given
        String documentContent = "Test Chapter with \"quotes\" content and end of chapter with \"escaped\" quotes";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "Test Chapter with \\"quotes\\"",
                  "end_anchor": "end of chapter with \\"escaped\\" quotes",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(documentContent);

        // Then
        assertNotNull(result);
        assertEquals(1, result.nodes().size());
        assertEquals("Test Chapter with \"quotes\"", result.nodes().get(0).startAnchor());
        assertEquals("end of chapter with \"escaped\" quotes", result.nodes().get(0).endAnchor());
    }

    @Test
    void extractOutline_CaseInsensitiveAnchors_Success() {
        // Given - Test case-insensitive anchor search
        String documentContent = "This is a TEST document with CONTENT.";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "this is",
                  "end_anchor": "test document",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(documentContent);

        // Then - Should succeed with case-insensitive matching
        assertNotNull(result);
        assertEquals(1, result.nodes().size());
        assertEquals("CHAPTER", result.nodes().get(0).type());
        assertEquals("this is", result.nodes().get(0).startAnchor());
        assertEquals("test document", result.nodes().get(0).endAnchor());
    }

    @Test
    void extractOutline_EmptyContent_ThrowsException() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            outlineExtractorService.extractOutline("");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            outlineExtractorService.extractOutline(null);
        });
    }

    @Test
    void extractOutline_InvalidJson_ThrowsException() {
        // Given
        String documentContent = "Test content";
        String aiResponse = "Invalid JSON response";

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_MissingNodes_ThrowsException() {
        // Given
        String documentContent = "Test content";
        String aiResponse = "{\"invalid\": \"structure\"}";

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_EmptyNodes_ThrowsException() {
        // Given
        String documentContent = "Test content";
        String aiResponse = "{\"nodes\": []}";

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_MissingRequiredFields_ThrowsException() {
        // Given
        String documentContent = "Test content";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "Test Chapter",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_MissingTitle_ThrowsException() {
        // Given
        String documentContent = "Test content";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "start_anchor": "Test Chapter",
                  "end_anchor": "end of chapter",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_MissingEndAnchor_ThrowsException() {
        // Given
        String documentContent = "Test content";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "Test Chapter",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_InvalidNodeType_ThrowsException() {
        // Given
        String documentContent = "Test content";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "INVALID_TYPE",
                  "title": "Test Chapter",
                  "start_anchor": "Test Chapter",
                  "end_anchor": "end of chapter",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_AnchorTooShort_ThrowsException() {
        // Given
        String documentContent = "This is a test document with content.";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "A",
                  "end_anchor": "This is a test document with content",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_AnchorTooShort_ThrowsException2() {
        // Given - Test with 1 word anchor (should fail with 2-15 word requirement)
        String documentContent = "This is a test document with content.";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "This",
                  "end_anchor": "This is a test document with content",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_AnchorTooLong_ThrowsException() {
        // Given
        String documentContent = "This is a test document with content.";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "This is a test document with content that is way too long for an anchor and should be rejected",
                  "end_anchor": "This is a test document with content",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_AnchorNotFound_ThrowsException() {
        // Given
        String documentContent = "This is a test document with content.";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "This phrase does not exist in the document",
                  "end_anchor": "This is a test document with content",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_EndAnchorBeforeStart_ThrowsException() {
        // Given
        String documentContent = "This is a test appears first. This is a test document with content.";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "This is a test document",
                  "end_anchor": "This is a test appears first",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_EndAnchorBeforeStartWithRepeatedPhrase_ThrowsException() {
        // Given - Test the improved anchor search that looks for end anchor after start anchor
        String documentContent = "This is a test appears first. This is a test document with content. This is a test appears again.";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "This is a test document",
                  "end_anchor": "This is a test appears first",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When & Then - Should fail because end anchor appears before start anchor in document
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_TooManyChildren_ThrowsException() {
        // Given
        String documentContent = "start content with end";
        StringBuilder aiResponse = new StringBuilder("{\"nodes\": [{\"type\": \"CHAPTER\", \"title\": \"Test\", \"start_anchor\": \"start\", \"end_anchor\": \"end\", \"children\": [");
        
        // Create more than 50 children
        for (int i = 0; i < 51; i++) {
            if (i > 0) aiResponse.append(",");
            aiResponse.append("{\"type\": \"SECTION\", \"title\": \"Section ").append(i).append("\", \"start_anchor\": \"start\", \"end_anchor\": \"end\", \"children\": []}");
        }
        aiResponse.append("]}]}");

        setupMockResponse(aiResponse.toString());

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_TooDeepHierarchy_ThrowsException() {
        // Given
        String documentContent = "Part 1 content with end part. Chapter 1 content with end chapter. Section 1 content with end section. Subsection 1 content with end subsection. Paragraph 1 content with end paragraph. Too Deep content with end deep.";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "PART",
                  "title": "Part 1",
                  "start_anchor": "Part 1",
                  "end_anchor": "end part",
                  "children": [
                    {
                      "type": "CHAPTER",
                      "title": "Chapter 1",
                      "start_anchor": "Chapter 1",
                      "end_anchor": "end chapter",
                      "children": [
                        {
                          "type": "SECTION",
                          "title": "Section 1",
                          "start_anchor": "Section 1",
                          "end_anchor": "end section",
                          "children": [
                            {
                              "type": "SUBSECTION",
                              "title": "Subsection 1",
                              "start_anchor": "Subsection 1",
                              "end_anchor": "end subsection",
                              "children": [
                                {
                                  "type": "PARAGRAPH",
                                  "title": "Paragraph 1",
                                  "start_anchor": "Paragraph 1",
                                  "end_anchor": "end paragraph",
                                  "children": [
                                    {
                                      "type": "OTHER",
                                      "title": "Too Deep",
                                      "start_anchor": "Too Deep",
                                      "end_anchor": "end deep",
                                      "children": []
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_MaxDepthAllowed_Success() {
        // Given - Test that exactly 4 levels (depth 0-3) is allowed
        String documentContent = "Part 1 content with end part. Chapter 1 content with end chapter. Section 1 content with end section. Subsection 1 content with end subsection.";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "PART",
                  "title": "Part 1",
                  "start_anchor": "Part 1",
                  "end_anchor": "end part",
                  "children": [
                    {
                      "type": "CHAPTER",
                      "title": "Chapter 1",
                      "start_anchor": "Chapter 1",
                      "end_anchor": "end chapter",
                      "children": [
                        {
                          "type": "SECTION",
                          "title": "Section 1",
                          "start_anchor": "Section 1",
                          "end_anchor": "end section",
                          "children": [
                            {
                              "type": "SUBSECTION",
                              "title": "Subsection 1",
                              "start_anchor": "Subsection 1",
                              "end_anchor": "end subsection",
                              "children": []
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(documentContent);

        // Then - Should succeed with exactly 4 levels
        assertNotNull(result);
        assertEquals(1, result.nodes().size());
        assertEquals("PART", result.nodes().get(0).type());
    }

    @Test
    void extractOutline_TwoWordAnchors_Success() {
        // Given - Test that 2-word anchors are accepted (new validation rule)
        String documentContent = "This is a test document with content.";
        String aiResponse = """
            {
              "nodes": [
                {
                  "type": "CHAPTER",
                  "title": "Test Chapter",
                  "start_anchor": "This is",
                  "end_anchor": "test document",
                  "children": []
                }
              ]
            }
            """;

        setupMockResponse(aiResponse);

        // When
        DocumentOutlineDto result = outlineExtractorService.extractOutline(documentContent);

        // Then - Should succeed with 2-word anchors
        assertNotNull(result);
        assertEquals(1, result.nodes().size());
        assertEquals("CHAPTER", result.nodes().get(0).type());
        assertEquals("This is", result.nodes().get(0).startAnchor());
        assertEquals("test document", result.nodes().get(0).endAnchor());
    }

    @Test
    void extractOutline_AiServiceFailure_ThrowsException() {
        // Given
        String documentContent = "Test content";
        when(chatClient.prompt()).thenThrow(new RuntimeException("AI service unavailable"));

        // When & Then
        assertThrows(AiServiceException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    @Test
    void extractOutline_EmptyAiResponse_ThrowsException() {
        // Given
        String documentContent = "Test content";
        setupMockResponse("");

        // When & Then
        assertThrows(AiServiceException.class, () -> {
            outlineExtractorService.extractOutline(documentContent);
        });
    }

    private void setupMockResponse(String aiResponse) {
        when(chatClient.prompt()).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.user(any(String.class))).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(new org.springframework.ai.chat.messages.AssistantMessage(aiResponse));
    }
}
