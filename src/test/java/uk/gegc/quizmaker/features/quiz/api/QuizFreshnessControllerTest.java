package uk.gegc.quizmaker.features.quiz.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfigurationSource;

import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.infra.security.CustomOAuth2UserService;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationFailureHandler;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationSuccessHandler;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthorizationRequestContextRepository;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2LoginAuthorizationRequestResolver;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.document.application.DocumentValidationService;
import uk.gegc.quizmaker.features.quiz.application.ModerationService;
import uk.gegc.quizmaker.features.quiz.application.QuizExportService;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizService;
import uk.gegc.quizmaker.features.quiz.application.imports.QuizImportService;
import uk.gegc.quizmaker.features.quiz.config.QuizImportProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizOwnerFreshnessRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.ExportMediaTypeResolver;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.SecurityConfig;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuizFreshnessController.class)
@Import({SecurityConfig.class, uk.gegc.quizmaker.features.quiz.application.impl.QuizFreshnessServiceImpl.class})
@DisplayName("Quiz freshness HTTP security")
class QuizFreshnessControllerTest {

    private static final String USERNAME = "generation-owner";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuizService quizService;

    @MockitoBean
    private AttemptService attemptService;

    @MockitoBean
    private DocumentProcessingService documentProcessingService;

    @MockitoBean
    private DocumentValidationService documentValidationService;

    @MockitoBean
    private QuizGenerationJobService quizGenerationJobService;

    @MockitoBean
    private QuizGenerationJobRepository quizGenerationJobRepository;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @MockitoBean
    private ModerationService moderationService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private QuizExportService quizExportService;

    @MockitoBean
    private ExportMediaTypeResolver exportMediaTypeResolver;

    @MockitoBean
    private QuizImportService quizImportService;

    @MockitoBean
    private QuizImportProperties quizImportProperties;

    @MockitoBean
    private AuthSessionService authSessionService;

    @MockitoBean
    private AuthSessionMetricsService authSessionMetricsService;

    @MockitoBean(name = "corsConfigurationSource")
    private CorsConfigurationSource corsConfigurationSource;

    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    @MockitoBean
    private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    @MockitoBean
    private OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;

    @MockitoBean
    private OAuth2LoginAuthorizationRequestResolver oAuth2LoginAuthorizationRequestResolver;

    @MockitoBean
    private OAuth2AuthorizationRequestContextRepository oAuth2AuthorizationRequestContextRepository;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockitoBean private QuizRepository quizRepository;
    @MockitoBean private QuizOwnerFreshnessRepository freshnessRepository;
    @MockitoBean private AppPermissionEvaluator permissionEvaluator;

    private final UUID quizId = UUID.randomUUID();
    private User owner;

    @BeforeEach
    void owner() {
        owner = new User();
        owner.setId(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(permissionEvaluator.getCurrentUser()).thenReturn(owner);
    }

    @Test
    void anonymousCannotAcknowledge() throws Exception {
        mockMvc.perform(post("/api/v1/quizzes/{id}/seen", quizId))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(quizRepository, freshnessRepository);
    }

    @Test
    @WithMockUser(authorities = "QUIZ_UPDATE")
    void editingPermissionAloneDoesNotGrantAcknowledgement() throws Exception {
        mockMvc.perform(post("/api/v1/quizzes/{id}/seen", quizId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(quizRepository, freshnessRepository);
    }

    @Test
    @WithMockUser(authorities = "QUIZ_READ")
    void readOnlyOwnerCanAcknowledgeRepeatedly() throws Exception {
        when(quizRepository.findOwnedByIdForUpdate(quizId, owner.getId())).thenReturn(Optional.of(new Quiz()));
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/quizzes/{id}/seen", quizId))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.quizId").value(quizId.toString()))
                    .andExpect(jsonPath("$.isNew").value(false));
        }
        verify(freshnessRepository, times(2)).acknowledge(quizId);
    }

    @Test
    @WithMockUser(authorities = {"QUIZ_READ", "QUIZ_MODERATE", "QUIZ_ADMIN"})
    void anotherOwnerAndMissingQuizAreIndistinguishableEvenForModerator() throws Exception {
        var other = mockMvc.perform(post("/api/v1/quizzes/{id}/seen", quizId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Quiz not found"))
                .andReturn().getResponse();
        var missing = mockMvc.perform(post("/api/v1/quizzes/{id}/seen", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Quiz not found"))
                .andReturn().getResponse();
        assertThat(other.getContentType()).isEqualTo(missing.getContentType());
        verifyNoInteractions(freshnessRepository);
    }

    @Test
    @WithMockUser(authorities = "QUIZ_READ")
    void malformedIdDoesNotReadStorage() throws Exception {
        mockMvc.perform(post("/api/v1/quizzes/not-a-uuid/seen"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(quizRepository, freshnessRepository);
    }

    @Test
    @WithMockUser(authorities = "QUIZ_READ")
    void storageFailureDoesNotReturnSuccessOrInternalDetails() throws Exception {
        when(quizRepository.findOwnedByIdForUpdate(quizId, owner.getId())).thenReturn(Optional.of(new Quiz()));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private database detail"))
                .when(freshnessRepository).acknowledge(quizId);
        String body = mockMvc.perform(post("/api/v1/quizzes/{id}/seen", quizId))
                .andExpect(status().is5xxServerError()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private database detail");
    }
}
