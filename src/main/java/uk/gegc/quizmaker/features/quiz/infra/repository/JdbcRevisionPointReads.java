package uk.gegc.quizmaker.features.quiz.infra.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

import static uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository.bytes;

/** Batches single-row locking statements without giving the optimizer a multi-key scan. */
final class JdbcRevisionPointReads {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcRevisionPointReads(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    <T> List<T> readIds(String singleSelect, List<UUID> ids, MapSqlParameterSource common, RowMapper<T> mapper) {
        return readKeys(singleSelect, ids.stream().map(id -> new Key(id, null)).toList(), common, mapper);
    }

    <T> List<T> readKeys(String singleSelect, List<Key> keys, MapSqlParameterSource common, RowMapper<T> mapper) {
        var rows = new ArrayList<T>();
        for (int start = 0; start < keys.size(); start += JdbcQuizRevisionRepository.SQL_BATCH) {
            int end = Math.min(start + JdbcQuizRevisionRepository.SQL_BATCH, keys.size());
            var sql = new StringJoiner(" UNION ALL ");
            var parameters = new MapSqlParameterSource(common.getValues());
            for (int index = start; index < end; index++) {
                var key = keys.get(index);
                String id = "pointId" + (index - start);
                String parent = "pointParent" + (index - start);
                sql.add("(" + singleSelect.replace(":pointId", ":" + id).replace(":pointParent", ":" + parent) + ")");
                parameters.addValue(id, bytes(key.id()));
                if (key.parent() != null) parameters.addValue(parent, bytes(key.parent()));
            }
            rows.addAll(jdbc.query(sql.toString(), parameters, mapper));
        }
        return rows;
    }

    record Key(UUID id, UUID parent) { }
}
