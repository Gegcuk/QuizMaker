package uk.gegc.quizmaker.shared.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for API documentation endpoints.
 * Tests Phases 1-7 of the API Documentation Structure implementation.
 * 
 * Uses TestRestTemplate with real embedded server to test SpringDoc-generated endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("API Documentation")
class ApiDocumentationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ============================================================================
    // Phase 1-2: API Groups Configuration
    // ============================================================================

    @Test
    @DisplayName("GET /v3/api-docs: full OpenAPI spec available")
    void fullSpec_available() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("openapi")).isTrue();
        assertThat(json.get("openapi").asText()).startsWith("3.");
        assertThat(json.has("paths")).isTrue();
    }

    @Test
    @DisplayName("GET /v3/api-docs?group=auth: auth group spec available")
    void authGroupSpec_available() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs?group=auth", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("paths")).isTrue();
        // Auth group should contain auth and user endpoints
        assertThat(response.getBody()).contains("/api/v1/auth/");
    }

    @Test
    @DisplayName("GET /v3/api-docs?group=quizzes: quizzes group spec available")
    void quizzesGroupSpec_available() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs?group=quizzes", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("paths")).isTrue();
    }

    @Test
    @DisplayName("GET /v3/api-docs?group=questions: questions group spec available")
    void questionsGroupSpec_available() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs?group=questions", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("paths")).isTrue();
        // Should contain question endpoints including schemas
        assertThat(response.getBody()).contains("/api/v1/questions/schemas");
    }

    @Test
    @DisplayName("GET /v3/api-docs?group=attempts: attempts group spec available")
    void attemptsGroupSpec_available() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs?group=attempts", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("paths")).isTrue();
    }

    @Test
    @DisplayName("GET /v3/api-docs?group=documents: documents group spec available")
    void documentsGroupSpec_available() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs?group=documents", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("paths")).isTrue();
    }

    @Test
    @DisplayName("GET /v3/api-docs?group=billing: billing group spec available")
    void billingGroupSpec_available() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs?group=billing", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("paths")).isTrue();
    }

    @Test
    @DisplayName("GET /v3/api-docs?group=ai: ai group spec available")
    void aiGroupSpec_available() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs?group=ai", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("paths")).isTrue();
    }

    @Test
    @DisplayName("GET /v3/api-docs?group=admin: admin group spec available")
    void adminGroupSpec_available() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs?group=admin", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("paths")).isTrue();
    }

    // ============================================================================
    // Phase 3: API Discovery Controller
    // ============================================================================

    @Test
    @DisplayName("GET /api/v1/api-summary: returns lightweight API summary")
    void apiSummary_returnsJson() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/api-summary", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.get("version").asText()).isEqualTo("v1");
        assertThat(json.get("baseUrl").asText()).isEqualTo("/api/v1");
        assertThat(json.get("fullSpecUrl").asText()).isEqualTo("/v3/api-docs");
        assertThat(json.get("fullDocsUrl").asText()).isEqualTo("/swagger-ui/index.html");
        assertThat(json.get("groups").isArray()).isTrue();
        assertThat(json.get("groups").size()).isEqualTo(9);
    }

    @Test
    @DisplayName("GET /api/v1/api-summary: groups have correct structure")
    void apiSummary_groupsHaveCorrectStructure() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/api-summary", String.class);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        JsonNode firstGroup = json.get("groups").get(0);
        
        assertThat(firstGroup.has("group")).isTrue();
        assertThat(firstGroup.has("displayName")).isTrue();
        assertThat(firstGroup.has("description")).isTrue();
        assertThat(firstGroup.has("icon")).isTrue();
        assertThat(firstGroup.has("specUrl")).isTrue();
        assertThat(firstGroup.has("docsUrl")).isTrue();
        assertThat(firstGroup.has("estimatedSizeKB")).isTrue();
    }

    @Test
    @DisplayName("GET /api/v1/api-summary: includes all expected groups")
    void apiSummary_includesAllGroups() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/api-summary", String.class);
        
        assertThat(response.getBody())
                .contains("\"group\":\"auth\"")
                .contains("\"group\":\"quizzes\"")
                .contains("\"group\":\"questions\"")
                .contains("\"group\":\"attempts\"")
                .contains("\"group\":\"documents\"")
                .contains("\"group\":\"billing\"")
                .contains("\"group\":\"articles\"")
                .contains("\"group\":\"ai\"")
                .contains("\"group\":\"admin\"");
    }

    @Test
    @DisplayName("GET /api/v1/api-summary: has cache control headers")
    void apiSummary_hasCacheControl() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/api-summary", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isNotNull();
        assertThat(response.getHeaders().getCacheControl()).contains("max-age");
    }

    // ============================================================================
    // Phase 4: Landing Page
    // ============================================================================

    @Test
    @DisplayName("GET /api/v1/docs: returns HTML landing page")
    void landingPage_returnsHtml() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/docs", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/html");
        assertThat(response.getBody())
                .contains("QuizMaker API Documentation")
                .containsAnyOf("Authentication & Users", "Authentication &amp; Users") // HTML entity or plain
                .contains("Quizzes") // Now just "Quizzes", not "Quizzes & Questions"
                .contains("Questions") // Separate group
                .containsAnyOf("Quiz Attempts & Scoring", "Quiz Attempts &amp; Scoring");
    }

    @Test
    @DisplayName("GET /api/v1/docs: contains links to grouped Swagger UIs")
    void landingPage_containsSwaggerLinks() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/docs", String.class);
        
        assertThat(response.getBody())
                .contains("/swagger-ui/index.html")
                .contains("/v3/api-docs");
    }

    @Test
    @DisplayName("GET /api/v1/docs: contains all group cards")
    void landingPage_containsAllGroupCards() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/docs", String.class);
        
        assertThat(response.getBody())
                .contains("🔐") // auth icon
                .contains("📝") // quizzes icon  
                .contains("❓") // questions icon
                .contains("🎯") // attempts icon
                .contains("📄") // documents icon
                .contains("💳") // billing icon
                .contains("📰") // articles icon
                .contains("🤖") // ai icon
                .contains("⚙️"); // admin icon
    }

    // ============================================================================
    // Phase 5: Security Configuration
    // ============================================================================

    @Test
    @DisplayName("Security: all documentation endpoints are public")
    void security_documentationIsPublic() {
        // No authentication needed for any of these
        assertThat(restTemplate.getForEntity("/v3/api-docs", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/api/v1/api-summary", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/api/v1/docs", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ============================================================================
    // Appendix D: Question Schema Endpoints
    // ============================================================================

    @Test
    @DisplayName("GET /api/v1/questions/schemas: returns all question type schemas")
    void questionSchemas_returnsAllTypes() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/questions/schemas", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("MCQ_SINGLE")).isTrue();
        assertThat(json.has("MCQ_MULTI")).isTrue();
        assertThat(json.has("TRUE_FALSE")).isTrue();
        assertThat(json.has("OPEN")).isTrue();
        assertThat(json.has("FILL_GAP")).isTrue();
        assertThat(json.has("ORDERING")).isTrue();
        assertThat(json.has("MATCHING")).isTrue();
        assertThat(json.has("HOTSPOT")).isTrue();
        assertThat(json.has("COMPLIANCE")).isTrue();
    }

    @Test
    @DisplayName("GET /api/v1/questions/schemas: each type has schema, example, and description")
    void questionSchemas_haveCompleteStructure() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/questions/schemas", String.class);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        JsonNode mcqSingle = json.get("MCQ_SINGLE");
        
        assertThat(mcqSingle.has("schema")).isTrue();
        assertThat(mcqSingle.has("example")).isTrue();
        assertThat(mcqSingle.has("description")).isTrue();
        
        JsonNode trueFalse = json.get("TRUE_FALSE");
        assertThat(trueFalse.has("schema")).isTrue();
        assertThat(trueFalse.has("example")).isTrue();
        assertThat(trueFalse.has("description")).isTrue();
    }

    @Test
    @DisplayName("GET /api/v1/questions/schemas/MCQ_SINGLE: returns specific schema")
    void questionSchema_mcqSingle_returnsSchema() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/questions/schemas/MCQ_SINGLE", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("schema")).isTrue();
        assertThat(json.has("example")).isTrue();
        assertThat(json.has("description")).isTrue();
        
        // Verify example has full question structure
        JsonNode example = json.get("example");
        assertThat(example.has("questionText")).isTrue();
        assertThat(example.has("type")).isTrue();
        assertThat(example.has("difficulty")).isTrue();
        assertThat(example.has("content")).isTrue();
        assertThat(example.has("hint")).isTrue();
        assertThat(example.has("explanation")).isTrue();
        
        // Verify content has options array with 4 items
        JsonNode content = example.get("content");
        assertThat(content.has("options")).isTrue();
        assertThat(content.get("options").isArray()).isTrue();
        assertThat(content.get("options").size()).isEqualTo(4);
        
        // Verify description mentions 4 options
        assertThat(json.get("description").asText()).contains("4 options");
    }

    @Test
    @DisplayName("GET /api/v1/questions/schemas/TRUE_FALSE: returns specific schema")
    void questionSchema_trueFalse_returnsSchema() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/questions/schemas/TRUE_FALSE", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("schema")).isTrue();
        assertThat(json.has("example")).isTrue();
        
        // Verify example has full question structure
        JsonNode example = json.get("example");
        assertThat(example.has("questionText")).isTrue();
        assertThat(example.has("content")).isTrue();
        assertThat(example.has("hint")).isTrue();
        assertThat(example.has("explanation")).isTrue();
        
        // Verify content has boolean answer
        JsonNode content = example.get("content");
        assertThat(content.has("answer")).isTrue();
        assertThat(content.get("answer").isBoolean()).isTrue();
        
        assertThat(json.get("description").asText()).contains("True or False");
    }

    @Test
    @DisplayName("GET /api/v1/questions/schemas/FILL_GAP: returns specific schema")
    void questionSchema_fillGap_returnsSchema() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/questions/schemas/FILL_GAP", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        JsonNode example = json.get("example");
        assertThat(example.has("questionText")).isTrue();
        assertThat(example.has("content")).isTrue();
        assertThat(example.has("hint")).isTrue();
        assertThat(example.has("explanation")).isTrue();
        
        // Verify content has text and gaps
        JsonNode content = example.get("content");
        assertThat(content.has("text")).isTrue();
        assertThat(content.has("gaps")).isTrue();
        assertThat(content.get("gaps").isArray()).isTrue();
    }

    @Test
    @DisplayName("GET /api/v1/questions/schemas/MATCHING: returns specific schema")
    void questionSchema_matching_returnsSchema() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/questions/schemas/MATCHING", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        JsonNode example = json.get("example");
        assertThat(example.has("questionText")).isTrue();
        assertThat(example.has("content")).isTrue();
        assertThat(example.has("hint")).isTrue();
        assertThat(example.has("explanation")).isTrue();
        
        // Verify content has left and right arrays
        JsonNode content = example.get("content");
        assertThat(content.has("left")).isTrue();
        assertThat(content.has("right")).isTrue();
        assertThat(content.get("left").isArray()).isTrue();
        assertThat(content.get("right").isArray()).isTrue();
    }

    @Test
    @DisplayName("GET /api/v1/questions/schemas/COMPLIANCE: returns specific schema")
    void questionSchema_compliance_returnsSchema() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/questions/schemas/COMPLIANCE", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        JsonNode json = objectMapper.readTree(response.getBody());
        JsonNode example = json.get("example");
        assertThat(example.has("questionText")).isTrue();
        assertThat(example.has("content")).isTrue();
        assertThat(example.has("hint")).isTrue();
        assertThat(example.has("explanation")).isTrue();
        
        // Verify content has statements array
        JsonNode content = example.get("content");
        assertThat(content.has("statements")).isTrue();
        assertThat(content.get("statements").isArray()).isTrue();
        assertThat(content.get("statements").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("GET /api/v1/questions/schemas: is publicly accessible without auth")
    void questionSchemas_isPublic() {
        // Should work without authentication
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/questions/schemas", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ============================================================================
    // Backward Compatibility & Swagger UI
    // ============================================================================

    @Test
    @DisplayName("Swagger UI: accessible at default path")
    void swaggerUi_accessible() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/html");
    }

    @Test
    @DisplayName("Swagger UI: old path redirects or requires auth")
    void swaggerUi_oldPath_redirects() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui.html", String.class);
        // Should redirect, return OK, or require authentication (depends on Spring Boot version and security config)
        assertThat(response.getStatusCode()).isIn(
                HttpStatus.OK, 
                HttpStatus.MOVED_PERMANENTLY, 
                HttpStatus.FOUND,
                HttpStatus.UNAUTHORIZED // Fixed: Returns 401 for unauthenticated access
        );
    }
}
