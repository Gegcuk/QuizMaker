package uk.gegc.quizmaker.features.quiz.application.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizOwnerFreshnessRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuizFreshnessServiceImplTest {
    @Mock private QuizOwnerFreshnessRepository freshnessRepository;
    @Mock private QuizRepository quizRepository;
    @Mock private AppPermissionEvaluator permissionEvaluator;
    @InjectMocks private QuizFreshnessServiceImpl service;

    @Test
    void emptyPageNeverQueriesFreshness() {
        assertThat(service.findNewQuizIds(UUID.randomUUID(), List.of())).isEmpty();
        verifyNoInteractions(freshnessRepository);
    }

    @Test
    void readsOnlyRequestedOwnersQuizIds() {
        UUID ownerId = UUID.randomUUID();
        UUID freshId = UUID.randomUUID();
        List<UUID> page = List.of(freshId, UUID.randomUUID());
        when(freshnessRepository.findNewQuizIds(ownerId, page)).thenReturn(Set.of(freshId));
        assertThat(service.findNewQuizIds(ownerId, page)).containsExactly(freshId);
        verify(freshnessRepository).findNewQuizIds(ownerId, page);
    }

    @Test
    void missingIdentityFailsClosed() {
        assertThatThrownBy(() -> service.acknowledgeSeen(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Quiz not found");
        verifyNoInteractions(quizRepository, freshnessRepository);
    }

    @Test
    void registrationFailurePropagatesToAssemblyTransaction() {
        UUID quizId = UUID.randomUUID();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test failure"))
                .when(freshnessRepository).registerGeneratedQuiz(quizId);
        assertThatThrownBy(() -> service.registerGeneratedQuiz(quizId))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }
}
