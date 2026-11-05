package uk.gegc.quizmaker.features.question.application.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuestionSourceScopeRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuestionSourceScopeRepository.SourceScope;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuestionSourceConfirmationServiceImplTest {
    final QuestionSourceScopeRepository repository = mock(QuestionSourceScopeRepository.class);
    final Instant now = Instant.parse("2026-11-05T16:00:00Z");
    final QuestionSourceConfirmationServiceImpl service = new QuestionSourceConfirmationServiceImpl(repository, Clock.fixed(now, ZoneOffset.UTC));
    final UUID actor = UUID.randomUUID(), owner = UUID.randomUUID(), question = UUID.randomUUID();

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void unauthenticatedAndOrdinaryAuthorsCannotConfirmOwnership() {
        assertThatThrownBy(() -> service.confirm(Set.of(question), owner, "Verified source"))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(repository);
        authenticate();
        when(repository.lockActor("moderator")).thenReturn(Optional.of(actor));
        when(repository.lockPermissionNames(actor)).thenReturn(Set.of("QUIZ_UPDATE"));
        assertThatThrownBy(() -> service.confirm(Set.of(question), owner, "Verified source"))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).lockForConfirmation(any());
    }

    @Test void boundedValidatedInputIsRequiredBeforeAnyDatabaseWork() {
        assertThatThrownBy(() -> service.confirm(Set.of(), owner, "Verified source")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.confirm(Set.of(question), null, "Verified source")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.confirm(Set.of(question), owner, " ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.confirm(Set.of(question), owner, "x".repeat(501))).isInstanceOf(ValidationException.class);
        Set<UUID> tooMany = new HashSet<>(); for (int i = 0; i < 101; i++) tooMany.add(UUID.randomUUID());
        assertThatThrownBy(() -> service.confirm(tooMany, owner, "Verified source")).isInstanceOf(ValidationException.class);
        verifyNoInteractions(repository);
    }

    @Test void moderatorConfirmsOnlyUnknownSourcesAndSameOwnerReplayPreservesEvidence() {
        moderator();
        UUID existing = UUID.randomUUID();
        when(repository.lockForConfirmation(Set.of(question, existing))).thenReturn(List.of(
                new SourceScope(question, null), new SourceScope(existing, owner)));
        service.confirm(Set.of(question, existing), owner, "  Verified source  ");
        verify(repository).confirmLegacy(question, owner, actor, now, "Verified source");
        verify(repository, never()).confirmLegacy(eq(existing), any(), any(), any(), any());
    }

    @Test void conflictingOrMissingQuestionsDenyTheEntireBatchBeforeWrites() {
        moderator();
        UUID foreign = UUID.randomUUID();
        when(repository.lockForConfirmation(Set.of(question, foreign))).thenReturn(List.of(
                new SourceScope(question, null), new SourceScope(foreign, UUID.randomUUID())));
        assertThatThrownBy(() -> service.confirm(Set.of(question, foreign), owner, "Verified source"))
                .isInstanceOf(ForbiddenException.class);
        when(repository.lockForConfirmation(Set.of(question, foreign))).thenReturn(List.of(new SourceScope(question, null)));
        assertThatThrownBy(() -> service.confirm(Set.of(question, foreign), owner, "Verified source"))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).confirmLegacy(any(), any(), any(), any(), any());
    }

    @Test void inactiveOrUnknownOwnerCannotReceiveAuthority() {
        moderator(); when(repository.lockActiveOwner(owner)).thenReturn(false);
        assertThatThrownBy(() -> service.confirm(Set.of(question), owner, "Verified source"))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).lockForConfirmation(any());
    }

    private void moderator() {
        authenticate();
        when(repository.lockActor("moderator")).thenReturn(Optional.of(actor));
        when(repository.lockPermissionNames(actor)).thenReturn(Set.of("QUESTION_ADMIN"));
        when(repository.lockActiveOwner(owner)).thenReturn(true);
    }
    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("moderator", null, List.of()));
    }
}
