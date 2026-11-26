package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** A batch of complete primary keys, kept on the driving side of each locking query. */
final class JdbcRevisionPointReads {
    static final String IDS = "JSON_TABLE(:keys, '$[*]' COLUMNS(id VARCHAR(36) PATH '$')) k";
    static final String OPTION_IDS = """
            JSON_TABLE(:keys, '$[*]' COLUMNS(question_id VARCHAR(36) PATH '$[0]', id VARCHAR(36) PATH '$[1]')) k
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcRevisionPointReads(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    <T> List<T> readIds(String sql, List<UUID> ids, MapSqlParameterSource parameters, RowMapper<T> mapper) {
        return readKeys(sql, ids.stream().map(JdbcRevisionPointReads::quote).toList(), parameters, mapper);
    }

    <T> List<T> readKeys(String sql, List<String> jsonKeys, MapSqlParameterSource parameters, RowMapper<T> mapper) {
        var rows = new ArrayList<T>();
        for (int start = 0; start < jsonKeys.size(); start += JdbcQuizRevisionRepository.SQL_BATCH) {
            var batch = jsonKeys.subList(start, Math.min(start + JdbcQuizRevisionRepository.SQL_BATCH, jsonKeys.size()));
            parameters.addValue("keys", PrivateSqlText.of(batch.stream().collect(Collectors.joining(",", "[", "]"))));
            rows.addAll(jdbc.query(sql, parameters, mapper));
        }
        return rows;
    }

    static String optionKey(UUID questionId, UUID optionId) {
        return "[" + quote(questionId) + "," + quote(optionId) + "]";
    }

    private static String quote(UUID id) {
        return "\"" + id + "\"";
    }
}
