package uk.gegc.quizmaker.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.ai.application.impl.PromptTemplateServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.validation.GenerationLanguagePolicy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
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

    private static final String SYSTEM_PROMPT = "You are an expert quiz generator. Use the trusted target language.";
    private static final String CONTEXT_TEMPLATE = "Trusted parameters: {language}\nGenerate {questionCount} questions.";
    private static final String MCQ_TEMPLATE = """
            Generate {questionType} questions with {difficulty} difficulty.
            - Use {language} for all text sections.
            """;

    @BeforeEach
    void setUp() {
        // Initialize the template cache with ConcurrentHashMap for thread safety
        ReflectionTestUtils.setField(promptTemplateService, "templateCache", new ConcurrentHashMap<>());
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
        assertThrows(RuntimeException.class, () -> promptTemplateService.loadPromptTemplate("nonexistent.txt"));
    }

    @Test
    void shouldBuildPromptForChunk() throws IOException {
        // Given
        String chunkContent = "This is a test chunk about machine learning.";
        QuestionType questionType = QuestionType.MCQ_SINGLE;
        int questionCount = 3;
        Difficulty difficulty = Difficulty.MEDIUM;
        String language = "fr";

        setupMcqTemplatesOnly();

        // When
        String result = promptTemplateService.buildPromptForChunk(
                chunkContent, questionType, questionCount, difficulty, language
        );

        // Then
        assertNotNull(result);
        assertTrue(result.contains(chunkContent));
        assertTrue(result.contains("Generate MCQ_SINGLE questions with MEDIUM difficulty."));
        assertTrue(result.contains("3"));
        assertTrue(result.contains("MEDIUM"));
        assertTrue(result.contains(language));
    }
    
    @Test
    @DisplayName("Keeps the system message separate from the user prompt")
    void shouldNotIncludeSystemPromptInUserPrompt() throws IOException {
        // Given
        String chunkContent = "Test content";
        setupMcqTemplatesOnly();

        // When
        String userPrompt = promptTemplateService.buildPromptForChunk(
                chunkContent, QuestionType.MCQ_SINGLE, 2, Difficulty.EASY, "en"
        );

        // Then - User prompt should NOT contain the system prompt
        assertNotNull(userPrompt);
        assertFalse(userPrompt.contains(SYSTEM_PROMPT), 
                "User prompt should not include system prompt - it's sent separately");
        // Should start with context template, not system prompt
        assertTrue(userPrompt.startsWith("Trusted parameters:"),
                "User prompt should start with context template");
    }
    
    @Test
    void shouldReplacePlaceholdersCorrectly() throws IOException {
        // Given
        String chunkContent = "Content about Java programming";
        setupMcqTemplatesOnly();

        // When
        String result = promptTemplateService.buildPromptForChunk(
                chunkContent, QuestionType.MCQ_SINGLE, 5, Difficulty.HARD, "es"
        );

        // Then
        assertTrue(result.contains(chunkContent), "Should contain actual content");
        assertTrue(result.contains("5"), "Should replace {questionCount}");
        assertTrue(result.contains("MCQ_SINGLE"), "Should replace {questionType}");
        assertTrue(result.contains("HARD"), "Should replace {difficulty}");
        assertTrue(result.contains("es"), "Should replace {language}");
        assertFalse(result.contains("{content}"), "Should not have unreplaced placeholders");
        assertFalse(result.contains("{questionCount}"), "Should not have unreplaced placeholders");
        assertFalse(result.contains("{questionType}"), "Should not have unreplaced placeholders");
        assertFalse(result.contains("{difficulty}"), "Should not have unreplaced placeholders");
        assertFalse(result.contains("{language}"), "Should not have unreplaced placeholders");
    }
    
    @Test
    void shouldDefaultToEnglishWhenLanguageIsNull() throws IOException {
        // Given
        String chunkContent = "Test content";
        setupMcqTemplatesOnly();

        // When
        String result = promptTemplateService.buildPromptForChunk(
                chunkContent, QuestionType.MCQ_SINGLE, 1, Difficulty.EASY, null
        );

        // Then
        assertTrue(result.contains("en"), "Should default to 'en' when language is null");
    }
    
    @Test
    void shouldRejectBlankLanguageBeforeLoadingTemplates() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> promptTemplateService.buildPromptForChunk(
                        "Test content", QuestionType.MCQ_SINGLE, 1, Difficulty.EASY, "  "));

        assertEquals(GenerationLanguagePolicy.INVALID_LANGUAGE_MESSAGE, exception.getMessage());
        verifyNoInteractions(resourceLoader);
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
        assertThrows(IllegalArgumentException.class, () -> promptTemplateService.buildPromptForChunk(
                null, QuestionType.MCQ_SINGLE, 1, Difficulty.EASY, "en"
        ));
    }

    @Test
    void shouldHandleEmptyChunkContent() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> promptTemplateService.buildPromptForChunk(
                "", QuestionType.MCQ_SINGLE, 1, Difficulty.EASY, "en"
        ));
    }

    @Test
    void shouldHandleZeroQuestionCount() throws IOException {
        // Given
        String chunkContent = "Test content";
        setupMcqTemplatesOnly();

        // When
        String result = promptTemplateService.buildPromptForChunk(
                chunkContent, QuestionType.MCQ_SINGLE, 0, Difficulty.EASY, "en"
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
        assertThrows(IllegalArgumentException.class, () -> promptTemplateService.buildPromptForChunk(
                chunkContent, QuestionType.MCQ_SINGLE, -1, Difficulty.EASY, "en"
        ));
    }

    @Test
    void shouldHandleNullDifficulty() {
        // Given
        String chunkContent = "Test content";

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> promptTemplateService.buildPromptForChunk(
                chunkContent, QuestionType.MCQ_SINGLE, 1, null, "en"
        ));
    }

    @Test
    void shouldHandleTrueFalseQuestionType() throws IOException {
        // Given
        String chunkContent = "Test content about true/false concepts.";
        setupTrueFalseTemplatesOnly();

        // When
        String result = promptTemplateService.buildPromptForChunk(
                chunkContent, QuestionType.TRUE_FALSE, 2, Difficulty.MEDIUM, "en"
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
                chunkContent, QuestionType.OPEN, 1, Difficulty.HARD, "en"
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
        // Setup context template (used in buildPromptForChunk)
        when(resourceLoader.getResource("classpath:prompts/base/context-template.txt"))
                .thenReturn(contextTemplateResource);
        when(contextTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream(CONTEXT_TEMPLATE.getBytes()));

        // Setup MCQ template (used in buildPromptForChunk)
        when(resourceLoader.getResource("classpath:prompts/question-types/mcq-single.txt"))
                .thenReturn(mcqTemplateResource);
        when(mcqTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream(MCQ_TEMPLATE.getBytes()));
    }

    private void setupTrueFalseTemplatesOnly() throws IOException {
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
        // Setup context template
        when(resourceLoader.getResource("classpath:prompts/base/context-template.txt"))
                .thenReturn(contextTemplateResource);
        when(contextTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream(CONTEXT_TEMPLATE.getBytes()));

        // Setup OPEN template
        when(resourceLoader.getResource("classpath:prompts/question-types/open-question.txt"))
                .thenReturn(openTemplateResource);
        when(openTemplateResource.getInputStream()).thenReturn(new ByteArrayInputStream("Generate {questionType} questions with {difficulty} difficulty.".getBytes()));
    }
    
    private void setupTemplatesForType(QuestionType type) throws IOException {
        // Setup context template (use lenient for multiple calls in loop)
        org.mockito.Mockito.lenient().when(resourceLoader.getResource("classpath:prompts/base/context-template.txt"))
                .thenReturn(contextTemplateResource);
        org.mockito.Mockito.lenient().when(contextTemplateResource.getInputStream())
                .thenReturn(new ByteArrayInputStream(CONTEXT_TEMPLATE.getBytes()));

        // Setup question type template
        String templatePath = "classpath:prompts/question-types/" + getExpectedTemplateName(type);
        Resource mockResource = mock(Resource.class);
        org.mockito.Mockito.lenient().when(resourceLoader.getResource(templatePath)).thenReturn(mockResource);
        org.mockito.Mockito.lenient().when(mockResource.getInputStream()).thenReturn(
                new ByteArrayInputStream("Generate {questionType} questions with {difficulty} difficulty.".getBytes())
        );
    }
    
    private String getExpectedTemplateName(QuestionType type) {
        return switch (type) {
            case MCQ_SINGLE -> "mcq-single.txt";
            case MCQ_MULTI -> "mcq-multi.txt";
            case TRUE_FALSE -> "true-false.txt";
            case OPEN -> "open-question.txt";
            case FILL_GAP -> "fill-gap.txt";
            case ORDERING -> "ordering.txt";
            case COMPLIANCE -> "compliance.txt";
            case HOTSPOT -> "hotspot.txt";
            case MATCHING -> "matching.txt";
        };
    }

    @Test
    void shouldHandleAllQuestionTypes() throws IOException {
        // Given
        String chunkContent = "Test content";
        
        // When/Then - Verify all question types work
        for (QuestionType type : QuestionType.values()) {
            setupTemplatesForType(type);
            String result = promptTemplateService.buildPromptForChunk(
                    chunkContent, type, 1, Difficulty.MEDIUM, "en"
            );
            assertNotNull(result, "Should generate prompt for " + type);
            assertTrue(result.contains(chunkContent), "Should contain content for " + type);
            assertTrue(result.contains(type.name()), "Should contain question type for " + type);
        }
    }
    
    @Test
    @DisplayName("Builds the system prompt without unresolved known placeholders")
    void shouldSystemPromptNotContainPlaceholders() throws IOException {
        // Given
        setupSystemPromptOnly();

        // When
        String systemPrompt = promptTemplateService.buildSystemPrompt();

        // Then
        assertNotNull(systemPrompt);
        assertFalse(systemPrompt.contains("{language}"),
                "System prompt must not dispatch an unresolved language placeholder");
    }

    @Test
    @DisplayName("Fails closed when a trusted prompt template cannot be loaded")
    void shouldFailClosedWhenPromptTemplateCannotBeLoaded() throws IOException {
        when(resourceLoader.getResource("classpath:prompts/base/context-template.txt"))
                .thenReturn(contextTemplateResource);
        when(contextTemplateResource.getInputStream()).thenThrow(new IOException("private resource detail"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> promptTemplateService.buildPromptForChunk(
                        "Source that must never enter a fallback prompt",
                        QuestionType.MCQ_SINGLE,
                        1,
                        Difficulty.EASY,
                        "en"));

        assertEquals("Failed to load prompt template: base/context-template.txt", exception.getMessage());
        verify(resourceLoader, never()).getResource("classpath:prompts/question-types/mcq-single.txt");
    }

    @Test
    @DisplayName("Preserves placeholder-looking document source verbatim")
    void shouldPreservePlaceholderLookingSourceVerbatim() throws IOException {
        String source = "Keep {content}, {language}, {questionCount}, and {difficulty} exactly as source data.";
        setupMcqTemplatesOnly();

        String prompt = promptTemplateService.buildPromptForChunk(
                source, QuestionType.MCQ_SINGLE, 3, Difficulty.HARD, "fr");

        assertTrue(prompt.contains("\n" + source + "\n"),
                "Trusted template rendering must finish before source insertion");
    }

    @Test
    @DisplayName("Fails closed if a trusted template reintroduces source interpolation")
    void shouldFailClosedIfTrustedTemplateContainsContentPlaceholder() throws IOException {
        when(resourceLoader.getResource("classpath:prompts/base/context-template.txt"))
                .thenReturn(contextTemplateResource);
        when(contextTemplateResource.getInputStream())
                .thenReturn(new ByteArrayInputStream("Unsafe source slot: {content}".getBytes()));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> promptTemplateService.buildPromptForChunk(
                        "PRIVATE_SOURCE_CANARY",
                        QuestionType.MCQ_SINGLE,
                        1,
                        Difficulty.EASY,
                        "en"));

        assertEquals(
                "Unresolved required prompt placeholder in base/context-template.txt: {content}",
                exception.getMessage());
        verify(resourceLoader, never()).getResource("classpath:prompts/question-types/mcq-single.txt");
    }
    
    @Test
    void shouldUserPromptHaveReplacedLanguagePlaceholder() throws IOException {
        // Given
        String chunkContent = "Test content";
        setupMcqTemplatesOnly();

        // When
        String userPrompt = promptTemplateService.buildPromptForChunk(
                chunkContent, QuestionType.MCQ_SINGLE, 1, Difficulty.EASY, "de"
        );

        // Then
        assertFalse(userPrompt.contains("{language}"), 
                "User prompt should have {language} replaced with actual value");
        assertTrue(userPrompt.contains("de"), 
                "User prompt should contain actual language 'de'");
    }
    
    @Test
    void shouldRejectPaddedLanguageBeforeLoadingTemplates() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> promptTemplateService.buildPromptForChunk(
                        "Test content", QuestionType.MCQ_SINGLE, 1, Difficulty.EASY, "  fr  "));

        assertEquals(GenerationLanguagePolicy.INVALID_LANGUAGE_MESSAGE, exception.getMessage());
        verifyNoInteractions(resourceLoader);
    }
    
    @Test
    void shouldBeThreadSafe() throws IOException, InterruptedException {
        // Given
        setupMcqTemplatesOnly();
        
        // When - Multiple threads access the service simultaneously
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                try {
                    String result = promptTemplateService.buildPromptForChunk(
                            "Thread " + threadId + " content", 
                            QuestionType.MCQ_SINGLE, 
                            2, 
                            Difficulty.MEDIUM,
                            "en"
                    );
                    assertNotNull(result);
                    assertTrue(result.contains("Thread " + threadId + " content"));
                } catch (Exception e) {
                    fail("Thread " + threadId + " failed: " + e.getMessage());
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Then - No ConcurrentModificationException should occur
        // The test passes if no exception is thrown
    }

}
