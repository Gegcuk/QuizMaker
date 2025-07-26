package uk.gegc.quizmaker.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.service.ai.parser.QuestionParserFactory;
import uk.gegc.quizmaker.service.question.factory.QuestionHandlerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class QuestionResponseParserTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private QuestionHandlerFactory questionHandlerFactory;

    @Mock
    private QuestionParserFactory questionParserFactory;

    @InjectMocks
    private uk.gegc.quizmaker.service.ai.parser.impl.QuestionResponseParserImpl questionResponseParser;

    private static final String VALID_MCQ_RESPONSE = """
            {
                "questions": [
                    {
                        "type": "MCQ_SINGLE",
                        "questionText": "What is machine learning?",
                        "options": ["A", "B", "C", "D"],
                        "correctAnswer": "A",
                        "explanation": "Machine learning is a subset of AI."
                    }
                ]
            }
            """;

    private static final String VALID_TRUE_FALSE_RESPONSE = """
            {
                "questions": [
                    {
                        "type": "TRUE_FALSE",
                        "questionText": "Machine learning is a subset of artificial intelligence.",
                        "correctAnswer": true,
                        "explanation": "Machine learning is indeed a subset of AI."
                    }
                ]
            }
            """;

    private static final String VALID_OPEN_RESPONSE = """
            {
                "questions": [
                    {
                        "type": "OPEN",
                        "questionText": "Explain the concept of machine learning.",
                        "correctAnswer": "Machine learning is a method of data analysis that automates analytical model building.",
                        "explanation": "This is a comprehensive definition of machine learning."
                    }
                ]
            }
            """;

    @Mock
    private uk.gegc.quizmaker.service.question.handler.QuestionHandler mockQuestionHandler;

    @BeforeEach
    void setUp() throws Exception {
        // Setup default behavior for QuestionHandlerFactory
        lenient().when(questionHandlerFactory.getHandler(any(QuestionType.class)))
                .thenReturn(mockQuestionHandler);

        // Setup default behavior for QuestionHandler.validateContent
        lenient().doNothing().when(mockQuestionHandler).validateContent(any());
    }

    @Test
    void shouldParseMCQQuestions() throws Exception {
        // Given
        String aiResponse = VALID_MCQ_RESPONSE;

        // Mock QuestionParserFactory to return valid questions
        Question mockQuestion = new Question();
        mockQuestion.setType(QuestionType.MCQ_SINGLE);
        mockQuestion.setQuestionText("What is machine learning?");
        mockQuestion.setExplanation("Machine learning is a subset of AI.");
        mockQuestion.setContent("{\"options\":[\"A\",\"B\",\"C\",\"D\"],\"correctAnswer\":\"A\"}");

        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.MCQ_SINGLE)))
                .thenReturn(List.of(mockQuestion));

        // When
        List<Question> questions = questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);

        // Then
        assertNotNull(questions);
        assertEquals(1, questions.size());

        Question question = questions.get(0);
        assertEquals(QuestionType.MCQ_SINGLE, question.getType());
        assertEquals("What is machine learning?", question.getQuestionText());
        assertEquals("Machine learning is a subset of AI.", question.getExplanation());
    }

    @Test
    void shouldParseTrueFalseQuestions() throws Exception {
        // Given
        String aiResponse = VALID_TRUE_FALSE_RESPONSE;

        // Mock QuestionParserFactory to return valid questions
        Question mockQuestion = new Question();
        mockQuestion.setType(QuestionType.TRUE_FALSE);
        mockQuestion.setQuestionText("Machine learning is a subset of artificial intelligence.");
        mockQuestion.setExplanation("Machine learning is indeed a subset of AI.");
        mockQuestion.setContent("{\"correctAnswer\":true}");

        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.TRUE_FALSE)))
                .thenReturn(List.of(mockQuestion));

        // When
        List<Question> questions = questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.TRUE_FALSE);

        // Then
        assertNotNull(questions);
        assertEquals(1, questions.size());

        Question question = questions.get(0);
        assertEquals(QuestionType.TRUE_FALSE, question.getType());
        assertEquals("Machine learning is a subset of artificial intelligence.", question.getQuestionText());
        assertEquals("Machine learning is indeed a subset of AI.", question.getExplanation());
    }

    @Test
    void shouldParseOpenQuestions() throws Exception {
        // Given
        String aiResponse = VALID_OPEN_RESPONSE;

        // Mock QuestionParserFactory to return valid questions
        Question mockQuestion = new Question();
        mockQuestion.setType(QuestionType.OPEN);
        mockQuestion.setQuestionText("Explain the concept of machine learning.");
        mockQuestion.setExplanation("This is a comprehensive definition of machine learning.");
        mockQuestion.setContent("{\"correctAnswer\":\"Machine learning is a method of data analysis that automates analytical model building.\"}");

        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.OPEN)))
                .thenReturn(List.of(mockQuestion));

        // When
        List<Question> questions = questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.OPEN);

        // Then
        assertNotNull(questions);
        assertEquals(1, questions.size());

        Question question = questions.get(0);
        assertEquals(QuestionType.OPEN, question.getType());
        assertEquals("Explain the concept of machine learning.", question.getQuestionText());
        assertEquals("This is a comprehensive definition of machine learning.", question.getExplanation());
    }

    @Test
    void shouldHandleEmptyAIResponse() {
        // Given
        String aiResponse = "";

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);
        });
    }

    @Test
    void shouldHandleNullAIResponse() {
        // Given
        String aiResponse = null;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);
        });
    }

    @Test
    void shouldHandleInvalidJSONResponse() {
        // Given
        String aiResponse = "This is not valid JSON";

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);
        });
    }

    @Test
    void shouldHandleMalformedJSONResponse() {
        // Given
        String aiResponse = "{ invalid json }";

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);
        });
    }

    @Test
    void shouldHandleMissingQuestionsArray() {
        // Given
        String aiResponse = "{\"someOtherField\": \"value\"}";

        // Mock QuestionParserFactory to throw exception for missing questions array
        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.MCQ_SINGLE)))
                .thenThrow(new AIResponseParseException("No 'questions' array found in JSON response"));

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);
        });
    }

    @Test
    void shouldHandleEmptyQuestionsArray() {
        // Given
        String aiResponse = "{\"questions\": []}";

        // When
        List<Question> questions = questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);

        // Then
        assertNotNull(questions);
        assertTrue(questions.isEmpty());
    }

    @Test
    void shouldHandleQuestionWithMissingType() {
        // Given
        String aiResponse = """
                {
                    "questions": [
                        {
                            "questionText": "What is AI?",
                            "difficulty": "EASY",
                            "content": {"options": ["A", "B", "C", "D"], "correctAnswer": "A"}
                        }
                    ]
                }
                """;

        // Mock QuestionParserFactory to throw exception for missing type
        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.MCQ_SINGLE)))
                .thenThrow(new AIResponseParseException("Missing 'type' field in question"));

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);
        });
    }

    @Test
    void shouldHandleQuestionWithMissingQuestionText() {
        // Given
        String aiResponse = """
                {
                    "questions": [
                        {
                            "type": "MCQ_SINGLE",
                            "difficulty": "EASY",
                            "content": {"options": ["A", "B", "C", "D"], "correctAnswer": "A"}
                        }
                    ]
                }
                """;

        // Mock QuestionParserFactory to throw exception for missing questionText
        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.MCQ_SINGLE)))
                .thenThrow(new AIResponseParseException("Missing 'questionText' field in question"));

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);
        });
    }

    @Test
    void shouldHandleQuestionWithMissingCorrectAnswer() {
        // Given
        String aiResponse = """
                {
                    "questions": [
                        {
                            "type": "MCQ_SINGLE",
                            "questionText": "What is AI?",
                            "difficulty": "EASY",
                            "content": {"options": ["A", "B", "C", "D"]}
                        }
                    ]
                }
                """;

        // Mock QuestionParserFactory to throw exception for missing correctAnswer
        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.MCQ_SINGLE)))
                .thenThrow(new AIResponseParseException("Missing 'correctAnswer' field in question"));

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);
        });
    }

    @Test
    void shouldHandleInvalidQuestionType() {
        // Given
        String aiResponse = """
                {
                    "questions": [
                        {
                            "type": "INVALID_TYPE",
                            "questionText": "What is AI?",
                            "difficulty": "EASY",
                            "content": {"options": ["A", "B", "C", "D"], "correctAnswer": "A"}
                        }
                    ]
                }
                """;

        // Mock QuestionParserFactory to throw exception for invalid question type
        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.MCQ_SINGLE)))
                .thenThrow(new AIResponseParseException("Invalid question type: INVALID_TYPE"));

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);
        });
    }

    @Test
    void shouldHandleMultipleQuestions() throws Exception {
        // Given
        String aiResponse = """
                {
                    "questions": [
                        {
                            "type": "MCQ_SINGLE",
                            "questionText": "What is AI?",
                            "options": ["A", "B", "C", "D"],
                            "correctAnswer": "A",
                            "explanation": "AI stands for Artificial Intelligence"
                        },
                        {
                            "type": "TRUE_FALSE",
                            "questionText": "Machine learning is a subset of AI.",
                            "correctAnswer": true,
                            "explanation": "This is correct"
                        }
                    ]
                }
                """;

        // Mock QuestionParserFactory to return valid questions
        Question mockQuestion1 = new Question();
        mockQuestion1.setType(QuestionType.MCQ_SINGLE);
        mockQuestion1.setQuestionText("What is AI?");
        mockQuestion1.setContent("{\"options\":[\"A\",\"B\",\"C\",\"D\"],\"correctAnswer\":\"A\"}");

        Question mockQuestion2 = new Question();
        mockQuestion2.setType(QuestionType.TRUE_FALSE);
        mockQuestion2.setQuestionText("Machine learning is a subset of AI.");
        mockQuestion2.setContent("{\"correctAnswer\":true}");

        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.MCQ_SINGLE)))
                .thenReturn(List.of(mockQuestion1, mockQuestion2));

        // When
        List<Question> questions = questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);

        // Then
        assertNotNull(questions);
        assertEquals(2, questions.size());

        Question question1 = questions.get(0);
        assertEquals(QuestionType.MCQ_SINGLE, question1.getType());
        assertEquals("What is AI?", question1.getQuestionText());

        Question question2 = questions.get(1);
        assertEquals(QuestionType.TRUE_FALSE, question2.getType());
        assertEquals("Machine learning is a subset of AI.", question2.getQuestionText());
    }

    @Test
    void shouldCleanAIResponse() {
        // Given
        String aiResponse = """
                Here is the JSON response:
                {
                    "questions": [
                        {
                            "type": "MCQ_SINGLE",
                            "questionText": "What is AI?",
                            "difficulty": "EASY",
                            "content": {"options": ["A", "B", "C", "D"], "correctAnswer": "A"}
                        }
                    ]
                }
                End of response.
                """;

        // Mock QuestionParserFactory to throw exception for invalid JSON after cleaning
        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.MCQ_SINGLE)))
                .thenThrow(new AIResponseParseException("Invalid JSON format after cleaning"));

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);
        });
    }

    @Test
    void shouldValidateQuestionContent() {
        // Given
        String aiResponse = """
                {
                    "questions": [
                        {
                            "type": "MCQ_SINGLE",
                            "questionText": "",
                            "difficulty": "EASY",
                            "content": {"options": ["A", "B", "C", "D"], "correctAnswer": "A"}
                        }
                    ]
                }
                """;

        // Mock QuestionParserFactory to throw exception for empty question text
        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.MCQ_SINGLE)))
                .thenThrow(new AIResponseParseException("Question text cannot be empty"));

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            questionResponseParser.parseQuestionsFromAIResponse(aiResponse, QuestionType.MCQ_SINGLE);
        });
    }


} 