package uk.gegc.quizmaker.shared.api.docs;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the human-friendly API documentation landing page.
 */
@Controller
@RequestMapping("/api/v1/docs")
@RequiredArgsConstructor
@Hidden // Hide from generated OpenAPI specs to avoid confusing Swagger UI
public class ApiDocsController {

    private final ApiDocumentationService apiDocumentationService;

    @GetMapping
    public String landingPage(Model model) {
        model.addAttribute("summary", apiDocumentationService.buildSummary());
        model.addAttribute("groups", apiDocumentationService.listGroups());
        return "api-docs-landing";
    }
}

