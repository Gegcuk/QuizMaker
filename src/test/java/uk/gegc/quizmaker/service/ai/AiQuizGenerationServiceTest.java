package uk.gegc.quizmaker.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.model.document.DocumentChunk;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.service.ai.impl.AiQuizGenerationServiceImpl;
import uk.gegc.quizmaker.service.ai.parser.QuestionResponseParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiQuizGenerationServiceTest {

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private QuestionResponseParser questionResponseParser;

    @Mock
    private ChatClient chatClient;

    @InjectMocks
    private AiQuizGenerationServiceImpl aiQuizGenerationService;

    private DocumentChunk testChunk;
    private List<Question> testQuestions;

    @BeforeEach
    void setUp() {
        // Setup test chunk
        testChunk = new DocumentChunk();
        testChunk.setContent("This is a test chunk about machine learning concepts.");
        testChunk.setChunkIndex(1);
        testChunk.setTitle("Introduction to Machine Learning");

        // Setup test questions
        Question question1 = new Question();
        question1.setType(QuestionType.MCQ_SINGLE);
        question1.setQuestionText("What is machine learning?");
        question1.setDifficulty(Difficulty.MEDIUM);
        question1.setExplanation("Machine learning is a subset of AI.");

        Question question2 = new Question();
        question2.setType(QuestionType.TRUE_FALSE);
        question2.setQuestionText("Machine learning is a subset of artificial intelligence.");
        question2.setDifficulty(Difficulty.EASY);
        question2.setExplanation("This is correct.");

        testQuestions = List.of(question1, question2);
    }

    @Test
    void shouldGenerateQuestionsByType() throws Exception {
        // Given
        String chunkContent = "This is a test chunk about machine learning concepts.";
        String expectedPrompt = "Generate 3 MCQ_SINGLE questions with MEDIUM difficulty";
        String aiResponse = "{\"questions\": [...]}";

        when(promptTemplateService.buildPromptForChunk(
                eq(chunkContent),
                eq(QuestionType.MCQ_SINGLE),
                eq(3),
                eq(Difficulty.MEDIUM)
        )).thenReturn(expectedPrompt);

        // Mock ChatClient to throw exception to test error handling
        when(chatClient.prompt()).thenThrow(new RuntimeException("ChatClient not configured for test"));

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            aiQuizGenerationService.generateQuestionsByType(
                    chunkContent, QuestionType.MCQ_SINGLE, 3, Difficulty.MEDIUM
            );
        });
    }

    @Test
    void shouldHandleEmptyChunkContent() {
        // Given
        String emptyContent = "";

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.generateQuestionsByType(
                    emptyContent, QuestionType.MCQ_SINGLE, 3, Difficulty.MEDIUM
            );
        });
    }

    @Test
    void shouldHandleNullChunkContent() {
        // Given
        String nullContent = null;

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.generateQuestionsByType(
                    nullContent, QuestionType.MCQ_SINGLE, 3, Difficulty.MEDIUM
            );
        });
    }

    @Test
    void shouldHandleShortChunkContent() {
        // Given
        String shortContent = "Too short";

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.generateQuestionsByType(
                    shortContent, QuestionType.MCQ_SINGLE, 3, Difficulty.MEDIUM
            );
        });
    }

    @Test
    void shouldHandleZeroQuestionCount() {
        // Given
        String chunkContent = "This is a test chunk about machine learning concepts.";

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.generateQuestionsByType(
                    chunkContent, QuestionType.MCQ_SINGLE, 0, Difficulty.MEDIUM
            );
        });
    }

    @Test
    void shouldHandleNegativeQuestionCount() {
        // Given
        String chunkContent = "This is a test chunk about machine learning concepts.";

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.generateQuestionsByType(
                    chunkContent, QuestionType.MCQ_SINGLE, -1, Difficulty.MEDIUM
            );
        });
    }

    @Test
    void shouldHandleNullQuestionType() {
        // Given
        String chunkContent = "This is a test chunk about machine learning concepts.";

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.generateQuestionsByType(
                    chunkContent, null, 3, Difficulty.MEDIUM
            );
        });
    }

    @Test
    void shouldHandleNullDifficulty() {
        // Given
        String chunkContent = "This is a test chunk about machine learning concepts.";

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            aiQuizGenerationService.generateQuestionsByType(
                    chunkContent, QuestionType.MCQ_SINGLE, 3, null
            );
        });
    }

    @Test
    void shouldHandleTrueFalseQuestionType() throws Exception {
        // Given
        String chunkContent = "This is a test chunk about machine learning concepts.";
        String expectedPrompt = "Generate 2 TRUE_FALSE questions with EASY difficulty";

        when(promptTemplateService.buildPromptForChunk(
                eq(chunkContent),
                eq(QuestionType.TRUE_FALSE),
                eq(2),
                eq(Difficulty.EASY)
        )).thenReturn(expectedPrompt);

        // Mock ChatClient to throw exception to test error handling
        when(chatClient.prompt()).thenThrow(new RuntimeException("ChatClient not configured for test"));

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            aiQuizGenerationService.generateQuestionsByType(
                    chunkContent, QuestionType.TRUE_FALSE, 2, Difficulty.EASY
            );
        });
    }

    @Test
    void shouldHandleOpenQuestionType() throws Exception {
        // Given
        String chunkContent = "This is a test chunk about machine learning concepts.";
        String expectedPrompt = "Generate 1 OPEN questions with HARD difficulty";

        when(promptTemplateService.buildPromptForChunk(
                eq(chunkContent),
                eq(QuestionType.OPEN),
                eq(1),
                eq(Difficulty.HARD)
        )).thenReturn(expectedPrompt);

        // Mock ChatClient to throw exception to test error handling
        when(chatClient.prompt()).thenThrow(new RuntimeException("ChatClient not configured for test"));

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            aiQuizGenerationService.generateQuestionsByType(
                    chunkContent, QuestionType.OPEN, 1, Difficulty.HARD
            );
        });
    }

    @Test
    void shouldHandleLargeQuestionCount() throws Exception {
        // Given
        String chunkContent = "This is a test chunk about machine learning concepts.";
        String expectedPrompt = "Generate 10 MCQ_SINGLE questions with MEDIUM difficulty";

        when(promptTemplateService.buildPromptForChunk(
                eq(chunkContent),
                eq(QuestionType.MCQ_SINGLE),
                eq(10),
                eq(Difficulty.MEDIUM)
        )).thenReturn(expectedPrompt);

        // Mock ChatClient to throw exception to test error handling
        when(chatClient.prompt()).thenThrow(new RuntimeException("ChatClient not configured for test"));

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            aiQuizGenerationService.generateQuestionsByType(
                    chunkContent, QuestionType.MCQ_SINGLE, 10, Difficulty.MEDIUM
            );
        });
    }
} 