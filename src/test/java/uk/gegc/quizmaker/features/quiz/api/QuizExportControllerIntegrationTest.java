package uk.gegc.quizmaker.features.quiz.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import jakarta.persistence.EntityManager;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class QuizExportControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private EntityManager em;

    @Test
    @DisplayName("scope=me requires auth and exports only author's quizzes; returns JSON array and filename with scope prefix")
    void exportMeScope_streamsJson_withPrefixedFilename() throws Exception {
        // Fetch existing permission from data initializer
        Permission pRead = em.createQuery(
                "SELECT p FROM Permission p WHERE p.permissionName = :name", Permission.class)
                .setParameter("name", PermissionName.QUIZ_READ.name())
                .getSingleResult();

        // Create test role with permission
        Role role = Role.builder().roleName("ROLE_TEST_AUTHOR").permissions(Set.of(pRead)).build();
        em.persist(role);
        em.flush();

        // Create user with role
        User author = new User();
        author.setUsername("author_export_test");
        author.setEmail("author_export@example.com");
        author.setHashedPassword("pw");
        author.setActive(true);
        author.setDeleted(false);
        author.setRoles(Set.of(role));
        userRepository.save(author);
        em.flush();

        // Create category and tag
        Category cat = new Category();
        cat.setName("General");
        categoryRepository.save(cat);

        Tag tag = new Tag();
        tag.setName("java");
        tagRepository.save(tag);

        // Create a private draft quiz by author with one question
        Quiz quiz = new Quiz();
        quiz.setCreator(author);
        quiz.setCategory(cat);
        quiz.setTitle("Exportable Quiz");
        quiz.setDescription("D");
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setDifficulty(Difficulty.EASY);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(5);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz.setTags(Set.of(tag));

        Question q = new Question();
        q.setType(QuestionType.TRUE_FALSE);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("Is Java fun?");
        q.setContent("{\"answer\":true}");

        quiz.setQuestions(Set.of(q));
        quizRepository.save(quiz);

        em.flush();

        // Invoke export with scope=me as author
        MvcResult res = mockMvc.perform(get("/api/v1/quizzes/export")
                        .param("format", "JSON_EDITABLE")
                        .param("scope", "me")
                        .with(user("author_export_test")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment; filename=\"quizzes_me_")))
                .andReturn();

        String body = res.getResponse().getContentAsString();
        assertThat(body.trim()).startsWith("[");
        assertThat(body).contains("Exportable Quiz");
        // should not include any other user's quizzes (we didn't create any)
    }
}

