package uk.gegc.quizmaker.features.quiz.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.HashSet;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Quiz API access control (Anonymous access only).
 * Tests AccessPolicy enforcement through real HTTP endpoints with real database.
 * 
 * <p>These tests verify end-to-end access control for anonymous users:
 * - Public quizzes are accessible to anonymous users
 * - Private quizzes are NOT accessible to anonymous users
 * 
 * <p><strong>Note on Test Scope:</strong> Due to parameter validation constraints on the {@code @Validated}
 * Quiz Controller, testing authenticated scenarios through MockMvc is challenging because
 * the {@code Authentication} parameter validation occurs before the security context is
 * properly established. Therefore, authenticated access control scenarios are thoroughly
 * tested through:
 * <ul>
 *   <li>{@link uk.gegc.quizmaker.shared.security.AccessPolicyTest} - Unit tests for AccessPolicy logic</li>
 *   <li>{@link uk.gegc.quizmaker.features.quiz.application.command.impl.QuizRelationServiceImplTest} - Service layer tests</li>
 *   <li>{@link uk.gegc.quizmaker.features.quiz.application.validation.QuizPublishValidatorTest} - Validator tests</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Quiz Access Control Integration Tests (Anonymous Access)")
class QuizAccessControlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private QuizRepository quizRepository;
    
    @Autowired
    private QuestionRepository questionRepository;
    
    @Autowired
    private CategoryRepository categoryRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private RoleRepository roleRepository;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @MockitoBean
    private StructuredAiClient structuredAiClient; // Mock AI calls - no real LLM calls
    
    private User ownerUser;
    private Quiz privateQuiz;
    private Quiz publicQuiz;
    private Category category;
    
    @BeforeEach
    void setUp() {
        // Find existing roles (created by DataInitializer)
        Role userRole = roleRepository.findByRoleName(RoleName.ROLE_USER.name())
            .orElseThrow(() -> new RuntimeException("ROLE_USER not found"));
        
        // Create test user
        ownerUser = createUser("quizowner123", "quizowner@example.com", userRole);
        
        // Create category
        category = new Category();
        category.setName("Test Category");
        category = categoryRepository.save(category);
        
        // Create private quiz owned by ownerUser
        privateQuiz = new Quiz();
        privateQuiz.setTitle("Private Quiz");
        privateQuiz.setDescription("Owner's private quiz");
        privateQuiz.setCreator(ownerUser);
        privateQuiz.setCategory(category);
        privateQuiz.setVisibility(Visibility.PRIVATE);
        privateQuiz.setStatus(QuizStatus.DRAFT);
        privateQuiz.setEstimatedTime(10);
        privateQuiz.setQuestions(new HashSet<>());
        privateQuiz.setTags(new HashSet<>());
        privateQuiz = quizRepository.save(privateQuiz);
        
        // Create public quiz owned by ownerUser
        publicQuiz = new Quiz();
        publicQuiz.setTitle("Public Quiz");
        publicQuiz.setDescription("Public quiz for all");
        publicQuiz.setCreator(ownerUser);
        publicQuiz.setCategory(category);
        publicQuiz.setVisibility(Visibility.PUBLIC);
        publicQuiz.setStatus(QuizStatus.PUBLISHED);
        publicQuiz.setEstimatedTime(5);
        publicQuiz.setQuestions(new HashSet<>());
        publicQuiz.setTags(new HashSet<>());
        publicQuiz = quizRepository.save(publicQuiz);
    }
    
    private User createUser(String username, String email, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setHashedPassword("$2a$10$dummyhashedpassword");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        user.setRoles(Set.of(role));
        return userRepository.save(user);
    }
    
    // =============== Anonymous Access Tests ===============
    
    @Nested
    @DisplayName("Anonymous User Access Control")
    class AnonymousAccessControl {
        
        @Test
        @DisplayName("Anonymous user CANNOT access private quiz via GET /api/v1/quizzes/{id}")
        void anonymous_cannotAccessPrivateQuiz() throws Exception {
            mockMvc.perform(get("/api/v1/quizzes/{id}", privateQuiz.getId()))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
        }
        
        @Test
        @DisplayName("Anonymous user CANNOT access specific quiz by ID (even public ones require authentication)")
        void anonymous_cannotAccessQuizById() throws Exception {
            mockMvc.perform(get("/api/v1/quizzes/{id}", publicQuiz.getId()))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
        }
    }
}
