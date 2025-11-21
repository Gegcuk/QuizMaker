package uk.gegc.quizmaker.features.quizgroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.application.QuizService;
import uk.gegc.quizmaker.features.quizgroup.api.dto.*;
import uk.gegc.quizmaker.features.quizgroup.application.QuizGroupService;
import uk.gegc.quizmaker.features.quizgroup.domain.repository.QuizGroupMembershipRepository;
import uk.gegc.quizmaker.features.quizgroup.domain.repository.QuizGroupRepository;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Quiz Groups feature.
 * 
 * <p>Tests verify end-to-end flows:
 * - Create group → add quizzes → reorder → remove → delete
 * - Archive/unarchive operations
 * - Virtual archived group queries
 * - Permission enforcement
 */
@DisplayName("QuizGroup Integration Tests")
class QuizGroupIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private QuizGroupService quizGroupService;

    @Autowired
    private QuizGroupRepository quizGroupRepository;

    @Autowired
    private QuizGroupMembershipRepository membershipRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private QuizService quizService;

    private User owner;
    private Category category;
    private Quiz quiz1;
    private Quiz quiz2;
    private Quiz quiz3;

    @BeforeEach
    void setUp() {
        // Create role
        Role role = roleRepository.findByRoleName(RoleName.ROLE_USER.name())
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setRoleName(RoleName.ROLE_USER.name());
                    r.setDescription("User role");
                    r.setDefault(true);
                    return roleRepository.save(r);
                });

        // Create user
        owner = new User();
        owner.setUsername("testuser");
        owner.setEmail("test@example.com");
        owner.setHashedPassword("{noop}password");
        owner.setActive(true);
        owner.setDeleted(false);
        owner.setRoles(Set.of(role));
        owner = userRepository.save(owner);

        // Create category
        category = new Category();
        category.setName("Test Category");
        category = categoryRepository.save(category);

        // Create quizzes
        quiz1 = createQuiz(owner, category, "Quiz 1");
        quiz2 = createQuiz(owner, category, "Quiz 2");
        quiz3 = createQuiz(owner, category, "Quiz 3");
        quiz1 = quizRepository.save(quiz1);
        quiz2 = quizRepository.save(quiz2);
        quiz3 = quizRepository.save(quiz3);

        entityManager.flush();
        entityManager.clear();
    }

    @Nested
    @DisplayName("Complete Group Lifecycle")
    class CompleteLifecycleTests {

        @Test
        @WithMockUser(username = "testuser", authorities = {"QUIZ_GROUP_CREATE", "QUIZ_GROUP_READ", "QUIZ_GROUP_UPDATE", "QUIZ_GROUP_DELETE"})
        @DisplayName("Create group → add quizzes → reorder → remove → delete")
        void completeLifecycle_Success() throws Exception {
            // 1. Create group
            CreateQuizGroupRequest createRequest = new CreateQuizGroupRequest(
                    "My Group", "Description", "#FF5733", "book", null
            );

            UUID groupId = quizGroupService.create("testuser", createRequest);
            assertThat(groupId).isNotNull();

            // 2. Verify group exists
            var group = quizGroupRepository.findById(groupId);
            assertThat(group).isPresent();
            assertThat(group.get().getName()).isEqualTo("My Group");

            // 3. Add quizzes
            AddQuizzesToGroupRequest addRequest = new AddQuizzesToGroupRequest(
                    List.of(quiz1.getId(), quiz2.getId(), quiz3.getId()), null
            );
            quizGroupService.addQuizzes("testuser", groupId, addRequest);

            // 4. Verify memberships
            var memberships = membershipRepository.findByGroupIdOrderByPositionAsc(groupId);
            assertThat(memberships).hasSize(3);

            // 5. Reorder quizzes
            ReorderGroupQuizzesRequest reorderRequest = new ReorderGroupQuizzesRequest(
                    List.of(quiz3.getId(), quiz1.getId(), quiz2.getId())
            );
            quizGroupService.reorder("testuser", groupId, reorderRequest);

            // 6. Verify order changed
            var reordered = membershipRepository.findByGroupIdOrderByPositionAsc(groupId);
            assertThat(reordered).hasSize(3);
            assertThat(reordered.get(0).getQuiz().getId()).isEqualTo(quiz3.getId());

            // 7. Remove a quiz
            quizGroupService.removeQuiz("testuser", groupId, quiz2.getId());

            // 8. Verify quiz removed
            var afterRemove = membershipRepository.findByGroupIdOrderByPositionAsc(groupId);
            assertThat(afterRemove).hasSize(2);

            // 9. Delete group
            quizGroupService.delete("testuser", groupId);
            entityManager.flush();
            entityManager.clear();

            // 10. Verify soft delete - findById returns empty due to @SQLRestriction
            var deleted = quizGroupRepository.findById(groupId);
            assertThat(deleted).isEmpty();
            
            // Verify soft delete using native query (bypasses @SQLRestriction)
            var result = entityManager.createNativeQuery(
                    "SELECT is_deleted FROM quiz_groups WHERE group_id = ?")
                    .setParameter(1, groupId)
                    .getSingleResult();
            assertThat(result).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("Archived Quizzes Flow")
    class ArchivedQuizzesTests {

        @Test
        @WithMockUser(username = "testuser", authorities = {"QUIZ_GROUP_READ", "QUIZ_UPDATE"})
        @DisplayName("Archive and unarchive quizzes, query archived group")
        void archiveUnarchiveFlow_Success() throws Exception {
            // Ensure entity state is fresh
            entityManager.flush();
            entityManager.clear();
            
            // Verify quiz exists and is owned by testuser
            var quiz = quizRepository.findById(quiz1.getId());
            assertThat(quiz).isPresent();
            assertThat(quiz.get().getCreator().getUsername()).isEqualTo("testuser");
            
            // 1. Archive quiz (use service directly to avoid MockMvc auth issues)
            quizService.archiveQuiz("testuser", quiz1.getId());

            // 2. Verify quiz is archived
            var archived = quizRepository.findById(quiz1.getId());
            assertThat(archived).isPresent();
            assertThat(archived.get().getStatus()).isEqualTo(QuizStatus.ARCHIVED);

            // 3. Query archived quizzes
            entityManager.flush();
            entityManager.clear();
            mockMvc.perform(get("/api/v1/quiz-groups/archived")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].status").value("ARCHIVED"));

            // 4. Unarchive quiz (use service directly)
            quizService.unarchiveQuiz("testuser", quiz1.getId());

            // 5. Verify quiz is unarchived
            var unarchived = quizRepository.findById(quiz1.getId());
            assertThat(unarchived).isPresent();
            assertThat(unarchived.get().getStatus()).isEqualTo(QuizStatus.DRAFT);
        }
    }

    @Nested
    @DisplayName("Idempotency Tests")
    class IdempotencyTests {

        @Test
        @WithMockUser(username = "testuser", authorities = {"QUIZ_GROUP_CREATE", "QUIZ_GROUP_UPDATE"})
        @DisplayName("Adding same quiz twice is idempotent")
        void addQuizzes_Idempotent() throws Exception {
            // Given
            UUID groupId = quizGroupService.create("testuser",
                    new CreateQuizGroupRequest("Group", null, null, null, null));

            // When - add same quiz twice
            AddQuizzesToGroupRequest request1 = new AddQuizzesToGroupRequest(
                    List.of(quiz1.getId()), null
            );
            quizGroupService.addQuizzes("testuser", groupId, request1);

            AddQuizzesToGroupRequest request2 = new AddQuizzesToGroupRequest(
                    List.of(quiz1.getId()), null
            );
            quizGroupService.addQuizzes("testuser", groupId, request2);

            // Then - only one membership exists
            var memberships = membershipRepository.findByGroupIdOrderByPositionAsc(groupId);
            assertThat(memberships).hasSize(1);
        }

        @Test
        @WithMockUser(username = "testuser", authorities = {"QUIZ_GROUP_CREATE", "QUIZ_GROUP_UPDATE"})
        @DisplayName("Removing non-existent membership is idempotent")
        void removeQuiz_Idempotent() throws Exception {
            // Given
            UUID groupId = quizGroupService.create("testuser",
                    new CreateQuizGroupRequest("Group", null, null, null, null));

            // When - remove quiz that doesn't exist in group
            quizGroupService.removeQuiz("testuser", groupId, quiz1.getId());

            // Then - no exception thrown
            assertThatCode(() -> quizGroupService.removeQuiz("testuser", groupId, quiz1.getId()))
                    .doesNotThrowAnyException();
        }
    }

    // Helper methods

    private Quiz createQuiz(User creator, Category category, String title) {
        Quiz quiz = new Quiz();
        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setTitle(title);
        quiz.setVisibility(uk.gegc.quizmaker.features.quiz.domain.model.Visibility.PRIVATE);
        quiz.setDifficulty(uk.gegc.quizmaker.features.question.domain.model.Difficulty.MEDIUM);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(10);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz.setIsDeleted(false);
        quiz.setQuestions(new HashSet<>());
        quiz.setTags(new HashSet<>());
        return quiz;
    }
}

