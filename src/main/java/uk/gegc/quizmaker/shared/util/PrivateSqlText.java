package uk.gegc.quizmaker.shared.util;

import org.springframework.jdbc.support.SqlValue;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Bind the original text without exposing it when Spring logs JDBC parameters. */
public record PrivateSqlText(String value) implements SqlValue {
    public static SqlValue of(String value) {
        return value == null ? null : new PrivateSqlText(value);
    }

    @Override public void setValue(PreparedStatement statement, int index) throws SQLException {
        statement.setString(index, value);
    }

    @Override public void cleanup() { }

    @Override public String toString() { return "[private SQL text]"; }
}
