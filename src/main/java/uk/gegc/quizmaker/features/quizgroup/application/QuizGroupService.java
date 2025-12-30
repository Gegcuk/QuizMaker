package uk.gegc.quizmaker.features.quizgroup.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSummaryDto;
import uk.gegc.quizmaker.features.quizgroup.api.dto.*;

import java.util.UUID;

public interface QuizGroupService {

    UUID create(String username, CreateQuizGroupRequest request);

    QuizGroupDto get(UUID id, Authentication authentication);

    Page<QuizGroupSummaryDto> list(Pageable pageable,
                                   Authentication authentication,
                                   boolean includeQuizPreviews,
                                   int previewSize);

    QuizGroupDto update(String username, UUID id, UpdateQuizGroupRequest request);

    void delete(String username, UUID id);

    Page<QuizSummaryDto> getQuizzesInGroup(
            UUID groupId,
            Pageable pageable,
            Authentication authentication
    );

    void addQuizzes(String username, UUID groupId, AddQuizzesToGroupRequest request);

    void removeQuiz(String username, UUID groupId, UUID quizId);

    void reorder(String username, UUID groupId, ReorderGroupQuizzesRequest request);

    /**
     * Get archived quizzes for the authenticated user (virtual group).
     * Uses QuizStatus.ARCHIVED for filtering.
     */
    Page<QuizSummaryDto> getArchivedQuizzes(
            Pageable pageable,
            Authentication authentication
    );
}
