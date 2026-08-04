package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionResponseParser;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.application.QuestionContentShuffler;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI quiz generation provider usage tracking")
class AiQuizGenerationServiceProviderUsageTest {

    @Mock private org.springframework.ai.chat.client.ChatClient chatClient;
    @Mock private DocumentRepository documentRepository;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private QuestionResponseParser questionResponseParser;
    @Mock private QuizGenerationJobRepository jobRepository;
    @Mock private UserRepository userRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AiRateLimitConfig rateLimitConfig;
    @Mock private InternalBillingService internalBillingService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private StructuredAiClient structuredAiClient;
    @Mock private QuestionContentShuffler questionContentShuffler;

    @InjectMocks
    private AiQuizGenerationServiceImpl service;

    @Test
    @DisplayName("provider usage accumulates separately and preserves legacy actual token values")
    void providerUsageAccumulatesSeparatelyAndPreservesLegacyActualTokenValues() {
        UUID jobId = UUID.randomUUID();
        QuizGenerationJob job = new QuizGenerationJob();
        job.setActualTokens(77L);
        job.setProviderLlmTokens(23L);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0, Consumer.class);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service.trackTokenUsage(jobId, 100L);

        assertThat(job.getProviderLlmTokens()).isEqualTo(123L);
        assertThat(job.getActualTokens()).isEqualTo(77L);
        verify(jobRepository).save(job);
    }
}
