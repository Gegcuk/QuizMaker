package uk.gegc.quizmaker.features.attempt.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrderingPolicy;
import uk.gegc.quizmaker.features.attempt.domain.repository.FormOptionOrderRepository;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcFormOptionOrderRepository implements FormOptionOrderRepository {
    private static final int SQL_BATCH = 500;
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcFormOptionOrderRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void createOrReplay(UUID ownerId, FixedForm form, FormOptionOrder order) {
        require(form != null && order != null && form.id().equals(order.formId()), INVALID_ORDER);
        require(form.items().size() == order.items().size(), INVALID_ORDER);
        var sourceByItem = new HashMap<UUID, UUID>();
        form.items().forEach(item -> sourceByItem.put(item.id(), item.sourceItemId()));
        order.items().forEach(item -> require(item.sourceItemId().equals(sourceByItem.get(item.formItemId())), INVALID_ORDER));
        var p = new MapSqlParameterSource("form", bytes(form.id())).addValue("owner", bytes(ownerId))
                .addValue("schema", order.schemaVersion()).addValue("strategy", order.policy().mode().name())
                .addValue("algorithm", order.policy().algorithmVersion()).addValue("count", order.items().size());
        // Current read: #519 may just have replayed a form invisible to an older RR snapshot.
        if (jdbc.queryForList("""
                SELECT f.id FROM attempt_form f JOIN quiz_revisions r ON r.id=f.quiz_revision_id
                WHERE f.id=:form AND r.owner_id=:owner FOR SHARE
                """, p).isEmpty()) throw new FixedFormException(FixedFormException.Reason.FORM_UNAVAILABLE);
        jdbc.update("""
                INSERT INTO attempt_form_option_order (form_id,schema_version,strategy,algorithm_version,item_count)
                VALUES (:form,:schema,:strategy,:algorithm,:count) ON DUPLICATE KEY UPDATE form_id=attempt_form_option_order.form_id
                """, p);
        // Serialize before any child insertion. Current reads also see a winner committed after an older RR snapshot.
        jdbc.queryForList("SELECT form_id FROM attempt_form_option_order WHERE form_id=:form FOR UPDATE", p);
        var itemRows = new ArrayList<MapSqlParameterSource>();
        var optionRows = new ArrayList<MapSqlParameterSource>();
        for (var item : order.items()) {
            var b = item.binding();
            itemRows.add(new MapSqlParameterSource("form", bytes(form.id())).addValue("item", bytes(item.formItemId()))
                    .addValue("revision", bytes(form.quizRevisionId())).addValue("source", bytes(item.sourceItemId()))
                    .addValue("question", bytes(b.questionRevisionId())).addValue("response", b.response().id())
                    .addValue("responseVersion", b.response().version()).addValue("scoring", b.scoring().id())
                    .addValue("scoringVersion", b.scoring().version()).addValue("count", item.optionIds().size()));
            for (int position = 0; position < item.optionIds().size(); position++) {
                optionRows.add(new MapSqlParameterSource("form", bytes(form.id())).addValue("item", bytes(item.formItemId()))
                        .addValue("question", bytes(b.questionRevisionId())).addValue("option", bytes(item.optionIds().get(position)))
                        .addValue("position", position));
            }
        }
        batch("""
                INSERT INTO attempt_form_item_option_order
                (form_id,item_id,quiz_revision_id,source_item_id,question_revision_id,response_contract_id,
                 response_contract_version,scoring_contract_id,scoring_contract_version,option_count)
                VALUES (:form,:item,:revision,:source,:question,:response,:responseVersion,:scoring,:scoringVersion,:count)
                ON DUPLICATE KEY UPDATE item_id=attempt_form_item_option_order.item_id
                """, itemRows);
        batch("""
                INSERT INTO attempt_form_option (form_id,item_id,question_revision_id,option_id,position)
                VALUES (:form,:item,:question,:option,:position) ON DUPLICATE KEY UPDATE option_id=attempt_form_option.option_id
                """, optionRows);
        require(read(ownerId, List.of(form.id()), true).equals(List.of(order)), ORDER_CONFLICT);
    }

    @Override
    public List<FormOptionOrder> findByIds(UUID ownerId, Collection<UUID> formIds) { return read(ownerId, formIds, false); }

    private List<FormOptionOrder> read(UUID ownerId, Collection<UUID> formIds, boolean lock) {
        if (ownerId == null) throw new FixedFormException(FixedFormException.Reason.FORM_UNAVAILABLE);
        require(formIds != null && formIds.stream().noneMatch(Objects::isNull), INVALID_ORDER);
        var ids = formIds.stream().distinct().toList();
        Map<UUID, FormOptionOrder> result = new HashMap<>();
        // The order root is already exclusively locked. Verification needs current reads,
        // without upgrading a shared immutable revision/binding lock used by other forms.
        String suffix = lock ? " FOR SHARE" : "";
        try {
            for (int start = 0; start < ids.size(); start += SQL_BATCH) {
                var p = new MapSqlParameterSource("owner", bytes(ownerId)).addValue("ids",
                        ids.subList(start, Math.min(start + SQL_BATCH, ids.size())).stream().map(JdbcFormOptionOrderRepository::bytes).toList());
                var roots = jdbc.query("""
                        SELECT o.* FROM attempt_form_option_order o JOIN attempt_form f ON f.id=o.form_id
                        JOIN quiz_revisions r ON r.id=f.quiz_revision_id WHERE r.owner_id=:owner AND o.form_id IN (:ids)
                        ORDER BY o.form_id
                        """ + suffix, p, (row, index) -> new Root(uuid(row,"form_id"),row.getInt("schema_version"),
                        new AnswerOrderingPolicy(AnswerOrder.valueOf(row.getString("strategy")), row.getInt("algorithm_version")),row.getInt("item_count")));
                if (roots.isEmpty()) continue;
                p.addValue("ids", roots.stream().map(root -> bytes(root.id())).toList());
                var items = jdbc.query("SELECT i.*,b.binding_hash FROM attempt_form_item_option_order i "
                        + "JOIN question_revision_contract_bindings b ON b.question_revision_id=i.question_revision_id "
                        + "WHERE i.form_id IN (:ids) ORDER BY i.form_id,i.item_id" + suffix, p, (row,index) -> item(row));
                Map<ItemKey, List<UUID>> options = new HashMap<>();
                jdbc.query("SELECT form_id,item_id,option_id,position FROM attempt_form_option WHERE form_id IN (:ids) "
                        + "ORDER BY form_id,item_id,position" + suffix, p, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    var values = options.computeIfAbsent(new ItemKey(uuid(row,"form_id"),uuid(row,"item_id")), ignored -> new ArrayList<>());
                    require(row.getInt("position") == values.size(), INVALID_STORED_ORDER);
                    values.add(uuid(row,"option_id"));
                });
                Map<UUID, List<FormOptionOrder.Item>> byForm = new HashMap<>();
                for (var item : items) {
                    var values = options.getOrDefault(new ItemKey(item.form(), item.id()), List.of());
                    require(values.size() == item.count(), INVALID_STORED_ORDER);
                    byForm.computeIfAbsent(item.form(), ignored -> new ArrayList<>())
                            .add(new FormOptionOrder.Item(item.id(),item.source(),item.binding(),values));
                }
                for (var root : roots) {
                    var children = byForm.getOrDefault(root.id(), List.of());
                    require(root.count() == children.size(), INVALID_STORED_ORDER);
                    result.put(root.id(), new FormOptionOrder(root.id(),root.version(),root.policy(),children));
                }
            }
        } catch (IllegalArgumentException | NullPointerException failure) { throw new OptionOrderException(INVALID_STORED_ORDER); }
        catch (uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException
               | uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException failure) {
            throw new OptionOrderException(INVALID_STORED_ORDER);
        } catch (OptionOrderException failure) {
            if (failure.reason() == INVALID_STORED_ORDER) throw failure;
            throw new OptionOrderException(INVALID_STORED_ORDER);
        }
        return ids.stream().filter(result::containsKey).map(result::get).toList();
    }

    private ItemRow item(ResultSet row) throws SQLException {
        var binding = new QuestionContractBinding(uuid(row,"question_revision_id"),
                new ContractVersion(row.getString("response_contract_id"), row.getInt("response_contract_version")),
                new ContractVersion(row.getString("scoring_contract_id"), row.getInt("scoring_contract_version")));
        require(binding.fingerprint().equals(row.getString("binding_hash")), INVALID_STORED_ORDER);
        return new ItemRow(uuid(row,"form_id"),uuid(row,"item_id"),uuid(row,"source_item_id"),binding,row.getInt("option_count"));
    }

    private void batch(String sql, List<MapSqlParameterSource> rows) {
        for (int start=0; start<rows.size(); start+=SQL_BATCH)
            jdbc.batchUpdate(sql,rows.subList(start,Math.min(start+SQL_BATCH,rows.size())).toArray(MapSqlParameterSource[]::new));
    }
    private static byte[] bytes(UUID id) { return id == null ? null : ByteBuffer.allocate(16)
            .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(ResultSet row,String column) throws SQLException {
        var value = ByteBuffer.wrap(row.getBytes(column)); return new UUID(value.getLong(),value.getLong());
    }
    private record Root(UUID id,int version,AnswerOrderingPolicy policy,int count) { }
    private record ItemKey(UUID form,UUID item) { }
    private record ItemRow(UUID form,UUID id,UUID source,QuestionContractBinding binding,int count) { }
}
