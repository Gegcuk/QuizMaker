package uk.gegc.quizmaker.features.quizgroup.domain.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroup;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroupMembership;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroupMembershipId;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Repository tests for QuizGroupMembershipRepository.
 * 
 * <p>Tests verify:
 * - Membership queries by group and quiz
 * - Position ordering
 * - Existence checks for idempotency
 * - Count queries
 */
@DataJpaTest
@ActiveProfiles("test-mysql")
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.auto=create-drop"
})
@DisplayName("QuizGroupMembershipRepository Tests")
class QuizGroupMembershipRepositoryTest {

    @Autowired
    private QuizGroupMembershipRepository membershipRepository;

    @Autowired
    private QuizGroupRepository quizGroupRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private User owner;
    private Category category;
    private QuizGroup group;
    private Quiz quiz1;
    private Quiz quiz2;
    private Quiz quiz3;

    @BeforeEach
    void setUp() {
        // Force schema creation by accessing EntityManagerFactory metadata
        entityManagerFactory.getMetamodel();
        
        // Create or find role
        Role role = roleRepository.findByRoleName("ROLE_USER")
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setRoleName("ROLE_USER");
                    r.setDescription("User role");
                    r.setDefault(true);
                    return entityManager.persist(r);
                });
        entityManager.flush();

        // Create user
        owner = new User();
        owner.setUsername("owner");
        owner.setEmail("owner@example.com");
        owner.setHashedPassword("{noop}password");
        owner.setActive(true);
        owner.setDeleted(false);
        owner.setRoles(Set.of(role));
        entityManager.persist(owner);
        entityManager.flush();

        // Create category
        category = new Category();
        category.setName("Test Category");
        entityManager.persist(category);
        entityManager.flush();

        // Create group
        group = new QuizGroup();
        group.setOwner(owner);
        group.setName("Test Group");
        group.setIsDeleted(false);
        entityManager.persist(group);
        entityManager.flush();

        // Create quizzes
        quiz1 = createQuiz(owner, category, "Quiz 1");
        quiz2 = createQuiz(owner, category, "Quiz 2");
        quiz3 = createQuiz(owner, category, "Quiz 3");
        entityManager.persist(quiz1);
        entityManager.persist(quiz2);
        entityManager.persist(quiz3);
        entityManager.flush();
    }

    @Test
    @DisplayName("findByGroupIdOrderByPositionAsc returns memberships ordered by position")
    void findByGroupIdOrderByPositionAsc_ReturnsOrdered() {
        // Given - create memberships with non-sequential positions
        QuizGroupMembership m1 = createMembership(group, quiz1, 2);
        QuizGroupMembership m2 = createMembership(group, quiz2, 0);
        QuizGroupMembership m3 = createMembership(group, quiz3, 1);
        entityManager.persist(m1);
        entityManager.persist(m2);
        entityManager.persist(m3);
        entityManager.flush();

        // When
        List<QuizGroupMembership> memberships = membershipRepository.findByGroupIdOrderByPositionAsc(group.getId());

        // Then
        assertThat(memberships).hasSize(3);
        assertThat(memberships)
                .extracting(QuizGroupMembership::getPosition)
                .containsExactly(0, 1, 2);
        assertThat(memberships)
                .extracting(m -> m.getQuiz().getTitle())
                .containsExactly("Quiz 2", "Quiz 3", "Quiz 1");
    }

    @Test
    @DisplayName("findByQuizId returns all groups containing quiz")
    void findByQuizId_ReturnsGroups() {
        // Given - create another group with quiz1
        QuizGroup group2 = new QuizGroup();
        group2.setOwner(owner);
        group2.setName("Group 2");
        group2.setIsDeleted(false);
        entityManager.persist(group2);
        entityManager.flush();

        QuizGroupMembership m1 = createMembership(group, quiz1, 0);
        QuizGroupMembership m2 = createMembership(group2, quiz1, 0);
        entityManager.persist(m1);
        entityManager.persist(m2);
        entityManager.flush();

        // When
        List<QuizGroupMembership> memberships = membershipRepository.findByQuizId(quiz1.getId());

        // Then
        assertThat(memberships).hasSize(2);
        assertThat(memberships)
                .extracting(m -> m.getGroup().getName())
                .containsExactlyInAnyOrder("Test Group", "Group 2");
    }

    @Test
    @DisplayName("existsByGroupIdAndQuizId returns true when membership exists")
    void existsByGroupIdAndQuizId_ExistingMembership_ReturnsTrue() {
        // Given
        QuizGroupMembership membership = createMembership(group, quiz1, 0);
        entityManager.persist(membership);
        entityManager.flush();

        // When
        boolean exists = membershipRepository.existsByGroupIdAndQuizId(group.getId(), quiz1.getId());

        // Then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("existsByGroupIdAndQuizId returns false when membership doesn't exist")
    void existsByGroupIdAndQuizId_NonExistentMembership_ReturnsFalse() {
        // When
        boolean exists = membershipRepository.existsByGroupIdAndQuizId(group.getId(), quiz1.getId());

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("countByGroupId returns correct count")
    void countByGroupId_ReturnsCount() {
        // Given
        createMembership(group, quiz1, 0);
        createMembership(group, quiz2, 1);
        createMembership(group, quiz3, 2);
        entityManager.persist(createMembership(group, quiz1, 0));
        entityManager.persist(createMembership(group, quiz2, 1));
        entityManager.persist(createMembership(group, quiz3, 2));
        entityManager.flush();

        // When
        long count = membershipRepository.countByGroupId(group.getId());

        // Then
        assertThat(count).isEqualTo(3);
    }

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

    private QuizGroupMembership createMembership(QuizGroup group, Quiz quiz, int position) {
        QuizGroupMembership membership = new QuizGroupMembership();
        membership.setId(new QuizGroupMembershipId(group.getId(), quiz.getId()));
        membership.setGroup(group);
        membership.setQuiz(quiz);
        membership.setPosition(position);
        return membership;
    }
}

