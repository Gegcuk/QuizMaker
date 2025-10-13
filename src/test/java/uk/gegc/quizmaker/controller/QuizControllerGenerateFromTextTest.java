package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.billing.domain.model.Balance;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentChunkRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Quiz Controller Generate From Text Tests")
class QuizControllerGenerateFromTextTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository jobRepository;

    private GenerateQuizFromTextRequest validRequest;

    @BeforeEach
    void setUp() {
        // Clean up existing data first - need to handle foreign key constraints
        // Use deleteAllInBatch() to bypass optimistic locking checks (avoids issues with stale entities)
        // Delete generation jobs first as they reference users (FK on username)
        jobRepository.deleteAllInBatch();
        // Then balances (may reference users)
        balanceRepository.deleteAllInBatch();
        // Delete document chunks before documents (FK constraint)
        documentChunkRepository.deleteAllInBatch();
        documentRepository.deleteAllInBatch();
        // Finally delete users and roles/permissions
        userRepository.deleteAllInBatch();
        roleRepository.deleteAllInBatch();
        permissionRepository.deleteAllInBatch();

        // Create billing permissions
        Permission billingReadPermission = new Permission();
        billingReadPermission.setPermissionName(PermissionName.BILLING_READ.name());
        billingReadPermission = permissionRepository.save(billingReadPermission);
        
        Permission billingWritePermission = new Permission();
        billingWritePermission.setPermissionName(PermissionName.BILLING_WRITE.name());
        billingWritePermission = permissionRepository.save(billingWritePermission);

        Permission quizCreatePermission = new Permission();
        quizCreatePermission.setPermissionName(PermissionName.QUIZ_CREATE.name());
        quizCreatePermission = permissionRepository.save(quizCreatePermission);

        // Create admin role with necessary permissions
        Role adminRole = new Role();
        adminRole.setRoleName(RoleName.ROLE_ADMIN.name());
        adminRole.setPermissions(Set.of(billingReadPermission, billingWritePermission, quizCreatePermission));
        adminRole = roleRepository.save(adminRole);

        // Create regular user role without QUIZ_CREATE permission
        Role userRole = new Role();
        userRole.setRoleName(RoleName.ROLE_USER.name());
        userRole.setPermissions(Set.of(billingReadPermission)); // Only billing read, no quiz create
        userRole = roleRepository.save(userRole);

        // Create the test user with admin role
        User testUser = new User();
        testUser.setUsername("testuser");
        testUser.setEmail("testuser@example.com");
        testUser.setHashedPassword("hashedPassword");
        testUser.setActive(true);
        testUser.setDeleted(false);
        testUser.setRoles(Set.of(adminRole));
        testUser = userRepository.save(testUser);

        // Create the regular user without admin privileges
        User regularUser = new User();
        regularUser.setUsername("regularuser");
        regularUser.setEmail("regularuser@example.com");
        regularUser.setHashedPassword("hashedPassword");
        regularUser.setActive(true);
        regularUser.setDeleted(false);
        regularUser.setRoles(Set.of(userRole));
        regularUser = userRepository.save(regularUser);

        // Create balance with sufficient tokens for the admin test user
        Balance adminBalance = new Balance();
        adminBalance.setUserId(testUser.getId());
        adminBalance.setAvailableTokens(100000L); // Enough tokens for quiz generation
        adminBalance.setReservedTokens(0L);
        adminBalance.setUpdatedAt(LocalDateTime.now());
        balanceRepository.save(adminBalance);

        // Create balance for regular user (they'll still get 403 due to missing permission)
        Balance regularBalance = new Balance();
        regularBalance.setUserId(regularUser.getId());
        regularBalance.setAvailableTokens(100000L);
        regularBalance.setReservedTokens(0L);
        regularBalance.setUpdatedAt(LocalDateTime.now());
        balanceRepository.save(regularBalance);

        // Set up security context with the real user and proper authorities
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("BILLING_READ"),
                new SimpleGrantedAuthority("BILLING_WRITE"),
                new SimpleGrantedAuthority("QUIZ_CREATE"),
                new SimpleGrantedAuthority("QUIZ_READ"),
                new SimpleGrantedAuthority("QUIZ_UPDATE"),
                new SimpleGrantedAuthority("QUIZ_DELETE"),
                new SimpleGrantedAuthority("QUIZ_PUBLISH"),
                new SimpleGrantedAuthority("QUIZ_MODERATE"),
                new SimpleGrantedAuthority("QUIZ_ADMIN")
        );
        
        UsernamePasswordAuthenticationToken authentication = 
                new UsernamePasswordAuthenticationToken(testUser.getUsername(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        validRequest = new GenerateQuizFromTextRequest(
                "Chapter 1: Introduction to Programming\n\nThis chapter covers the fundamentals of programming. Programming is the process of creating instructions that tell a computer how to perform a task. There are many programming languages available, each with its own strengths and weaknesses.\n\n1.1 What is Programming?\nProgramming involves writing code in a specific language that a computer can understand and execute. The code consists of instructions that tell the computer what to do.\n\n1.2 Programming Languages\nSome popular programming languages include Java, Python, C++, and JavaScript. Each language has its own syntax and rules for writing code.\n\nChapter 2: Variables and Data Types\n\nIn programming, variables are used to store data. Different types of data can be stored in variables, such as numbers, text, and boolean values.\n\n2.1 Understanding Variables\nA variable is a named storage location in memory that holds a value. The value can be changed during program execution.\n\n2.2 Common Data Types\nInteger variables store whole numbers, while string variables store text. Boolean variables store true or false values.",
                "en",
                null, // chunkingStrategy - will use default
                null, // maxChunkSize - will use default
                null, // quizScope - will use default
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test Quiz from Text", // quizTitle
                "Test Description for text-based quiz", // quizDescription
                Map.of(QuestionType.MCQ_SINGLE, 3, QuestionType.TRUE_FALSE, 2), // questionsPerType
                Difficulty.MEDIUM, // difficulty
                2, // estimatedTimePerQuestion
                null, // categoryId
                null  // tagIds
        );
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 202 Accepted for valid request")
    void generateFromText_ValidRequest_Returns202Accepted() throws Exception {
        // Get the test user from database
        User testUser = userRepository.findByUsername("testuser")
                .orElseThrow(() -> new RuntimeException("Test user not found"));
        
        // Create authorities that match the ROLE_ADMIN role
        // ROLE_ADMIN has 49 permissions including BILLING_READ, BILLING_WRITE, QUIZ_CREATE, etc.
        SimpleGrantedAuthority adminRole = new SimpleGrantedAuthority("ROLE_ADMIN");
        SimpleGrantedAuthority billingReadAuthority = new SimpleGrantedAuthority("BILLING_READ");
        SimpleGrantedAuthority billingWriteAuthority = new SimpleGrantedAuthority("BILLING_WRITE");
        SimpleGrantedAuthority quizCreateAuthority = new SimpleGrantedAuthority("QUIZ_CREATE");
        
        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest))
                        .with(user(testUser.getUsername())
                                .authorities(adminRole, billingReadAuthority, billingWriteAuthority, quizCreateAuthority)))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jobId").exists())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.message").value("Quiz generation started successfully"))
                .andExpect(jsonPath("$.estimatedTimeSeconds").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 400 for empty text")
    @WithMockUser(username = "testuser", roles = "ADMIN")
    void generateFromText_EmptyText_Returns400BadRequest() throws Exception {
        // Given
        GenerateQuizFromTextRequest invalidRequest = new GenerateQuizFromTextRequest(
                "", // empty text
                "en",
                null,
                null,
                null,
                null,
                null,
                null,
                "Test Quiz",
                "Test Description",
                Map.of(QuestionType.MCQ_SINGLE, 3),
                Difficulty.MEDIUM,
                2,
                null,
                null
        );

        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 400 for text exceeding 300k characters")
    @WithMockUser(username = "testuser", roles = "ADMIN")
    void generateFromText_TextTooLong_Returns400BadRequest() throws Exception {
        // Given
        String longText = "A".repeat(300001); // 300,001 characters
        GenerateQuizFromTextRequest invalidRequest = new GenerateQuizFromTextRequest(
                longText,
                "en",
                null,
                null,
                null,
                null,
                null,
                null,
                "Test Quiz",
                "Test Description",
                Map.of(QuestionType.MCQ_SINGLE, 3),
                Difficulty.MEDIUM,
                2,
                null,
                null
        );

        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 400 for missing questionsPerType")
    @WithMockUser(username = "testuser", roles = "ADMIN")
    void generateFromText_MissingQuestionsPerType_Returns400BadRequest() throws Exception {
        // Given
        GenerateQuizFromTextRequest invalidRequest = new GenerateQuizFromTextRequest(
                "Sample text",
                "en",
                null,
                null,
                null,
                null,
                null,
                null,
                "Test Quiz",
                "Test Description",
                null, // missing questionsPerType
                Difficulty.MEDIUM,
                2,
                null,
                null
        );

        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 400 for missing difficulty")
    @WithMockUser(username = "testuser", roles = "ADMIN")
    void generateFromText_MissingDifficulty_Returns400BadRequest() throws Exception {
        // Given
        GenerateQuizFromTextRequest invalidRequest = new GenerateQuizFromTextRequest(
                "Sample text",
                "en",
                null,
                null,
                null,
                null,
                null,
                null,
                "Test Quiz",
                "Test Description",
                Map.of(QuestionType.MCQ_SINGLE, 3),
                null, // missing difficulty
                2,
                null,
                null
        );

        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 202 for user with QUIZ_CREATE permission")
    @WithMockUser(username = "regularuser", roles = "USER")
    void generateFromText_UserWithQuizCreatePermission_Returns202Accepted() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/generate-from-text should return 403 for unauthenticated user")
    void generateFromText_UnauthenticatedUser_Returns403Forbidden() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest))
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }
}
