package uk.gegc.quizmaker.features.quiz.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import uk.gegc.quizmaker.features.quiz.domain.util.VersionCodeGenerator;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizExportAssembler;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuizExportServiceImpl implements QuizExportService {

    private final QuizExportRepository exportRepository;
    private final QuizExportAssembler assembler;
    private final List<ExportRenderer> renderers;
    private final AppPermissionEvaluator permissionEvaluator;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public ExportFile export(QuizExportFilter filter, ExportFormat format, PrintOptions printOptions, Authentication authentication) {
        Instant startTime = clock.instant();
        enforceScopePermissions(filter, authentication);
        
        // Generate export metadata for versioning and reproducibility
        UUID exportId = UUID.randomUUID();
        String versionCode = VersionCodeGenerator.generateVersionCode(exportId);
        long shuffleSeed = exportId.getMostSignificantBits() ^ exportId.getLeastSignificantBits();
        Random rng = new Random(shuffleSeed);
        
        List<Quiz> quizzes = fetchQuizzes(filter);
        List<QuizExportDto> exportDtos = assembler.toExportDtos(quizzes, rng);

        // Deterministic ordering by createdAt then id for stable outputs
        exportDtos = exportDtos.stream()
                .sorted(Comparator
                        .comparing(QuizExportDto::createdAt)
                        .thenComparing(QuizExportDto::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        String filenamePrefix = buildFilenamePrefix(filter, clock);
        ExportPayload payload = new ExportPayload(
                exportDtos, 
                printOptions != null ? printOptions : uk.gegc.quizmaker.features.quiz.domain.model.PrintOptions.defaults(), 
                filenamePrefix,
                exportId,
                versionCode,
                shuffleSeed
        );
        ExportFile result = resolveRenderer(format).render(payload);
        
        // Observability logging
        long durationMs = java.time.Duration.between(startTime, clock.instant()).toMillis();
        String user = authentication != null ? authentication.getName() : "anonymous";
        log.info("Quiz export completed: user={}, scope={}, format={}, quizCount={}, exportId={}, versionCode={}, durationMs={}", 
                 user, filter != null ? filter.scope() : "public", format, exportDtos.size(), exportId, versionCode, durationMs);
        
        return result;
    }
    
    private String buildFilenamePrefix(QuizExportFilter filter, Clock clock) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
                .withZone(ZoneId.systemDefault())
                .format(clock.instant());
        String scope = filter != null && filter.scope() != null ? filter.scope() : "public";
        
        StringBuilder prefix = new StringBuilder("quizzes_");
        prefix.append(scope).append("_").append(timestamp);
        
        // Add compact filter summary if filters present
        if (filter != null) {
            if (filter.quizIds() != null && !filter.quizIds().isEmpty()) {
                prefix.append("_ids").append(filter.quizIds().size());
            }
            if (filter.categoryIds() != null && !filter.categoryIds().isEmpty()) {
                prefix.append("_cat").append(filter.categoryIds().size());
            }
            if (filter.tags() != null && !filter.tags().isEmpty()) {
                prefix.append("_tag").append(filter.tags().size());
            }
            if (filter.difficulty() != null) {
                prefix.append("_").append(filter.difficulty().name().toLowerCase());
            }
            if (filter.search() != null && !filter.search().isBlank()) {
                prefix.append("_search");
            }
        }
        
        return prefix.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public void streamExport(QuizExportFilter filter, ExportFormat format, PrintOptions printOptions, OutputStream output, Authentication authentication) {
        ExportFile file = export(filter, format, printOptions, authentication);
        try (var is = file.contentSupplier().get()) {
            is.transferTo(output);
        } catch (Exception e) {
            log.error("Failed streaming export: format={}, scope={}", format, filter != null ? filter.scope() : "public", e);
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
            // Security: Apply scope predicates even when fetching by IDs
            // to prevent anonymous/low-privilege users from accessing private/unpublished quizzes
            List<Quiz> quizzes = exportRepository.findAllByIdsWithCategoryTagsQuestions(filter.quizIds());
            
            // Filter quizzes based on scope visibility rules
            return quizzes.stream()
                    .filter(quiz -> matchesScopeVisibility(quiz, filter))
                    .collect(java.util.stream.Collectors.toList());
        }
        // Use specification for filters; repository method fetches relations eagerly
        return exportRepository.findAll(QuizExportSpecifications.build(filter));
    }
    
    /**
     * Check if a quiz matches the scope visibility rules.
     * This ensures that even when fetching by IDs, we enforce the same
     * visibility and publication status checks as the specification.
     */
    private boolean matchesScopeVisibility(Quiz quiz, QuizExportFilter filter) {
        String scope = filter.scope() != null ? filter.scope().toLowerCase() : "public";
        
        switch (scope) {
            case "public" -> {
                // Public scope: only visible and published quizzes
                return quiz.getVisibility() == uk.gegc.quizmaker.features.quiz.domain.model.Visibility.PUBLIC
                        && quiz.getStatus() == uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus.PUBLISHED;
            }
            case "me" -> {
                // Me scope: user's own quizzes (any visibility/status)
                // authorId must match the quiz creator
                if (filter.authorId() == null || quiz.getCreator() == null) {
                    return false;
                }
                return quiz.getCreator().getId().equals(filter.authorId());
            }
            case "all" -> {
                // All scope: all quizzes (permissions already checked in validateScopePermissions)
                // Filter by author if specified
                if (filter.authorId() != null && quiz.getCreator() != null) {
                    return quiz.getCreator().getId().equals(filter.authorId());
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private ExportRenderer resolveRenderer(ExportFormat format) {
        return renderers.stream()
                .filter(r -> r.supports(format))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported export format: " + format));
    }
}


