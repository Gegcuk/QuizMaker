package uk.gegc.quizmaker.shared.exception;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.*;
import uk.gegc.quizmaker.features.attempt.infra.receipt.ReceiptDatabaseFailure;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;
import static org.assertj.core.api.Assertions.*;

class SafeDataAccessTest {
    @Test @SuppressWarnings("deprecation")
    void springCategoriesSurviveWithoutTheOriginalDriverOrSuppressedGraph() {
        var driver=new SQLException("private-driver-diagnostic","HY000");
        for(var original:List.of(new DuplicateKeyException("private-sql",driver),
                new DataIntegrityViolationException("private-sql",driver),new CannotAcquireLockException("private-sql",driver),
                new DeadlockLoserDataAccessException("private-sql",driver),new PessimisticLockingFailureException("private-sql",driver),
                new org.springframework.orm.ObjectOptimisticLockingFailureException("private-sql",driver),
                new OptimisticLockingFailureException("private-sql",driver),new QueryTimeoutException("private-sql",driver),
                new TransientDataAccessResourceException("private-sql",driver),new RecoverableDataAccessException("private-sql",driver),
                new DataAccessResourceFailureException("private-sql",driver),new PermissionDeniedDataAccessException("private-sql",driver),
                new InvalidDataAccessApiUsageException("private-sql",driver),new InvalidDataAccessResourceUsageException("private-sql",driver),
                new ConcurrencyFailureException("private-sql",driver))) {
            original.addSuppressed(new IllegalStateException("private-suppressed"));
            var safe=SafeDataAccess.sanitize(original);
            assertThat(safe).isExactlyInstanceOf(original.getClass())
                    .hasMessage("Database operation failed" + (original instanceof DuplicateKeyException ? ": duplicate key" : "")).hasNoCause();
            assertThat(safe.getSuppressed()).isEmpty();
        }
    }
    @ParameterizedTest @CsvSource(delimiter='|', textBlock="""
            1062 | This record already exists. Please check for duplicates.
            1451 | This record cannot be modified because it is referenced by other data.
            1452 | This record cannot be modified because it is referenced by other data.
            1048 | A required field is missing. Please provide all required information.
            3819 | The data does not meet validation requirements. Please check your input.
            """)
    void integrityCategoriesKeepExistingConflictResponsesWithoutDriverDetails(int code,String expectedDetail) {
        var driver=new SQLException("private-constraint-contents", "23000",code);
        driver.addSuppressed(new IllegalStateException("private-constraint-suppressed"));
        var root=new SQLException("private-constraint-wrapper");root.setNextException(driver);
        var safe=SafeDataAccess.sanitize(new DataIntegrityViolationException("private-constraint-sql",root));
        assertThat(safe).isExactlyInstanceOf(DataIntegrityViolationException.class)
                .hasNoCause();
        assertThat(safe.getMessage()).doesNotContain("private");
        assertThat(safe.getSuppressed()).isEmpty();
        assertThat(SafeDataAccess.sanitize(safe)).hasMessage(safe.getMessage()).hasNoCause();
        var advice=new uk.gegc.quizmaker.shared.api.advice.GlobalExceptionHandler();
        var response=advice.handleDataIntegrity((DataIntegrityViolationException)safe,
                new org.springframework.mock.web.MockHttpServletRequest("POST","/test"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getDetail()).isEqualTo(expectedDetail);
    }
    @ParameterizedTest @ValueSource(ints={1205,1213})
    void retryClassificationUsesBoundedConflictEvidenceEvenWhenCodeIsInNextSQLException(int code) {
        var root=new SQLException("private-root","HY000");
        root.setNextException(new SQLException("private-next",code==1213?"40001":"HY000",code));
        var original=new DataAccessResourceFailureException("private-sql",root);
        var safe=SafeDataAccess.sanitize(original);
        assertThat(safe.getCause()).isInstanceOf(DatabaseConflictException.class).hasNoCause();
        assertThat(safe.getCause().getMessage()).doesNotContain("private");
        assertThat(ReceiptDatabaseFailure.reason(safe)).isEqualTo(code==1213?ReceiptException.Reason.RETRYABLE:ReceiptException.Reason.IN_PROGRESS);
        assertThat(SafeDataAccess.sanitize(safe).getCause()).isInstanceOf(DatabaseConflictException.class).hasNoCause();
    }
    @Test void unknownCategoriesRemainUnavailableWithoutCopyingDiagnostics() {
        var safe=SafeDataAccess.sanitize(new NonTransientDataAccessException("private-unknown",new SQLException("private-driver")){});
        assertThat(safe).isInstanceOf(NonTransientDataAccessException.class).hasNoCause().hasMessage("Database operation failed");
        assertThat(ReceiptDatabaseFailure.reason(safe)).isEqualTo(ReceiptException.Reason.PERSISTENCE_UNAVAILABLE);
        var transientSafe=SafeDataAccess.sanitize(new TransientDataAccessException("private-transient"){});
        assertThat(transientSafe).isInstanceOf(TransientDataAccessException.class).hasNoCause();
    }
}
