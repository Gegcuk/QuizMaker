package uk.gegc.quizmaker.features.quiz.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportFilter;
import uk.gegc.quizmaker.features.quiz.application.QuizExportService;
import uk.gegc.quizmaker.features.quiz.application.export.ExportRenderer;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.PrintOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizExportSpecifications;
import uk.gegc.quizmaker.features.quiz.domain.repository.export.QuizExportRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizExportAssembler;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.io.OutputStream;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QuizExportServiceImpl implements QuizExportService {

    private final QuizExportRepository exportRepository;
    private final QuizExportAssembler assembler;
    private final List<ExportRenderer> renderers;
    private final AppPermissionEvaluator permissionEvaluator;

    @Override
    public ExportFile export(QuizExportFilter filter, ExportFormat format, PrintOptions printOptions, Authentication authentication) {
        enforceScopePermissions(filter, authentication);
        List<Quiz> quizzes = fetchQuizzes(filter);
        List<QuizExportDto> exportDtos = assembler.toExportDtos(quizzes);

        // Deterministic ordering by createdAt then id for stable outputs
        exportDtos = exportDtos.stream()
                .sorted(Comparator
                        .comparing(QuizExportDto::createdAt)
                        .thenComparing(QuizExportDto::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        ExportPayload payload = new ExportPayload(exportDtos, printOptions != null ? printOptions : uk.gegc.quizmaker.features.quiz.domain.model.PrintOptions.defaults());
        return resolveRenderer(format).render(payload);
    }

    @Override
    public void streamExport(QuizExportFilter filter, ExportFormat format, PrintOptions printOptions, OutputStream output, Authentication authentication) {
        ExportFile file = export(filter, format, printOptions, authentication);
        try (var is = file.contentSupplier().get()) {
            is.transferTo(output);
        } catch (Exception e) {
            throw new RuntimeException("Failed streaming export", e);
        }
    }

    private void enforceScopePermissions(QuizExportFilter filter, Authentication authentication) {
        String scope = filter != null ? filter.scope() : "public";
        if (scope == null || scope.isBlank() || scope.equalsIgnoreCase("public")) {
            return; // public is allowed anonymously
        }

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ForbiddenException("Authentication required for scope=" + scope);
        }

        switch (scope.toLowerCase()) {
            case "me" -> {
                boolean hasRead = permissionEvaluator.hasPermission(PermissionName.QUIZ_READ);
                if (!hasRead) {
                    throw new ForbiddenException("QUIZ_READ permission required for scope=me");
                }
            }
            case "all" -> {
                boolean hasModerate = permissionEvaluator.hasAnyPermission(PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN);
                if (!hasModerate) {
                    throw new ForbiddenException("QUIZ_MODERATE or QUIZ_ADMIN required for scope=all");
                }
            }
        }
    }

    private List<Quiz> fetchQuizzes(QuizExportFilter filter) {
        if (filter != null && filter.quizIds() != null && !filter.quizIds().isEmpty()) {
            return exportRepository.findAllByIdsWithCategoryTagsQuestions(filter.quizIds());
        }
        // Use specification for filters; repository method fetches relations eagerly
        return exportRepository.findAll(QuizExportSpecifications.build(filter));
    }

    private ExportRenderer resolveRenderer(ExportFormat format) {
        return renderers.stream()
                .filter(r -> r.supports(format))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported export format: " + format));
    }
}


