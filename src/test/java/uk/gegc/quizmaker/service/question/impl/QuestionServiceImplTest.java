package uk.gegc.quizmaker.service.question.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import uk.gegc.quizmaker.dto.question.*;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.repository.question.QuestionRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.quiz.TagRepository;
import uk.gegc.quizmaker.service.question.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.service.question.handler.QuestionHandler;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuestionServiceImplTest {

    @Mock QuestionRepository qRepo;
    @Mock QuizRepository quizRepo;
    @Mock TagRepository tagRepo;
    @Mock QuestionHandlerFactory factory;
    @Mock QuestionHandler handler;

    @InjectMocks
    QuestionServiceImpl svc;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // make factory return our handler for any type
        lenient().when(factory.getHandler(any())).thenReturn(handler);
        objectMapper = new ObjectMapper();
    }

    @Test
    void createQuestion_happyPath_savesAndReturnsId() {
        CreateQuestionRequest req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("Q?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));

        // no quiz/tag lookups
        when(qRepo.save(any(Question.class)))
                .thenAnswer(inv -> {
                    Question q = inv.getArgument(0);
                    q.setId(UUID.randomUUID());
                    return q;
                });

        UUID id = svc.createQuestion(req);

        assertThat(id).isNotNull();
        // now factory.getHandler() → handler is non-null, and handler.validateContent was called
        verify(handler).validateContent(req);
        verify(qRepo).save(any(Question.class));
    }

    @Test
    void createQuestion_missingQuiz_throws404() {
        CreateQuestionRequest req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("Q?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));
        req.setQuizIds(List.of(UUID.randomUUID()));

        when(quizRepo.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.createQuestion(req))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(qRepo, never()).save(any());
    }

    @Test
    void listQuestions_withQuizId_delegatesToRepo() {
        UUID quizId = UUID.randomUUID();
        Pageable page = PageRequest.of(0,10);
        when(qRepo.findAllByQuizId_Id(eq(quizId), eq(page)))
                .thenReturn(new PageImpl<>(List.of(new Question())));

        var result = svc.listQuestions(quizId, page);
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getQuestion_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(qRepo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.getQuestion(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateQuestion_happyPath_updatesAndReturns() {
        UUID id = UUID.randomUUID();
        var existing = new Question();
        existing.setId(id);

        var req = new UpdateQuestionRequest();
        req.setType(uk.gegc.quizmaker.model.question.QuestionType.TRUE_FALSE);
        req.setDifficulty(uk.gegc.quizmaker.model.question.Difficulty.EASY);
        req.setQuestionText("Q?");
        req.setContent(new ObjectMapper().createObjectNode().put("answer", false));

        when(qRepo.findById(id)).thenReturn(Optional.of(existing));
        when(qRepo.save(any())).then(inv -> inv.getArgument(0));

        var dto = svc.updateQuestion(id, req);
        assertThat(dto.getId()).isEqualTo(id);
        verify(handler).validateContent(req);
    }

    @Test
    void deleteQuestion_existing_deletes() {
        UUID id = UUID.randomUUID();
        var q = new Question(); q.setId(id);
        when(qRepo.findById(id)).thenReturn(Optional.of(q));

        svc.deleteQuestion(id);
        verify(qRepo).delete(q);
    }

    @Test
    void deleteQuestion_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(qRepo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.deleteQuestion(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createQuestion_withTags_succeeds() {
        // prepare a request with one tag
        UUID tagId = UUID.randomUUID();
        CreateQuestionRequest req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("T/F?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));
        req.setTagIds(List.of(tagId));

        // quizRepo: no quizzes
        // tagRepo returns a real Tag
        Tag tag = new Tag();
        tag.setId(tagId);
        when(tagRepo.findById(tagId)).thenReturn(Optional.of(tag));

        // stub save() to assign an ID
        when(qRepo.save(any(Question.class)))
                .thenAnswer(inv -> { Question q = inv.getArgument(0); q.setId(UUID.randomUUID()); return q; });

        UUID result = svc.createQuestion(req);

        assertThat(result).isNotNull();
        verify(tagRepo).findById(tagId);
        verify(qRepo).save(any(Question.class));
    }

    @Test
    void createQuestion_unknownTag_throws404() {
        UUID badTag = UUID.randomUUID();
        CreateQuestionRequest req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("T/F?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));
        req.setTagIds(List.of(badTag));

        when(tagRepo.findById(badTag)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.createQuestion(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tag " + badTag);
        verify(qRepo, never()).save(any());
    }

    @Test
    void updateQuestion_withTags_succeeds() {
        UUID id = UUID.randomUUID(), tagId = UUID.randomUUID();
        // existing entity
        Question existing = new Question();
        existing.setId(id);
        when(qRepo.findById(id)).thenReturn(Optional.of(existing));

        // handler passes
        UpdateQuestionRequest req = new UpdateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.MEDIUM);
        req.setQuestionText("Changed?");
        req.setContent(objectMapper.createObjectNode().put("answer", false));
        req.setTagIds(List.of(tagId));

        // quizRepo: no quizzes
        Tag tag = new Tag(); tag.setId(tagId);
        when(tagRepo.findById(tagId)).thenReturn(Optional.of(tag));

        when(qRepo.save(any(Question.class))).thenAnswer(inv -> inv.getArgument(0));

        QuestionDto dto = svc.updateQuestion(id, req);

        assertThat(dto.getId()).isEqualTo(id);
        verify(tagRepo).findById(tagId);
        verify(qRepo).save(existing);
    }

    @Test
    void updateQuestion_unknownTag_throws404() {
        UUID id = UUID.randomUUID(), badTag = UUID.randomUUID();
        Question existing = new Question(); existing.setId(id);
        when(qRepo.findById(id)).thenReturn(Optional.of(existing));

        UpdateQuestionRequest req = new UpdateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.MEDIUM);
        req.setQuestionText("Changed?");
        req.setContent(objectMapper.createObjectNode().put("answer", false));
        req.setTagIds(List.of(badTag));

        when(tagRepo.findById(badTag)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.updateQuestion(id, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tag " + badTag);
        verify(qRepo, never()).save(any());
    }
}