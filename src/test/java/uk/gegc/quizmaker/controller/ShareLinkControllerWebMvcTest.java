package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionDto;
import uk.gegc.quizmaker.dto.attempt.AttemptResultDto;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptStatsDto;
import uk.gegc.quizmaker.features.attempt.api.dto.StartAttemptResponse;
import uk.gegc.quizmaker.features.quiz.api.dto.ShareLinkDto;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ShareLinkAlreadyUsedException;
import uk.gegc.quizmaker.features.quiz.api.ShareLinkController;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.quiz.domain.model.ShareLinkScope;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.quiz.application.ShareLinkService;
import uk.gegc.quizmaker.features.quiz.infra.web.ShareLinkCookieManager;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShareLinkController.class)
@AutoConfigureMockMvc(addFilters = false)
class ShareLinkControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ShareLinkService shareLinkService;

    @MockitoBean
    private ShareLinkCookieManager cookieManager;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @MockitoBean
    private AttemptService attemptService;

    @MockitoBean
    private UserRepository userRepository;

       private UUID quizId;
    private String token;
    private ShareLinkDto shareLink;

    @BeforeEach
    void setUp() {
        quizId = UUID.randomUUID();
        token = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; // 43 chars
        shareLink = new ShareLinkDto(
                UUID.randomUUID(),
                quizId,
                UUID.randomUUID(),
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false,
                null,
                Instant.now()
        );
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");
    }

    // ---- startAnonymousAttempt ----

    @Test
    @WithMockUser
    @DisplayName("Start anonymous attempt: success 201")
    void startAnonymousAttempt_success() throws Exception {
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        StartAttemptResponse start = new StartAttemptResponse(UUID.randomUUID(), quizId, AttemptMode.ALL_AT_ONCE, 1, null, Instant.now());
        when(attemptService.startAnonymousAttempt(eq(quizId), any(UUID.class), any(AttemptMode.class))).thenReturn(start);

        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attemptId").value(start.attemptId().toString()))
                .andExpect(jsonPath("$.quizId").value(quizId.toString()));

        verify(shareLinkService).validateToken(token);
        verify(attemptService).startAnonymousAttempt(eq(quizId), any(UUID.class), any(AttemptMode.class));
    }

    @Test
    @WithMockUser
    @DisplayName("Start anonymous attempt: invalid token -> 400")
    void startAnonymousAttempt_invalidToken_returns400() throws Exception {
        String bad = "short";
        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", bad)
                        .with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(shareLinkService, attemptService);
    }

    @Test
    @WithMockUser
    @DisplayName("Start anonymous attempt: rate limited -> 429 with Retry-After")
    void startAnonymousAttempt_rateLimited_returns429() throws Exception {
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        doThrow(new RateLimitExceededException("Too many", 42))
                .when(rateLimitService).checkRateLimit(anyString(), anyString(), anyInt());

        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"));
    }

    @Test
    @WithMockUser
    @DisplayName("Start anonymous attempt: token not found -> 404")
    void startAnonymousAttempt_tokenNotFound_returns404() throws Exception {
        when(shareLinkService.validateToken(token)).thenThrow(new ResourceNotFoundException("not found"));

        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Start anonymous attempt: token already used -> 410 Gone")
    void startAnonymousAttempt_tokenAlreadyUsed_returns410() throws Exception {
        when(shareLinkService.validateToken(token)).thenThrow(new ShareLinkAlreadyUsedException("used"));

        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .with(csrf()))
                .andExpect(status().isGone());
    }

    @Test
    @WithMockUser
    @DisplayName("Start anonymous attempt: default mode ALL_AT_ONCE when request body missing")
    void startAnonymousAttempt_defaultMode() throws Exception {
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        StartAttemptResponse start = new StartAttemptResponse(UUID.randomUUID(), quizId, AttemptMode.ALL_AT_ONCE, 5, null, Instant.now());
        when(attemptService.startAnonymousAttempt(eq(quizId), any(UUID.class), any(AttemptMode.class))).thenReturn(start);

        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .with(csrf()))
                .andExpect(status().isCreated());

        ArgumentCaptor<AttemptMode> modeCaptor = ArgumentCaptor.forClass(AttemptMode.class);
        verify(attemptService).startAnonymousAttempt(eq(quizId), any(UUID.class), modeCaptor.capture());
        assertThat(modeCaptor.getValue()).isEqualTo(AttemptMode.ALL_AT_ONCE);
    }

    @Test
    @WithMockUser
    @DisplayName("Start anonymous attempt: explicit ONE_BY_ONE mode")
    void startAnonymousAttempt_explicitMode() throws Exception {
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        StartAttemptResponse start = new StartAttemptResponse(UUID.randomUUID(), quizId, AttemptMode.ONE_BY_ONE, 3, 30, Instant.now());
        when(attemptService.startAnonymousAttempt(eq(quizId), any(UUID.class), any(AttemptMode.class))).thenReturn(start);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("mode", "ONE_BY_ONE");

        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("ONE_BY_ONE"));
    }

    @Test
    @DisplayName("Start anonymous attempt: unauthenticated allowed -> 201")
    void startAnonymousAttempt_unauthenticated_success() throws Exception {
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        StartAttemptResponse start = new StartAttemptResponse(UUID.randomUUID(), quizId, AttemptMode.ALL_AT_ONCE, 2, null, Instant.now());
        when(attemptService.startAnonymousAttempt(eq(quizId), any(UUID.class), any(AttemptMode.class))).thenReturn(start);

        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .with(csrf()))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser
    @DisplayName("Start anonymous attempt: invalid mode enum -> 400")
    void startAnonymousAttempt_invalidMode_returns400() throws Exception {
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        String payload = "{\"mode\":\"INVALID\"}";
        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Start anonymous attempt: unexpected server error -> 500")
    void startAnonymousAttempt_unexpectedServerError_returns500() throws Exception {
        when(shareLinkService.validateToken(token)).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .with(csrf()))
                .andExpect(status().isInternalServerError());
    }

    // ---- submitAnonymousAnswer ----

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answer: success 200")
    void submitAnonymousAnswer_success() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");

        AnswerSubmissionDto dto = new AnswerSubmissionDto(UUID.randomUUID(), UUID.randomUUID(), true, 1.0, Instant.now(), null);
        when(attemptService.submitAnswer(eq("anonymous"), eq(attemptId), any())).thenReturn(dto);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", UUID.randomUUID().toString());
        payload.set("response", objectMapper.createObjectNode().put("answer", true));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerId").value(dto.answerId().toString()))
                .andExpect(jsonPath("$.isCorrect").value(true));
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answers batch: success 200")
    void submitAnonymousAnswersBatch_success() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");

        AnswerSubmissionDto dto1 = new AnswerSubmissionDto(UUID.randomUUID(), UUID.randomUUID(), true, 1.0, Instant.now(), null);
        AnswerSubmissionDto dto2 = new AnswerSubmissionDto(UUID.randomUUID(), UUID.randomUUID(), false, 0.0, Instant.now(), null);
        when(attemptService.submitBatch(eq("anonymous"), eq(attemptId), any()))
                .thenReturn(java.util.List.of(dto1, dto2));

        ObjectNode a1 = objectMapper.createObjectNode();
        a1.put("questionId", UUID.randomUUID().toString());
        a1.set("response", objectMapper.createObjectNode().put("answer", true));
        ObjectNode a2 = objectMapper.createObjectNode();
        a2.put("questionId", UUID.randomUUID().toString());
        a2.set("response", objectMapper.createObjectNode().put("answer", false));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("answers", objectMapper.createArrayNode().add(a1).add(a2));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].answerId").value(dto1.answerId().toString()))
                .andExpect(jsonPath("$[1].answerId").value(dto2.answerId().toString()));
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answers batch: missing cookie -> 400")
    void submitAnonymousAnswersBatch_missingCookie_returns400() throws Exception {
        UUID attemptId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("answers", objectMapper.createArrayNode());

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answers batch: validation error -> 400")
    void submitAnonymousAnswersBatch_validationError_returns400() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("answers", objectMapper.createArrayNode()); // empty -> @NotEmpty

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answers batch: invalid token in cookie -> 400")
    void submitAnonymousAnswersBatch_invalidToken_returns400() throws Exception {
        UUID attemptId = UUID.randomUUID();
        String bad = "short";
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(bad));

        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode a1 = objectMapper.createObjectNode();
        a1.put("questionId", UUID.randomUUID().toString());
        a1.set("response", objectMapper.createObjectNode().put("answer", true));
        payload.set("answers", objectMapper.createArrayNode().add(a1));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", bad))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answers batch: token not found -> 404")
    void submitAnonymousAnswersBatch_tokenNotFound_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenThrow(new ResourceNotFoundException("not found"));

        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode a1 = objectMapper.createObjectNode();
        a1.put("questionId", UUID.randomUUID().toString());
        a1.set("response", objectMapper.createObjectNode().put("answer", true));
        payload.set("answers", objectMapper.createArrayNode().add(a1));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answers batch: attempt not found -> 404")
    void submitAnonymousAnswersBatch_attemptNotFound_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenThrow(new ResourceNotFoundException("Attempt not found"));

        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode a1 = objectMapper.createObjectNode();
        a1.put("questionId", UUID.randomUUID().toString());
        a1.set("response", objectMapper.createObjectNode().put("answer", true));
        payload.set("answers", objectMapper.createArrayNode().add(a1));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answers batch: rate limited -> 429")
    void submitAnonymousAnswersBatch_rateLimited_returns429() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        doThrow(new RateLimitExceededException("Too many", 33))
                .when(rateLimitService).checkRateLimit(anyString(), anyString(), anyInt());

        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode a1 = objectMapper.createObjectNode();
        a1.put("questionId", UUID.randomUUID().toString());
        a1.set("response", objectMapper.createObjectNode().put("answer", true));
        payload.set("answers", objectMapper.createArrayNode().add(a1));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "33"));
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answers batch: invalid questionId format -> 400")
    void submitAnonymousAnswersBatch_invalidUuid_returns400() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");

        ObjectNode a1 = objectMapper.createObjectNode();
        a1.put("questionId", "not-a-uuid");
        a1.set("response", objectMapper.createObjectNode().put("answer", true));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("answers", objectMapper.createArrayNode().add(a1));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answers batch: conflict -> 409")
    void submitAnonymousAnswersBatch_conflict_returns409() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        when(attemptService.submitBatch(eq("anonymous"), eq(attemptId), any()))
                .thenThrow(new IllegalStateException("Attempt not in progress"));

        ObjectNode a1 = objectMapper.createObjectNode();
        a1.put("questionId", UUID.randomUUID().toString());
        a1.set("response", objectMapper.createObjectNode().put("answer", true));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("answers", objectMapper.createArrayNode().add(a1));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answers batch: unexpected server error -> 500")
    void submitAnonymousAnswersBatch_unexpectedError_returns500() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        when(attemptService.submitBatch(eq("anonymous"), eq(attemptId), any()))
                .thenThrow(new RuntimeException("boom"));

        ObjectNode a1 = objectMapper.createObjectNode();
        a1.put("questionId", UUID.randomUUID().toString());
        a1.set("response", objectMapper.createObjectNode().put("answer", true));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("answers", objectMapper.createArrayNode().add(a1));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("Submit anonymous answers batch: unauthenticated allowed -> 200")
    void submitAnonymousAnswersBatch_unauthenticated_success() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        when(attemptService.submitBatch(eq("anonymous"), eq(attemptId), any()))
                .thenReturn(java.util.List.of());

        ObjectNode a1 = objectMapper.createObjectNode();
        a1.put("questionId", UUID.randomUUID().toString());
        a1.set("response", objectMapper.createObjectNode().put("answer", true));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("answers", objectMapper.createArrayNode().add(a1));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    @DisplayName("Complete anonymous attempt: success 200")
    void completeAnonymousAttempt_success() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");

        AttemptResultDto res = new AttemptResultDto(attemptId, quizId, null, Instant.now().minusSeconds(60), Instant.now(), 5.0, 5, 5, java.util.List.of());
        when(attemptService.completeAttempt(eq("anonymous"), eq(attemptId))).thenReturn(res);

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/complete", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.quizId").value(quizId.toString()));
    }

    @Test
    @WithMockUser
    @DisplayName("Complete anonymous attempt: missing cookie -> 400")
    void completeAnonymousAttempt_missingCookie_returns400() throws Exception {
        UUID attemptId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/complete", attemptId)
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Complete anonymous attempt: token not found -> 404")
    void completeAnonymousAttempt_tokenNotFound_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenThrow(new ResourceNotFoundException("not found"));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/complete", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Complete anonymous attempt: attempt not found -> 404")
    void completeAnonymousAttempt_attemptNotFound_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenThrow(new ResourceNotFoundException("Attempt not found"));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/complete", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Complete anonymous attempt: quiz mismatch -> 404")
    void completeAnonymousAttempt_quizMismatch_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/complete", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Complete anonymous attempt: rate limited -> 429")
    void completeAnonymousAttempt_rateLimited_returns429() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        doThrow(new RateLimitExceededException("Too many", 31))
                .when(rateLimitService).checkRateLimit(anyString(), anyString(), anyInt());

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/complete", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "31"));
    }

    @Test
    @WithMockUser
    @DisplayName("Complete anonymous attempt: conflict -> 409")
    void completeAnonymousAttempt_conflict_returns409() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        when(attemptService.completeAttempt(eq("anonymous"), eq(attemptId)))
                .thenThrow(new IllegalStateException("Already completed"));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/complete", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Complete anonymous attempt: unauthenticated allowed -> 200")
    void completeAnonymousAttempt_unauthenticated_success() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        when(attemptService.completeAttempt(eq("anonymous"), eq(attemptId)))
                .thenReturn(new AttemptResultDto(attemptId, quizId, null, Instant.now().minusSeconds(10), Instant.now(), 5.0, 5, 5, java.util.List.of()));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/complete", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    @DisplayName("Get anonymous attempt stats: success 200")
    void getAnonymousAttemptStats_success() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        AttemptStatsDto stats = new AttemptStatsDto(attemptId, java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(12), 5, 4, 80.0, 100.0, java.util.List.of(), Instant.now().minusSeconds(60), Instant.now());
        when(attemptService.getAttemptStats(attemptId)).thenReturn(stats);

        mockMvc.perform(get("/api/v1/quizzes/shared/attempts/{attemptId}/stats", attemptId)
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()));
    }

    @Test
    @WithMockUser
    @DisplayName("Get anonymous attempt stats: missing cookie -> 400")
    void getAnonymousAttemptStats_missingCookie_returns400() throws Exception {
        UUID attemptId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/quizzes/shared/attempts/{attemptId}/stats", attemptId))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Get anonymous attempt stats: token not found -> 404")
    void getAnonymousAttemptStats_tokenNotFound_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenThrow(new ResourceNotFoundException("not found"));

        mockMvc.perform(get("/api/v1/quizzes/shared/attempts/{attemptId}/stats", attemptId)
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Get anonymous attempt stats: attempt not found -> 404")
    void getAnonymousAttemptStats_attemptNotFound_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenThrow(new ResourceNotFoundException("Attempt not found"));

        mockMvc.perform(get("/api/v1/quizzes/shared/attempts/{attemptId}/stats", attemptId)
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Get anonymous attempt stats: quiz mismatch -> 404")
    void getAnonymousAttemptStats_quizMismatch_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(UUID.randomUUID());

        mockMvc.perform(get("/api/v1/quizzes/shared/attempts/{attemptId}/stats", attemptId)
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Get anonymous attempt stats: invalid token format -> 400")
    void getAnonymousAttemptStats_invalidTokenFormat_returns400() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of("bad-token")); // not 43 chars

        mockMvc.perform(get("/api/v1/quizzes/shared/attempts/{attemptId}/stats", attemptId)
                        .cookie(new jakarta.servlet.http.Cookie("share_token", "bad-token")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Get anonymous attempt stats: rate limited -> 429 with Retry-After")
    void getAnonymousAttemptStats_rateLimited_returns429() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        doThrow(new RateLimitExceededException("Too many", 17))
                .when(rateLimitService).checkRateLimit(anyString(), anyString(), anyInt());

        mockMvc.perform(get("/api/v1/quizzes/shared/attempts/{attemptId}/stats", attemptId)
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "17"));
    }

    @Test
    @WithMockUser
    @DisplayName("Get anonymous attempt stats: unexpected error -> 500")
    void getAnonymousAttemptStats_unexpectedError_returns500() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        when(attemptService.getAttemptStats(attemptId)).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/v1/quizzes/shared/attempts/{attemptId}/stats", attemptId)
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answers batch: quiz mismatch -> 404")
    void submitAnonymousAnswersBatch_quizMismatch_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(UUID.randomUUID());

        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode a1 = objectMapper.createObjectNode();
        a1.put("questionId", UUID.randomUUID().toString());
        a1.set("response", objectMapper.createObjectNode().put("answer", true));
        payload.set("answers", objectMapper.createArrayNode().add(a1));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers/batch", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answer: missing cookie -> 400")
    void submitAnonymousAnswer_missingCookie_returns400() throws Exception {
        UUID attemptId = UUID.randomUUID();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", UUID.randomUUID().toString());
        payload.set("response", objectMapper.createObjectNode().put("answer", true));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answer: invalid token in cookie -> 400")
    void submitAnonymousAnswer_invalidToken_returns400() throws Exception {
        UUID attemptId = UUID.randomUUID();
        String bad = "short";
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(bad));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", UUID.randomUUID().toString());
        payload.set("response", objectMapper.createObjectNode().put("answer", true));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", bad))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answer: token not found -> 404")
    void submitAnonymousAnswer_tokenNotFound_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenThrow(new ResourceNotFoundException("not found"));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", UUID.randomUUID().toString());
        payload.set("response", objectMapper.createObjectNode().put("answer", true));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answer: attempt not found -> 404")
    void submitAnonymousAnswer_attemptNotFound_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenThrow(new ResourceNotFoundException("Attempt not found"));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", UUID.randomUUID().toString());
        payload.set("response", objectMapper.createObjectNode().put("answer", true));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answer: quiz mismatch -> 404")
    void submitAnonymousAnswer_quizMismatch_returns404() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(UUID.randomUUID()); // different

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", UUID.randomUUID().toString());
        payload.set("response", objectMapper.createObjectNode().put("answer", true));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answer: rate limited -> 429")
    void submitAnonymousAnswer_rateLimited_returns429() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        doThrow(new RateLimitExceededException("Too many", 17))
                .when(rateLimitService).checkRateLimit(anyString(), anyString(), anyInt());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", UUID.randomUUID().toString());
        payload.set("response", objectMapper.createObjectNode().put("answer", true));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "17"));
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answer: validation error -> 400")
    void submitAnonymousAnswer_validationError_returns400() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(shareLinkService.hashToken(token)).thenReturn("hash");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.putNull("questionId"); // trigger @NotNull
        payload.set("response", objectMapper.createObjectNode().put("answer", true));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answer: invalid questionId format -> 400")
    void submitAnonymousAnswer_invalidQuestionIdFormat_returns400() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(shareLinkService.hashToken(token)).thenReturn("hash");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", "not-a-uuid");
        payload.set("response", objectMapper.createObjectNode().put("answer", true));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answer: unexpected server error -> 500")
    void submitAnonymousAnswer_unexpectedServerError_returns500() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        when(attemptService.submitAnswer(eq("anonymous"), eq(attemptId), any()))
                .thenThrow(new RuntimeException("boom"));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", UUID.randomUUID().toString());
        payload.set("response", objectMapper.createObjectNode().put("answer", true));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @WithMockUser
    @DisplayName("Submit anonymous answer: attempt not in progress -> 409")
    void submitAnonymousAnswer_conflict_returns409() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(cookieManager.getShareLinkToken(any())).thenReturn(Optional.of(token));
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        when(attemptService.getAttemptQuizId(attemptId)).thenReturn(quizId);
        when(attemptService.getAttemptShareLinkId(attemptId)).thenReturn(shareLink.id());
        when(shareLinkService.hashToken(token)).thenReturn("hash");
        when(attemptService.submitAnswer(eq("anonymous"), eq(attemptId), any()))
                .thenThrow(new IllegalStateException("Attempt not in progress"));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", UUID.randomUUID().toString());
        payload.set("response", objectMapper.createObjectNode().put("answer", true));

        mockMvc.perform(post("/api/v1/quizzes/shared/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("share_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isConflict());
    }
}


