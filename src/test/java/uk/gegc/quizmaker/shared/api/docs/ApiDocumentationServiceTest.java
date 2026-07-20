package uk.gegc.quizmaker.shared.api.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.shared.api.dto.ApiGroupSummary;

import static org.assertj.core.api.Assertions.assertThat;

class ApiDocumentationServiceTest {

    private final ApiDocumentationService service = new ApiDocumentationService();

    @Test
    @DisplayName("API discovery lists the media OpenAPI group")
    void listGroups_includesMediaContract() {
        ApiGroupSummary media = service.listGroups().stream()
                .filter(group -> group.group().equals("media"))
                .findFirst()
                .orElseThrow();

        assertThat(media.displayName()).isEqualTo("Media Library");
        assertThat(media.specUrl()).isEqualTo("/v3/api-docs/media");
        assertThat(media.docsUrl()).isEqualTo("/swagger-ui/index.html?urls.primaryName=media");
    }

    @Test
    @DisplayName("API discovery lists the complete bug-report OpenAPI group")
    void listGroups_includesBugReportContract() {
        ApiGroupSummary bugReports = service.listGroups().stream()
                .filter(group -> group.group().equals("bug-reports"))
                .findFirst()
                .orElseThrow();

        assertThat(bugReports.displayName()).isEqualTo("Bug Reports");
        assertThat(bugReports.specUrl()).isEqualTo("/v3/api-docs/bug-reports");
        assertThat(bugReports.docsUrl()).isEqualTo("/swagger-ui/index.html?urls.primaryName=bug-reports");
    }
}
