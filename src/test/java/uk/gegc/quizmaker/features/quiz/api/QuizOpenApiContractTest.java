package uk.gegc.quizmaker.features.quiz.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.document.application.DocumentValidationService;
import uk.gegc.quizmaker.features.quiz.api.dto.BulkQuizUpdateOperationResultDto;
import uk.gegc.quizmaker.features.quiz.api.dto.BulkQuizUpdateRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationJobPageResponse;
import uk.gegc.quizmaker.features.quiz.api.dto.ShareLinkDto;
import uk.gegc.quizmaker.features.quiz.application.ModerationService;
import uk.gegc.quizmaker.features.quiz.application.QuizExportService;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizService;
import uk.gegc.quizmaker.features.quiz.application.ShareLinkService;
import uk.gegc.quizmaker.features.quiz.application.imports.QuizImportService;
import uk.gegc.quizmaker.features.quiz.config.QuizImportProperties;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.infra.ExportMediaTypeResolver;
import uk.gegc.quizmaker.features.quiz.infra.web.ShareLinkCookieManager;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {QuizController.class, ShareLinkController.class})
@Import({
        OpenApiConfig.class,
        OpenApiGroupConfig.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        QuizOpenApiContractTest.SpringDocTestConfig.class
})
class QuizOpenApiContractTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class SpringDocTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private ShareLinkService shareLinkService;

    @MockitoBean
    private ShareLinkCookieManager shareLinkCookieManager;

    @Test
    @WithMockUser
    @DisplayName("GET /v3/api-docs/quizzes publishes typed lifecycle collection and bulk-update contracts")
    void quizzesOpenApiContract() throws Exception {
        JsonNode specification = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/quizzes"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(specification.path("paths").has("/api/v1/quizzes/share-links")).isTrue();
        assertThat(specification.path("paths").has("/api/v1/quizzes/generation-jobs")).isTrue();
        assertThat(specification.path("paths").has("/api/v1/quizzes/bulk-update")).isTrue();
        assertThat(specification.at("/security/0/bearerAuth").isArray()).isTrue();

        JsonNode shareLinksSchema = specification.at("/paths/~1api~1v1~1quizzes~1share-links/get/responses/200/content/application~1json/schema");
        assertThat(shareLinksSchema.path("type").asText()).isEqualTo("array");
        assertThat(shareLinksSchema.path("items").path("$ref").asText()).isEqualTo("#/components/schemas/ShareLinkDto");
        assertResponseDocumented(specification, "/paths/~1api~1v1~1quizzes~1share-links/get/responses/401");
        List<ShareLinkDto> shareLinks = strictObjectMapper().readerForListOf(ShareLinkDto.class).readValue(
                specification.at("/paths/~1api~1v1~1quizzes~1share-links/get/responses/200/content/application~1json/examples/User share links/value")
        );
        assertThat(shareLinks).hasSize(1);
        assertThat(shareLinks.get(0).oneTime()).isFalse();

        assertThat(specification.at("/paths/~1api~1v1~1quizzes~1generation-jobs/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/QuizGenerationJobPageResponse");
        assertThat(specification.at("/components/schemas/QuizGenerationJobPageResponse/properties/content/items/$ref").asText())
                .isEqualTo("#/components/schemas/QuizGenerationStatus");
        assertResponseDocumented(specification, "/paths/~1api~1v1~1quizzes~1generation-jobs/get/responses/401");
        assertThat(specification.at("/components/schemas/QuizGenerationStatus/properties/status/enum").toString())
                .contains("PENDING", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED");
        assertThat(specification.at("/components/schemas/QuizGenerationStatus/properties/terminal/description").asText())
                .contains("no longer active");
        assertThat(specification.at("/components/schemas/QuizGenerationStatus/properties/active/description").asText())
                .contains("pending or processing");
        QuizGenerationJobPageResponse generationJobs = strictObjectMapper().treeToValue(
                specification.at("/paths/~1api~1v1~1quizzes~1generation-jobs/get/responses/200/content/application~1json/examples/Processing generation job/value"),
                QuizGenerationJobPageResponse.class
        );
        assertThat(generationJobs.content()).hasSize(1);
        assertThat(generationJobs.content().get(0).status().name()).isEqualTo("PROCESSING");
        assertThat(generationJobs.pageable().pageSize()).isEqualTo(20);

        assertThat(specification.at("/paths/~1api~1v1~1quizzes~1bulk-update/patch/requestBody/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/BulkQuizUpdateRequest");
        assertThat(specification.at("/components/schemas/BulkQuizUpdateRequest/properties/update").toString())
                .contains("#/components/schemas/UpdateQuizRequest");
        assertThat(specification.at("/paths/~1api~1v1~1quizzes~1bulk-update/patch/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/BulkQuizUpdateOperationResultDto");
        assertThat(specification.at("/components/schemas/BulkQuizUpdateOperationResultDto/properties/failures/additionalProperties/type").asText())
                .isEqualTo("string");
        assertResponseDocumented(specification, "/paths/~1api~1v1~1quizzes~1bulk-update/patch/responses/400");
        assertResponseDocumented(specification, "/paths/~1api~1v1~1quizzes~1bulk-update/patch/responses/403");

        BulkQuizUpdateRequest bulkRequest = strictObjectMapper().treeToValue(
                specification.at("/paths/~1api~1v1~1quizzes~1bulk-update/patch/requestBody/content/application~1json/examples/Update quiz visibility and timer/value"),
                BulkQuizUpdateRequest.class
        );
        assertThat(bulkRequest.quizIds()).hasSize(2);
        assertThat(bulkRequest.update().timerEnabled()).isFalse();

        BulkQuizUpdateOperationResultDto bulkResult = strictObjectMapper().treeToValue(
                specification.at("/paths/~1api~1v1~1quizzes~1bulk-update/patch/responses/200/content/application~1json/examples/Partial success/value"),
                BulkQuizUpdateOperationResultDto.class
        );
        assertThat(bulkResult.successfulIds()).hasSize(1);
        assertThat(bulkResult.failures()).hasSize(1);
    }

    private ObjectMapper strictObjectMapper() {
        return objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private void assertResponseDocumented(JsonNode specification, String responsePointer) {
        assertThat(specification.at(responsePointer).isMissingNode()).isFalse();
    }
}
