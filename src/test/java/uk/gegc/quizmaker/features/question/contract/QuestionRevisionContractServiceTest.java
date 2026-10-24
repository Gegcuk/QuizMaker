package uk.gegc.quizmaker.features.question.contract;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gegc.quizmaker.features.question.application.QuestionContractRegistry;
import uk.gegc.quizmaker.features.question.application.impl.QuestionRevisionContractServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionContractBindingRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.question.contract.ContractFixtures.*;
import static uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException.Reason.*;

class QuestionRevisionContractServiceTest {
    @Test
    void domainConflictsMustNotBeReclassifiedAsPersistenceFailuresByJpa() {
        var conflict = new ContractResolutionException(BINDING_CONFLICT);
        assertThat(new org.springframework.orm.jpa.vendor.HibernateJpaDialect().translateExceptionIfPossible(conflict))
                .isNull();
    }

    @Test
    void databaseReadOutageIsTypedAndCannotExposeSqlDiagnostics() {
        var repository = mock(QuestionContractBindingRepository.class);
        var registry = mock(QuestionContractRegistry.class);
        var service = new QuestionRevisionContractServiceImpl(repository, registry);
        when(repository.findForRevision(OWNER, id(10))).thenThrow(new DataAccessResourceFailureException("private SQL details"));
        assertThatThrownBy(() -> service.resolve(OWNER, id(10)))
                .isInstanceOfSatisfying(ContractResolutionException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(PERSISTENCE_UNAVAILABLE);
                    assertThat(failure.getMessage()).doesNotContain("private SQL");
                    assertThat(failure.getCause()).isNull();
                });
        verifyNoInteractions(registry);
    }

    @Test
    void writeOutageHasNoFallbackAndRemainsATransactionAbortingFailure() {
        var repository = mock(QuestionContractBindingRepository.class);
        var service = new QuestionRevisionContractServiceImpl(repository, registry());
        when(repository.findForRevision(OWNER, id(10))).thenReturn(Optional.of(
                new QuestionContractBindingRepository.Snapshot(Set.of(id(1)), Map.of())));
        doThrow(new DataAccessResourceFailureException("private SQL details"))
                .when(repository).insert(OWNER, id(10), binding(1, 1));
        assertThatThrownBy(() -> service.bind(OWNER, id(10), List.of(binding(1, 1))))
                .isInstanceOfSatisfying(ContractResolutionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(PERSISTENCE_UNAVAILABLE));
        verify(repository, times(1)).insert(OWNER, id(10), binding(1, 1));
    }
}
