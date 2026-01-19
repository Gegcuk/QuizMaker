package uk.gegc.quizmaker.features.quizgroup.domain.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroup;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroupMembership;
import uk.gegc.quizmaker.features.quizgroup.domain.model.QuizGroupMembershipId;
import uk.gegc.quizmaker.features.quizgroup.domain.repository.projection.QuizGroupSummaryProjection;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Repository tests for QuizGroupRepository.
 * 
 * <p>Tests verify:
 * - Basic CRUD operations
 * - Queries by owner with pagination
 * - Projection queries for list views
 * - Soft delete filtering
 * - Document linkage queries
 */
@DataJpaTest
@ActiveProfiles("test-mysql")
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.properties.hibernate.hbm2ddl.auto=update"
})
@DisplayName("QuizGroupRepository Tests")
class QuizGroupRepositoryTest {

    @Autowired
    private QuizGroupRepository quizGroupRepository;

    @Autowired
    private QuizGroupMembershipRepository membershipRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private User owner;
    private User otherUser;
    private Category category;
    private QuizGroup group1;
    private QuizGroup group2;
    private Document document;

    @BeforeEach
    void setUp() {
        // Get or create role - check if exists first to avoid duplicate key violation
        Role role = roleRepository.findByRoleName("ROLE_USER").orElseGet(() -> {
            Role newRole = new Role();
            newRole.setRoleName("ROLE_USER");
            newRole.setDescription("User role");
            newRole.setDefault(true);
            return entityManager.persist(newRole);
        });
        entityManager.flush();

        // Create users
        owner = new User();
        owner.setUsername("owner");
        owner.setEmail("owner@example.com");
        owner.setHashedPassword("{noop}password");
        owner.setActive(true);
        owner.setDeleted(false);
        owner.setRoles(Set.of(role));
        entityManager.persist(owner);
        entityManager.flush();

        otherUser = new User();
        otherUser.setUsername("other");
        otherUser.setEmail("other@example.com");
        otherUser.setHashedPassword("{noop}password");
        otherUser.setActive(true);
        otherUser.setDeleted(false);
        otherUser.setRoles(Set.of(role));
        entityManager.persist(otherUser);
        entityManager.flush();

        // Create category
        category = new Category();
        category.setName("Test Category");
        entityManager.persist(category);
        entityManager.flush();

        // Create document
        document = new Document();
        document.setOriginalFilename("test.pdf");
        document.setContentType("application/pdf");
        document.setFileSize(1000L);
        document.setFilePath("/test/path");
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setUploadedBy(owner);
        entityManager.persist(document);
        entityManager.flush();

        // Create groups
        group1 = new QuizGroup();
        group1.setOwner(owner);
        group1.setName("Group 1");
        group1.setDescription("Description 1");
        group1.setColor("#FF5733");
        group1.setIsDeleted(false);
        entityManager.persist(group1);
        entityManager.flush();

        group2 = new QuizGroup();
        group2.setOwner(owner);
        group2.setName("Group 2");
        group2.setDocument(document);
        group2.setIsDeleted(false);
        entityManager.persist(group2);
        entityManager.flush();
    }

    @Test
    @DisplayName("findByOwnerId returns groups for owner with pagination")
    void findByOwnerId_ReturnsGroups() {
        // When
        Page<QuizGroup> page = quizGroupRepository.findByOwnerId(
                owner.getId(), PageRequest.of(0, 10)
        );

        // Then
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(QuizGroup::getName)
                .containsExactlyInAnyOrder("Group 1", "Group 2");
    }

    @Test
    @DisplayName("findByOwnerIdProjected returns projections with quiz count")
    void findByOwnerIdProjected_ReturnsProjections() {
        // Given - add a quiz to group1
        Quiz quiz = createQuiz(owner, category);
        entityManager.persist(quiz);
        entityManager.flush();

        QuizGroupMembership membership = new QuizGroupMembership();
        membership.setId(new QuizGroupMembershipId(group1.getId(), quiz.getId()));
        membership.setGroup(group1);
        membership.setQuiz(quiz);
        membership.setPosition(0);
        entityManager.persist(membership);
        entityManager.flush();

        // When
        Page<QuizGroupSummaryProjection> page = quizGroupRepository.findByOwnerIdProjected(
                owner.getId(), PageRequest.of(0, 10)
        );

        // Then
        assertThat(page.getContent()).hasSize(2);
        QuizGroupSummaryProjection group1Projection = page.getContent().stream()
                .filter(p -> "Group 1".equals(p.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(group1Projection.getQuizCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("findByDocumentId returns group linked to document")
    void findByDocumentId_ReturnsGroup() {
        // When
        Optional<QuizGroup> found = quizGroupRepository.findByDocumentId(document.getId());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getDocument()).isEqualTo(document);
        assertThat(found.get().getName()).isEqualTo("Group 2");
    }

    @Test
    @DisplayName("Soft delete excludes deleted groups from queries")
    void softDelete_ExcludesDeletedGroups() {
        // Given
        group1.setIsDeleted(true);
        entityManager.persist(group1);
        entityManager.flush();

        // When
        Page<QuizGroup> page = quizGroupRepository.findByOwnerId(
                owner.getId(), PageRequest.of(0, 10)
        );

        // Then
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getName()).isEqualTo("Group 2");
    }

    private Quiz createQuiz(User creator, Category category) {
        Quiz quiz = new Quiz();
        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setTitle("Test Quiz");
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

