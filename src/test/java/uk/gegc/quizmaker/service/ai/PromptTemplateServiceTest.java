package uk.gegc.quizmaker.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.service.ai.impl.PromptTemplateServiceImpl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PromptTemplateServiceTest {

    @Mock
    private ResourceLoader resourceLoader;

    @Mock
    private Resource systemPromptResource;
    
    @Mock
    private Resource contextTemplateResource;
    
    @Mock
    private Resource mcqTemplateResource;
    
    @Mock
    private Resource trueFalseTemplateResource;
    
    @Mock
    private Resource openTemplateResource;
    
    @Mock
    private Resource nonexistentResource;

    @InjectMocks
    private PromptTemplateServiceImpl promptTemplateService;

    private static final String SYSTEM_PROMPT = "You are an expert quiz generator.";
    private static final String CONTEXT_TEMPLATE = "Document content: {content}\nGenerate {questionCount} questions.";
    private static final String MCQ_TEMPLATE = "Generate {questionType} questions with {difficulty} difficulty.";

    @BeforeEach
    void setUp() {
        // Initialize the template cache
        ReflectionTestUtils.setField(promptTemplateService, "templateCache", new HashMap<>());
    }

        @Test
    void shouldLoadPromptTemplate() throws IOException {
        // Given
        String templateContent = "Test template content";
        InputStream inputStream = new ByteArrayInputStream(templateContent.getBytes());

        when(resourceLoader.getResource("classpath:prompts/base/system-prompt.txt"))
                .thenReturn(systemPromptResource);
        when(systemPromptResource.getInputStream()).thenReturn(inputStream);

        // When
        String result = promptTemplateService.loadPromptTemplate("base/system-prompt.txt");

        // Then
        assertEquals(templateContent, result);
        verify(resourceLoader).getResource("classpath:prompts/base/system-prompt.txt");
        verify(systemPromptResource).getInputStream();
    }

    @Test
    void shouldCachePromptTemplate() throws IOException {
        // Given
        String templateContent = "Test template content";
        InputStream inputStream = new ByteArrayInputStream(templateContent.getBytes());
        
        when(resourceLoader.getResource("classpath:prompts/base/system-prompt.txt"))
                .thenReturn(systemPromptResource);
        when(systemPromptResource.getInputStream()).thenReturn(inputStream);

        // When - Load template twice
        String result1 = promptTemplateService.loadPromptTemplate("base/system-prompt.txt");
        String result2 = promptTemplateService.loadPromptTemplate("base/system-prompt.txt");

        // Then
        assertEquals(templateContent, result1);
        assertEquals(templateContent, result2);
        // Should only call resource loader once due to caching
        verify(resourceLoader, times(1)).getResource("classpath:prompts/base/system-prompt.txt");
    }

    @Test
    void shouldHandleTemplateNotFound() throws IOException {
        // Given
        when(resourceLoader.getResource("classpath:prompts/nonexistent.txt"))
                .thenReturn(nonexistentResource);
        when(nonexistentResource.getInputStream()).thenThrow(new IOException("File not found"));

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            promptTemplateService.loadPromptTemplate("nonexistent.txt");
        });
    }

    @Test
    void shouldBuildPromptForChunk() throws IOException {
        // Given
        String chunkContent = "This is a test chunk about machine learning.";
        QuestionType questionType = QuestionType.MCQ_SINGLE;
        int questionCount = 3;
        Difficulty difficulty = Difficulty.MEDIUM;
        
        setupMcqTemplatesOnly();

        // When
        String result = promptTemplateService.buildPromptForChunk(
                chunkContent, questionType, questionCount, difficulty
        );

        // Then
        assertNotNull(result);
        assertTrue(result.contains(chunkContent));
        assertTrue(result.contains("Generate MCQ_SINGLE questions with MEDIUM difficulty."));
        assertTrue(result.contains("3"));
        assertTrue(result.contains("MEDIUM"));
    }

    @Test
    void shouldBuildSystemPrompt() throws IOException {
        // Given
        setupSystemPromptOnly();

        // When
        String result = promptTemplateService.buildSystemPrompt();

        // Then
        assertNotNull(result);
        assertTrue(result.contains(SYSTEM_PROMPT));
    }

    @Test
    void shouldHandleNullChunkContent() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            promptTemplateService.buildPromptForChunk(
                    null, QuestionType.MCQ_SINGLE, 1, Difficulty.EASY
            );
        });
    }

    @Test
    void shouldHandleEmptyChunkContent() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            promptTemplateService.buildPromptForChunk(
                    "", QuestionType.MCQ_SINGLE, 1, Difficulty.EASY
            );
        });
    }

    @Test
    void shouldHandleZeroQuestionCount() throws IOException {
        // Given
        String chunkContent = "Test content";
        setupMcqTemplatesOnly();

        // When
        String result = promptTemplateService.buildPromptForChunk(
                chunkContent, QuestionType.MCQ_SINGLE, 0, Difficulty.EASY
        );

        // Then
        assertNotNull(result);
        assertTrue(result.contains("0"));
    }

    @Test
    void shouldHandleNegativeQuestionCount() {
        // Given
        String chunkContent = "Test content";

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            promptTemplateService.buildPromptForChunk(
                    chunkContent, QuestionType.MCQ_SINGLE, -1, Difficulty.EASY
            );
        });
    }

    @Test
    void shouldHandleNullDifficulty() {
        // Given
        String chunkContent = "Test content";

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            promptTemplateService.buildPromptForChunk(
                    chunkContent, QuestionType.MCQ_SINGLE, 1, null
            );
        });
    }

    @Test
    void shouldHandleTrueFalseQuestionType() throws IOException {
        // Given
        String chunkContent = "Test content about true/false concepts.";
        setupTrueFalseTemplatesOnly();

        // When
        String result = promptTemplateService.buildPromptForChunk(
                chunkContent, QuestionType.TRUE_FALSE, 2, Difficulty.MEDIUM
        );

        // Then
        assertNotNull(result);
        assertTrue(result.contains(chunkContent));
        assertTrue(result.contains("Generate TRUE_FALSE questions with MEDIUM difficulty."));
        assertTrue(result.contains("2"));
        assertTrue(result.contains("MEDIUM"));
    }

    @Test
    void shouldHandleOpenQuestionType() throws IOException {
        // Given
        String chunkContent = "Test content for open questions.";
        setupOpenTemplatesOnly();

        // When
        String result = promptTemplateService.buildPromptForChunk(
                chunkContent, QuestionType.OPEN, 1, Difficulty.HARD
        );

        // Then
        assertNotNull(result);
        assertTrue(result.contains(chunkContent));
        assertTrue(result.contains("Generate OPEN questions with HARD difficulty."));
        assertTrue(result.contains("1"));
        assertTrue(result.contains("HARD"));
    }

    private void setupSystemPromptOnly() throws IOException {
        // Setup system prompt only
        when(resourceLoader.getResource("classpath:prompts/base/system-prompt.txt"))
                .thenReturn(systemPromptResource);
        when(systemPromptResource.getInputStream()).thenReturn(new ByteArrayInputStream(SYSTEM_PROMPT.getBytes()));
    }

    private void setupMcqTemplatesOnly() throws IOException {
        // Setup system prompt
        when(resourceLoader.getResource("classpath:prompts/base/system-prompt.txt"))
                .thenReturn(systemPromptResource);
        when(systemPromptResource.getInputStream()).thenReturn(new ByteArrayInputStream(SYSTEM_PROMPT.getBytes()));

        // Setup context template
        when(resourceLoader.getResource("classpath:prompts/base/context-template.txt"))
                .thenReturn(contextTemplateResource);
        when(contextTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream(CONTEXT_TEMPLATE.getBytes()));

        // Setup MCQ template
        when(resourceLoader.getResource("classpath:prompts/question-types/mcq-single.txt"))
                .thenReturn(mcqTemplateResource);
        when(mcqTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream(MCQ_TEMPLATE.getBytes()));
    }

    private void setupTrueFalseTemplatesOnly() throws IOException {
        // Setup system prompt
        when(resourceLoader.getResource("classpath:prompts/base/system-prompt.txt"))
                .thenReturn(systemPromptResource);
        when(systemPromptResource.getInputStream()).thenReturn(new ByteArrayInputStream(SYSTEM_PROMPT.getBytes()));

        // Setup context template
        when(resourceLoader.getResource("classpath:prompts/base/context-template.txt"))
                .thenReturn(contextTemplateResource);
        when(contextTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream(CONTEXT_TEMPLATE.getBytes()));

        // Setup TRUE_FALSE template
        when(resourceLoader.getResource("classpath:prompts/question-types/true-false.txt"))
                .thenReturn(trueFalseTemplateResource);
        when(trueFalseTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream("Generate {questionType} questions with {difficulty} difficulty.".getBytes()));
    }

    private void setupOpenTemplatesOnly() throws IOException {
        // Setup system prompt
        when(resourceLoader.getResource("classpath:prompts/base/system-prompt.txt"))
                .thenReturn(systemPromptResource);
        when(systemPromptResource.getInputStream()).thenReturn(new ByteArrayInputStream(SYSTEM_PROMPT.getBytes()));

        // Setup context template
        when(resourceLoader.getResource("classpath:prompts/base/context-template.txt"))
                .thenReturn(contextTemplateResource);
        when(contextTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream(CONTEXT_TEMPLATE.getBytes()));

        // Setup OPEN template
        when(resourceLoader.getResource("classpath:prompts/question-types/open-question.txt"))
                .thenReturn(openTemplateResource);
        when(openTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream("Generate {questionType} questions with {difficulty} difficulty.".getBytes()));
    }

    private void setupMockTemplates() throws IOException {
        // Setup system prompt
        when(resourceLoader.getResource("classpath:prompts/base/system-prompt.txt"))
                .thenReturn(systemPromptResource);
        when(systemPromptResource.getInputStream()).thenReturn(new ByteArrayInputStream(SYSTEM_PROMPT.getBytes()));

        // Setup context template
        when(resourceLoader.getResource("classpath:prompts/base/context-template.txt"))
                .thenReturn(contextTemplateResource);
        when(contextTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream(CONTEXT_TEMPLATE.getBytes()));

        // Setup MCQ template
        when(resourceLoader.getResource("classpath:prompts/question-types/mcq-single.txt"))
                .thenReturn(mcqTemplateResource);
        when(mcqTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream(MCQ_TEMPLATE.getBytes()));

        // Setup TRUE_FALSE template
        when(resourceLoader.getResource("classpath:prompts/question-types/true-false.txt"))
                .thenReturn(trueFalseTemplateResource);
        when(trueFalseTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream("Generate {questionType} questions with {difficulty} difficulty.".getBytes()));

        // Setup OPEN template
        when(resourceLoader.getResource("classpath:prompts/question-types/open-question.txt"))
                .thenReturn(openTemplateResource);
        when(openTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream("Generate {questionType} questions with {difficulty} difficulty.".getBytes()));
    }
} 