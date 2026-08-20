package uk.gegc.quizmaker.features.quiz.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.infra.ExportMediaTypeResolver;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.SecurityConfig;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuizController.class)
@Import(SecurityConfig.class)
@DisplayName("Quiz generation status security")
class QuizGenerationStatusSecurityTest {

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

    @Test
    @DisplayName("Unauthenticated polling is rejected before generation data is read")
    void unauthenticatedPollingIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes/generation-status/{jobId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(quizService);
    }

    @Test
    @WithMockUser(username = USERNAME)
    @DisplayName("Authenticated users cannot poll another owner's generation coverage")
    void wrongOwnerPollingIsForbidden() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(quizService.getGenerationStatus(jobId, USERNAME))
                .thenThrow(new ForbiddenException("Access denied"));

        mockMvc.perform(get("/api/v1/quizzes/generation-status/{jobId}", jobId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verify(quizService).getGenerationStatus(jobId, USERNAME);
    }
}
