package uk.gegc.quizmaker.model.question;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("db-serial")
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuestionSoftDeleteTest {

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Question> fixtureQuestions = new ArrayList<>();

    @AfterTransaction
    void fixtureWritesAreRolledBack() {
        for (Question question : fixtureQuestions) {
            if (question.getId() != null) {
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM questions WHERE id=UUID_TO_BIN(?)",
                        Integer.class, question.getId().toString())).isZero();
            }
        }
    }

    @Test
    void shouldPerformSoftDeleteWhenDeletingQuestion() {
        // Given
        Question question = createTestQuestion();
        Question savedQuestion = questionRepository.save(question);
        UUID questionId = savedQuestion.getId();

        // Verify question exists and is not deleted
        assertThat(questionRepository.findById(questionId)).isPresent();
        assertThat(savedQuestion.getIsDeleted()).isFalse();

        // When
        entityManager.flush(); // Make the INSERT real before exercising @SQLDelete.
        questionRepository.delete(savedQuestion);
        entityManager.flush();
        entityManager.clear();

        // Then
        // Question should not be found in normal queries due to @SQLRestriction
        Optional<Question> foundQuestion = questionRepository.findById(questionId);
        assertThat(foundQuestion).isEmpty();

        assertThat(savedQuestion.getIsDeleted()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT is_deleted FROM questions WHERE id=UUID_TO_BIN(?)",
                Boolean.class, questionId.toString())).isTrue();
    }

    @Test
    void shouldInitializeIsDeletedToFalseOnNewQuestion() {
        // Given
        Question question = createTestQuestion();
        question.setIsDeleted(null); // Explicitly set to null to test initialization

        // When
        Question savedQuestion = questionRepository.save(question);

        // Then
        assertThat(savedQuestion.getIsDeleted()).isFalse();
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT is_deleted FROM questions WHERE id=UUID_TO_BIN(?)",
                Boolean.class, savedQuestion.getId().toString())).isFalse();
    }

    @Test
    void shouldNotReturnDeletedQuestionsInFindAll() {
        // Given
        Question question1 = createTestQuestion();
        Question question2 = createTestQuestion();
        
        Question savedQuestion1 = questionRepository.save(question1);
        Question savedQuestion2 = questionRepository.save(question2);

        // When
        entityManager.flush();
        questionRepository.delete(savedQuestion1);
        entityManager.flush();
        entityManager.clear();
        List<Question> allQuestions = questionRepository.findAll();

        // Then
        assertThat(allQuestions).hasSize(1);
        assertThat(allQuestions.get(0).getId()).isEqualTo(savedQuestion2.getId());
    }

    @Test
    void shouldSetDeletedAtTimestampOnSoftDelete() {
        // Given
        Question question = createTestQuestion();
        Question savedQuestion = questionRepository.save(question);

        // When
        entityManager.flush(); // Make the INSERT real before exercising @SQLDelete.
        questionRepository.delete(savedQuestion);
        entityManager.flush();
        entityManager.clear();

        // Then
        assertThat(savedQuestion.getIsDeleted()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM questions WHERE id=UUID_TO_BIN(?)",
                java.sql.Timestamp.class, savedQuestion.getId().toString())).isNotNull();
    }

    private Question createTestQuestion() {
        Question question = new Question();
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("Test question?");
        question.setContent("{\"options\": [\"A\", \"B\", \"C\", \"D\"], \"correct\": 0}");
        question.setHint("Test hint");
        question.setExplanation("Test explanation");
        question.setQuizId(new ArrayList<>());
        question.setTags(new ArrayList<>());
        fixtureQuestions.add(question);
        return question;
    }
} 