package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for DocumentStructurePromptService covering all methods and edge cases.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Document Structure Prompt Service Tests")
class DocumentStructurePromptServiceTest {

    @Mock
    private ResourceLoader resourceLoader;

    @Mock
    private Resource resource;

    @InjectMocks
    private DocumentStructurePromptService promptService;

    private LlmClient.StructureOptions defaultOptions;

    @BeforeEach
    void setUp() {
        defaultOptions = LlmClient.StructureOptions.defaultOptions();
    }

    @Nested
    @DisplayName("buildStructurePrompt (simple version) Tests")
    class BuildSimplePromptTests {

        @Test
        @DisplayName("buildStructurePrompt: when called with content and options then delegates to full version")
        void buildStructurePrompt_withContentAndOptions_delegatesToFullVersion() throws IOException {
            // Given
            String content = "Sample document content";
            String templateContent = "Template with {content} and {profile} and {granularity}";
            
            when(resourceLoader.getResource(anyString())).thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions);

            // Then
            assertThat(result).isNotNull();
            assertThat(result).contains(content);
            assertThat(result).contains(defaultOptions.profile());
            assertThat(result).contains(defaultOptions.granularity());
        }
    }

    @Nested
    @DisplayName("buildStructurePrompt (with context) Tests")
    class BuildContextPromptTests {

        @Test
        @DisplayName("buildStructurePrompt: when no previous nodes then uses standard template")
        void buildStructurePrompt_noPreviousNodes_usesStandardTemplate() throws IOException {
            // Given
            String content = "Document content to analyze";
            String templateContent = "System prompt with {content}, {profile}, {granularity}, {charCount}, {chunkIndex}, {totalChunks}, {previousStructure}";
            
            when(resourceLoader.getResource("classpath:prompts/document-structure/system-prompt.txt"))
                    .thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, null, 0, 1);

            // Then
            assertThat(result).contains(content);
            assertThat(result).contains(defaultOptions.profile());
            assertThat(result).contains(defaultOptions.granularity());
            assertThat(result).contains(String.valueOf(content.length()));
            assertThat(result).contains("1"); // chunkIndex + 1
            assertThat(result).contains("None (first chunk)");
            verify(resourceLoader).getResource("classpath:prompts/document-structure/system-prompt.txt");
        }

        @Test
        @DisplayName("buildStructurePrompt: when empty previous nodes list then uses standard template")
        void buildStructurePrompt_emptyPreviousNodes_usesStandardTemplate() throws IOException {
            // Given
            String content = "Document content";
            String templateContent = "Template {content} {previousStructure}";
            
            when(resourceLoader.getResource("classpath:prompts/document-structure/system-prompt.txt"))
                    .thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, List.of(), 0, 1);

            // Then
            assertThat(result).contains("None (first chunk)");
            verify(resourceLoader).getResource("classpath:prompts/document-structure/system-prompt.txt");
        }

        @Test
        @DisplayName("buildStructurePrompt: when has previous nodes (< 10) then uses chunked template and includes all nodes")
        void buildStructurePrompt_fewPreviousNodes_usesChunkedTemplateWithAllNodes() throws IOException {
            // Given
            String content = "Chunk 2 content";
            String templateContent = "Chunked template {content} {previousStructure}";
            List<DocumentNode> previousNodes = createDocumentNodes(5);
            
            when(resourceLoader.getResource("classpath:prompts/document-structure/system-prompt-chunked.txt"))
                    .thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, previousNodes, 1, 3);

            // Then
            assertThat(result).contains(content);
            assertThat(result).contains("Node 0"); // First node
            assertThat(result).contains("Node 4"); // Last node
            assertThat(result).contains("depth: 0");
            assertThat(result).doesNotContain("more nodes"); // Should not show "more nodes" message
            verify(resourceLoader).getResource("classpath:prompts/document-structure/system-prompt-chunked.txt");
        }

        @Test
        @DisplayName("buildStructurePrompt: when has previous nodes (> 10) then shows only last 10 with count")
        void buildStructurePrompt_manyPreviousNodes_showsLast10WithCount() throws IOException {
            // Given
            String content = "Large chunk content";
            String templateContent = "Template {content} {previousStructure}";
            List<DocumentNode> previousNodes = createDocumentNodes(15);
            
            when(resourceLoader.getResource("classpath:prompts/document-structure/system-prompt-chunked.txt"))
                    .thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, previousNodes, 2, 5);

            // Then
            assertThat(result).contains(content);
            assertThat(result).contains("Node 5"); // First of last 10
            assertThat(result).contains("Node 14"); // Last node
            assertThat(result).contains("(5... and"); // Shows count of excluded nodes
            assertThat(result).contains(" more nodes)"); // Shows "more nodes" message
            assertThat(result).doesNotContain("Node 0"); // Should not include first nodes
            assertThat(result).doesNotContain("Node 4"); // Should not include early nodes
            verify(resourceLoader).getResource("classpath:prompts/document-structure/system-prompt-chunked.txt");
        }

        @Test
        @DisplayName("buildStructurePrompt: when exactly 10 previous nodes then shows all without count")
        void buildStructurePrompt_exactly10PreviousNodes_showsAllWithoutCount() throws IOException {
            // Given
            String content = "Content";
            String templateContent = "Template {content} {previousStructure}";
            List<DocumentNode> previousNodes = createDocumentNodes(10);
            
            when(resourceLoader.getResource("classpath:prompts/document-structure/system-prompt-chunked.txt"))
                    .thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, previousNodes, 1, 2);

            // Then
            assertThat(result).contains("Node 0"); // First node
            assertThat(result).contains("Node 9"); // Last node
            assertThat(result).doesNotContain("more nodes"); // No "more nodes" message
        }

        @Test
        @DisplayName("buildStructurePrompt: when template loading fails then uses fallback prompt")
        void buildStructurePrompt_templateLoadFails_usesFallbackPrompt() throws IOException {
            // Given
            String content = "Test content";
            
            when(resourceLoader.getResource(anyString()))
                    .thenReturn(resource);
            when(resource.getInputStream())
                    .thenThrow(new IOException("Resource not found"));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, null, 0, 1);

            // Then - Should use fallback prompt format
            assertThat(result).contains("Analyze the following document chunk");
            assertThat(result).contains(content);
            assertThat(result).contains(defaultOptions.profile());
            assertThat(result).contains(defaultOptions.granularity());
            assertThat(result).contains("Chunk Length: " + content.length());
            assertThat(result).contains("Chunk Position: 1 of 1");
            assertThat(result).contains("Return a JSON object");
        }

        @Test
        @DisplayName("buildStructurePrompt: when resource loader throws exception then uses fallback")
        void buildStructurePrompt_resourceLoaderFails_usesFallback() {
            // Given
            String content = "Fallback test content";
            
            when(resourceLoader.getResource(anyString()))
                    .thenThrow(new RuntimeException("Resource loader error"));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, null, 0, 1);

            // Then
            assertThat(result).contains("Analyze the following document chunk");
            assertThat(result).contains(content);
        }

        @Test
        @DisplayName("buildStructurePrompt: replaces all placeholders correctly")
        void buildStructurePrompt_replacesAllPlaceholders() throws IOException {
            // Given
            String content = "My document content";
            String templateContent = "{content}|{profile}|{granularity}|{charCount}|{chunkIndex}|{totalChunks}|{previousStructure}";
            
            when(resourceLoader.getResource(anyString())).thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, null, 2, 5);

            // Then
            assertThat(result).isEqualTo(content + "|" + defaultOptions.profile() + "|" + 
                                        defaultOptions.granularity() + "|" + content.length() + 
                                        "|3|5|None (first chunk)");
        }
    }

    @Nested
    @DisplayName("loadPromptTemplate Tests")
    class LoadPromptTemplateTests {

        @Test
        @DisplayName("loadPromptTemplate: when template not cached then loads from resources")
        void loadPromptTemplate_notCached_loadsFromResources() throws IOException {
            // Given
            String templateName = "document-structure/system-prompt.txt";
            String templateContent = "This is the template content";
            
            when(resourceLoader.getResource("classpath:prompts/" + templateName))
                    .thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.loadPromptTemplate(templateName);

            // Then
            assertThat(result).isEqualTo(templateContent);
            verify(resourceLoader).getResource("classpath:prompts/" + templateName);
        }

        @Test
        @DisplayName("loadPromptTemplate: when template already cached then returns cached version")
        void loadPromptTemplate_cached_returnsCachedVersion() throws IOException {
            // Given
            String templateName = "cached-template.txt";
            String templateContent = "Cached content";
            
            when(resourceLoader.getResource("classpath:prompts/" + templateName))
                    .thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When - Load twice
            String result1 = promptService.loadPromptTemplate(templateName);
            String result2 = promptService.loadPromptTemplate(templateName);

            // Then - Should only load once from resource loader
            assertThat(result1).isEqualTo(templateContent);
            assertThat(result2).isEqualTo(templateContent);
            assertThat(result1).isSameAs(result2); // Same instance due to caching
            verify(resourceLoader, times(1)).getResource("classpath:prompts/" + templateName);
        }

        @Test
        @DisplayName("loadPromptTemplate: when IOException occurs then throws RuntimeException")
        void loadPromptTemplate_ioException_throwsRuntimeException() throws IOException {
            // Given
            String templateName = "missing-template.txt";
            
            when(resourceLoader.getResource("classpath:prompts/" + templateName))
                    .thenReturn(resource);
            when(resource.getInputStream()).thenThrow(new IOException("File not found"));

            // When & Then
            assertThatThrownBy(() -> promptService.loadPromptTemplate(templateName))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to load template: " + templateName)
                    .hasCauseInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("loadPromptTemplate: when resource is null then throws exception")
        void loadPromptTemplate_nullResource_throwsException() throws IOException {
            // Given
            String templateName = "null-resource.txt";
            
            when(resourceLoader.getResource("classpath:prompts/" + templateName))
                    .thenReturn(resource);
            when(resource.getInputStream()).thenThrow(new IOException("Resource is null"));

            // When & Then
            assertThatThrownBy(() -> promptService.loadPromptTemplate(templateName))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to load template");
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("buildStructurePrompt: when content is empty then handles gracefully")
        void buildStructurePrompt_emptyContent_handlesGracefully() throws IOException {
            // Given
            String content = "";
            String templateContent = "Template {content} {charCount}";
            
            when(resourceLoader.getResource(anyString())).thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, null, 0, 1);

            // Then
            assertThat(result).contains("0"); // charCount should be 0
        }

        @Test
        @DisplayName("buildStructurePrompt: when content has special characters then includes them")
        void buildStructurePrompt_specialCharacters_includesThem() throws IOException {
            // Given
            String content = "Content with {special} & <characters> and \"quotes\"";
            String templateContent = "Template: {content}";
            
            when(resourceLoader.getResource(anyString())).thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, null, 0, 1);

            // Then
            assertThat(result).contains(content);
        }

        @Test
        @DisplayName("buildStructurePrompt: when chunkIndex is large then handles correctly")
        void buildStructurePrompt_largeChunkIndex_handlesCorrectly() throws IOException {
            // Given
            String content = "Chunk content";
            String templateContent = "{chunkIndex}/{totalChunks}";
            
            when(resourceLoader.getResource(anyString())).thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, null, 99, 100);

            // Then
            assertThat(result).isEqualTo("100/100"); // chunkIndex + 1
        }

        @Test
        @DisplayName("buildStructurePrompt: when previous node has null title then handles gracefully")
        void buildStructurePrompt_nodeWithNullTitle_handlesGracefully() throws IOException {
            // Given
            String content = "Content";
            String templateContent = "{previousStructure}";
            DocumentNode nodeWithNullTitle = new DocumentNode();
            nodeWithNullTitle.setId(UUID.randomUUID());
            nodeWithNullTitle.setTitle(null);
            nodeWithNullTitle.setDepth((short) 0);
            
            when(resourceLoader.getResource(anyString())).thenReturn(resource);
            when(resource.getInputStream()).thenReturn(createInputStream(templateContent));

            // When
            String result = promptService.buildStructurePrompt(content, defaultOptions, 
                    List.of(nodeWithNullTitle), 1, 2);

            // Then - Should not crash
            assertThat(result).isNotNull();
        }
    }

    // Helper methods

    private InputStream createInputStream(String content) {
        return new ByteArrayInputStream(content.getBytes());
    }

    private List<DocumentNode> createDocumentNodes(int count) {
        List<DocumentNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            DocumentNode node = new DocumentNode();
            node.setId(UUID.randomUUID());
            node.setTitle("Node " + i);
            node.setType(DocumentNode.NodeType.SECTION);
            node.setDepth((short) 0);
            node.setStartOffset(i * 100);
            node.setEndOffset((i + 1) * 100);
            node.setStartAnchor("Start " + i);
            node.setEndAnchor("End " + i);
            node.setAiConfidence(BigDecimal.valueOf(0.95));
            nodes.add(node);
        }
        return nodes;
    }
}

