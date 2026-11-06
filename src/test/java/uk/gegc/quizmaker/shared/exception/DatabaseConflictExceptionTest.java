package uk.gegc.quizmaker.shared.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.BatchUpdateException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;

class DatabaseConflictExceptionTest {
    @ParameterizedTest @CsvSource({"1213,DEADLOCK,retryable", "1205,LOCK_TIMEOUT,in_progress"})
    void nestedAndBatchDiagnosticsBecomeOnlyASafeCode(int code, DatabaseConflictException.Kind kind, String outcome) {
        var sql = new SQLException("Private SQL and parameter values", "HY000", code);
        var batch = new BatchUpdateException(); batch.setNextException(sql);
        var driver = new IllegalStateException("Private driver diagnostics", batch);
        var safe = DatabaseConflictException.from(driver).orElseThrow();
        assertThat(safe.kind()).isEqualTo(kind); assertThat(safe.outcome()).isEqualTo(outcome);
        assertThat(safe).hasMessage("Database conflict: " + kind).hasNoCause();
        safe.addSuppressed(driver);
        assertThat(safe.getSuppressed()).isEmpty();
        assertThatThrownBy(() -> safe.initCause(driver)).isInstanceOf(IllegalStateException.class);
        var forwarded = DatabaseConflictException.from(safe).orElseThrow();
        assertThat(forwarded.kind()).isEqualTo(kind);
        assertThat(forwarded).hasNoCause(); assertThat(forwarded.getSuppressed()).isEmpty();
    }

    @Test void ordinaryOutagesAndMessagesDoNotInventAConflict() {
        assertThat(DatabaseConflictException.from(new SQLException("deadlock 1213", "08006", 0))).isEmpty();
        assertThat(DatabaseConflictException.from(new SQLException("duplicate key", "23000", 1062))).isEmpty();
        assertThat(DatabaseConflictException.from(null)).isEmpty();
        var first = new SQLException("cycle"); var second = new IllegalStateException("cycle", first);
        first.initCause(second);
        assertThat(DatabaseConflictException.from(first)).isEmpty();
    }
}
