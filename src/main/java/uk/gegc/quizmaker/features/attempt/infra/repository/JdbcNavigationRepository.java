package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Cursor;
import uk.gegc.quizmaker.features.attempt.domain.repository.NavigationRepository;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcNavigationRepository implements NavigationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcNavigationRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void initialize(UUID attemptId, Cursor c) {
        jdbc.update("""
                INSERT INTO attempt_navigation (attempt_id,form_id,current_item_id,row_version)
                VALUES (UUID_TO_BIN(:attempt),UUID_TO_BIN(:form),UUID_TO_BIN(:item),:version)
                """, new MapSqlParameterSource("attempt", attemptId.toString()).addValue("form", c.formId().toString())
                .addValue("item", c.currentFormItemId().toString()).addValue("version", c.version()));
    }
    @Override public Optional<Cursor> read(UUID attemptId) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(form_id) form_id,BIN_TO_UUID(current_item_id) item_id,row_version
                FROM attempt_navigation WHERE attempt_id=UUID_TO_BIN(:attempt) FOR SHARE
                """, Map.of("attempt", attemptId.toString()), (r, n) -> cursor(r)).stream().findFirst();
    }
    @Override public boolean move(UUID attemptId, long expectedVersion, UUID target) {
        return jdbc.update("""
                UPDATE attempt_navigation SET current_item_id=UUID_TO_BIN(:target),row_version=row_version+1
                WHERE attempt_id=UUID_TO_BIN(:attempt) AND row_version=:version
                """, Map.of("attempt", attemptId.toString(), "target", target.toString(), "version", expectedVersion)) == 1;
    }
    @Override public void record(Result r) {
        var c = r.cursor();
        jdbc.update("""
                INSERT INTO attempt_navigation_result (command_id,attempt_id,target_item_id,form_id,current_item_id,row_version)
                VALUES (UUID_TO_BIN(:command),UUID_TO_BIN(:attempt),UUID_TO_BIN(:target),UUID_TO_BIN(:form),UUID_TO_BIN(:item),:version)
                """, new MapSqlParameterSource("command", r.commandId().toString()).addValue("attempt", r.attemptId().toString())
                .addValue("target", r.targetFormItemId().toString()).addValue("form", c == null ? null : c.formId().toString())
                .addValue("item", c == null ? null : c.currentFormItemId().toString()).addValue("version", c == null ? null : c.version()));
    }
    @Override public Optional<Result> result(UUID commandId) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(command_id) command_id,BIN_TO_UUID(attempt_id) attempt_id,
                       BIN_TO_UUID(target_item_id) target_id,BIN_TO_UUID(form_id) form_id,
                       BIN_TO_UUID(current_item_id) item_id,row_version
                FROM attempt_navigation_result WHERE command_id=UUID_TO_BIN(:command) FOR SHARE
                """, Map.of("command", commandId.toString()), (r, n) -> new Result(UUID.fromString(r.getString("command_id")),
                UUID.fromString(r.getString("attempt_id")), UUID.fromString(r.getString("target_id")),
                r.getString("form_id") == null ? null : cursor(r))).stream().findFirst();
    }
    private static Cursor cursor(ResultSet r) throws SQLException {
        return new Cursor(UUID.fromString(r.getString("form_id")), UUID.fromString(r.getString("item_id")), r.getLong("row_version"));
    }
}
