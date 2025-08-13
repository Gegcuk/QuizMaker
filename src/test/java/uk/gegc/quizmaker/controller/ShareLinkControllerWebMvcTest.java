package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.dto.attempt.AnswerSubmissionDto;
import uk.gegc.quizmaker.dto.attempt.StartAttemptResponse;
import uk.gegc.quizmaker.dto.quiz.ShareLinkDto;
import uk.gegc.quizmaker.exception.RateLimitExceededException;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.exception.ShareLinkAlreadyUsedException;
import uk.gegc.quizmaker.model.attempt.AttemptMode;
import uk.gegc.quizmaker.model.quiz.ShareLinkScope;
import uk.gegc.quizmaker.service.RateLimitService;
import uk.gegc.quizmaker.service.attempt.AttemptService;
import uk.gegc.quizmaker.service.quiz.ShareLinkService;
import uk.gegc.quizmaker.util.ShareLinkCookieManager;
import uk.gegc.quizmaker.util.TrustedProxyUtil;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShareLinkController.class)
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
        when(attemptService.startAnonymousAttempt(eq(quizId), any())).thenReturn(start);

        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attemptId").value(start.attemptId().toString()))
                .andExpect(jsonPath("$.quizId").value(quizId.toString()));

        verify(shareLinkService).validateToken(token);
        verify(attemptService).startAnonymousAttempt(eq(quizId), any());
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
        when(attemptService.startAnonymousAttempt(eq(quizId), any())).thenReturn(start);

        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/attempts", token)
                        .with(csrf()))
                .andExpect(status().isCreated());

        ArgumentCaptor<AttemptMode> modeCaptor = ArgumentCaptor.forClass(AttemptMode.class);
        verify(attemptService).startAnonymousAttempt(eq(quizId), modeCaptor.capture());
        assertThat(modeCaptor.getValue()).isEqualTo(AttemptMode.ALL_AT_ONCE);
    }

    @Test
    @WithMockUser
    @DisplayName("Start anonymous attempt: explicit ONE_BY_ONE mode")
    void startAnonymousAttempt_explicitMode() throws Exception {
        when(shareLinkService.validateToken(token)).thenReturn(shareLink);
        StartAttemptResponse start = new StartAttemptResponse(UUID.randomUUID(), quizId, AttemptMode.ONE_BY_ONE, 3, 30, Instant.now());
        when(attemptService.startAnonymousAttempt(eq(quizId), any())).thenReturn(start);

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


