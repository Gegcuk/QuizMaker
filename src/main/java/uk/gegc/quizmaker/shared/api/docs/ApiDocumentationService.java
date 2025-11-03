package uk.gegc.quizmaker.shared.api.docs;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.shared.api.dto.ApiGroupSummary;
import uk.gegc.quizmaker.shared.api.dto.ApiSummary;

import java.util.List;

/**
 * Central place to describe the API documentation structure so we can reuse the
 * same metadata for both the JSON discovery endpoint and the human landing page.
 */
@Component
public class ApiDocumentationService {

    private static final String API_VERSION = "v1";
    private static final String BASE_URL = "/api/v1";
    private static final String FULL_SPEC_URL = "/v3/api-docs";
    private static final String FULL_DOCS_URL = "/swagger-ui/index.html";

    private static final List<ApiGroupSummary> GROUPS = List.of(
            ApiGroupSummary.of(
                    "auth",
                    "Authentication & Users",
                    "User authentication, registration, two-factor setup, and profile endpoints",
                    "🔐",
                    30
            ),
            ApiGroupSummary.of(
                    "quizzes",
                    "Quizzes & Questions",
                    "Create and manage quizzes, questions, tags, categories, and share links",
                    "📝",
                    60
            ),
            ApiGroupSummary.of(
                    "attempts",
                    "Quiz Attempts & Scoring",
                    "Lifecycle of quiz attempts, scoring, and participant progress tracking",
                    "🎯",
                    25
            ),
            ApiGroupSummary.of(
                    "documents",
                    "Document Processing",
                    "Document ingestion, parsing, AI analysis, and transcript retrieval",
                    "📄",
                    40
            ),
            ApiGroupSummary.of(
                    "billing",
                    "Billing & Payments",
                    "Billing configuration, balances, Stripe webhooks, and purchase flows",
                    "💳",
                    25
            ),
            ApiGroupSummary.of(
                    "ai",
                    "AI Features",
                    "AI-powered quiz generation, document analysis, and recommendation endpoints",
                    "🤖",
                    20
            ),
            ApiGroupSummary.of(
                    "admin",
                    "Administration",
                    "Administrative operations for managing organizations, roles, and system health",
                    "⚙️",
                    20
            )
    );

    public ApiSummary buildSummary() {
        return ApiSummary.builder()
                .version(API_VERSION)
                .baseUrl(BASE_URL)
                .groups(GROUPS)
                .fullSpecUrl(FULL_SPEC_URL)
                .fullDocsUrl(FULL_DOCS_URL)
                .build();
    }

    public List<ApiGroupSummary> listGroups() {
        return GROUPS;
    }
}

