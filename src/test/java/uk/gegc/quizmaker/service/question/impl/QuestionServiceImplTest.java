package uk.gegc.quizmaker.service.question.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.question.QuestionDto;
import uk.gegc.quizmaker.dto.question.UpdateQuestionRequest;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.repository.question.QuestionRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.service.question.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.service.question.handler.QuestionHandler;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("QuestionServiceImpl Unit Tests")
class QuestionServiceImplTest {

    private static final String DUMMY_USER = "testUser";

    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private QuizRepository quizRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private QuestionHandlerFactory factory;
    @Mock
    private QuestionHandler handler;

    @InjectMocks
    private QuestionServiceImpl questionService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        lenient().when(factory.getHandler(any())).thenReturn(handler);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("createQuestion: happy path should save and return new UUID")
    void createQuestion_happyPath_savesAndReturnsId() {
        CreateQuestionRequest req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("Q?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));

        when(questionRepository.save(any(Question.class)))
                .thenAnswer(inv -> {
                    Question q = inv.getArgument(0);
                    q.setId(UUID.randomUUID());
                    return q;
                });

        UUID id = questionService.createQuestion(DUMMY_USER, req);

        assertThat(id).isNotNull();
        verify(handler).validateContent(req);
        verify(questionRepository).save(any(Question.class));
    }

    @Test
    @DisplayName("createQuestion: missing quiz should throw ResourceNotFoundException")
    void createQuestion_missingQuiz_throws404() {
        CreateQuestionRequest req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("Q?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));
        req.setQuizIds(List.of(UUID.randomUUID()));

        when(quizRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> questionService.createQuestion(DUMMY_USER, req))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(questionRepository, never()).save(any());
    }

    @Test
    @DisplayName("listQuestions: with quizId should delegate to repository")
    void listQuestions_withQuizId_delegatesToRepo() {
        UUID quizId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 10);

        when(questionRepository.findAllByQuizId_Id(eq(quizId), eq(page)))
                .thenReturn(new PageImpl<>(List.of(new Question())));

        Page<QuestionDto> result = questionService.listQuestions(quizId, page);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("getQuestion: not found should throw ResourceNotFoundException")
    void getQuestion_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(questionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> questionService.getQuestion(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updateQuestion: happy path should update and return DTO")
    void updateQuestion_happyPath_updatesAndReturns() {
        UUID id = UUID.randomUUID();
        Question existing = new Question();
        existing.setId(id);

        UpdateQuestionRequest updateQuestionRequest = new UpdateQuestionRequest();
        updateQuestionRequest.setType(QuestionType.TRUE_FALSE);
        updateQuestionRequest.setDifficulty(Difficulty.EASY);
        updateQuestionRequest.setQuestionText("Question?");
        updateQuestionRequest.setContent(objectMapper.createObjectNode().put("answer", false));

        when(questionRepository.findById(id)).thenReturn(Optional.of(existing));
        when(questionRepository.save(any(Question.class))).thenAnswer(inv -> inv.getArgument(0));

        QuestionDto dto = questionService.updateQuestion(DUMMY_USER, id, updateQuestionRequest);

        assertThat(dto.getId()).isEqualTo(id);
        verify(handler).validateContent(updateQuestionRequest);
        verify(questionRepository).save(existing);
    }

    @Test
    @DisplayName("deleteQuestion: existing should delete without error")
    void deleteQuestion_existing_deletes() {
        UUID id = UUID.randomUUID();
        var q = new Question();
        q.setId(id);
        when(questionRepository.findById(id)).thenReturn(Optional.of(q));

        questionService.deleteQuestion(DUMMY_USER, id);

        verify(questionRepository).delete(q);
    }

    @Test
    @DisplayName("deleteQuestion: missing should throw ResourceNotFoundException")
    void deleteQuestion_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(questionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> questionService.deleteQuestion(DUMMY_USER, id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createQuestion: with tags should succeed")
    void createQuestion_withTags_succeeds() {
        UUID tagId = UUID.randomUUID();
        var req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("T/F?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));
        req.setTagIds(List.of(tagId));

        Tag tag = new Tag();
        tag.setId(tagId);
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
        when(questionRepository.save(any(Question.class)))
                .thenAnswer(inv -> {
                    Question q = inv.getArgument(0);
                    q.setId(UUID.randomUUID());
                    return q;
                });

        UUID result = questionService.createQuestion(DUMMY_USER, req);

        assertThat(result).isNotNull();
        verify(tagRepository).findById(tagId);
        verify(questionRepository).save(any(Question.class));
    }

    @Test
    @DisplayName("createQuestion: unknown tag should throw ResourceNotFoundException")
    void createQuestion_unknownTag_throws404() {
        UUID badTag = UUID.randomUUID();
        var req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("T/F?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));
        req.setTagIds(List.of(badTag));

        when(tagRepository.findById(badTag)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> questionService.createQuestion(DUMMY_USER, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tag " + badTag);

        verify(questionRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateQuestion: with tags should succeed")
    void updateQuestion_withTags_succeeds() {
        UUID id = UUID.randomUUID(), tagId = UUID.randomUUID();
        var existing = new Question();
        existing.setId(id);

        when(questionRepository.findById(id)).thenReturn(Optional.of(existing));
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(new Tag() {{
            setId(tagId);
        }}));
        when(questionRepository.save(existing)).thenReturn(existing);

        var req = new UpdateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.MEDIUM);
        req.setQuestionText("Changed?");
        req.setContent(objectMapper.createObjectNode().put("answer", false));
        req.setTagIds(List.of(tagId));

        QuestionDto dto = questionService.updateQuestion(DUMMY_USER, id, req);

        assertThat(dto.getId()).isEqualTo(id);
        verify(tagRepository).findById(tagId);
        verify(questionRepository).save(existing);
    }

    @Test
    @DisplayName("updateQuestion: unknown tag should throw ResourceNotFoundException")
    void updateQuestion_unknownTag_throws404() {
        UUID id = UUID.randomUUID(), badTag = UUID.randomUUID();
        var existing = new Question();
        existing.setId(id);

        when(questionRepository.findById(id)).thenReturn(Optional.of(existing));
        when(tagRepository.findById(badTag)).thenReturn(Optional.empty());

        var req = new UpdateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.MEDIUM);
        req.setQuestionText("Changed?");
        req.setContent(objectMapper.createObjectNode().put("answer", false));
        req.setTagIds(List.of(badTag));

        assertThatThrownBy(() -> questionService.updateQuestion(DUMMY_USER, id, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tag " + badTag);

        verify(questionRepository, never()).save(any());
    }
}