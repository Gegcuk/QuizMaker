package uk.gegc.quizmaker.features.assignment.infra.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublication.*;
import uk.gegc.quizmaker.features.assignment.domain.repository.AssignmentPublicationRepository;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetReference;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException.*;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException.Reason.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcAssignmentPublicationRepository implements AssignmentPublicationRepository {
    private final NamedParameterJdbcTemplate sql;
    private final ObjectMapper mapper;
    public JdbcAssignmentPublicationRepository(NamedParameterJdbcTemplate sql, ObjectMapper mapper) { this.sql = sql; this.mapper = mapper; }

    @Override public void create(UUID id, UUID owner, Scope scope, Instant now) {
        safe(() -> {
            var p = ids(id).addValue("owner", owner.toString()).addValue("scope", scope.kind().name())
                    .addValue("org", string(scope.organizationId())).addValue("time", Timestamp.from(now));
            int inserted = sql.update("INSERT IGNORE INTO assignments(id,created_at) VALUES(UUID_TO_BIN(:id),:time)", p);
            if (inserted == 1) sql.update("""
                    INSERT INTO assignment_publication_heads(assignment_id,owner_id,scope_kind,organization_id,row_version)
                    VALUES(UUID_TO_BIN(:id),UUID_TO_BIN(:owner),:scope,UUID_TO_BIN(:org),0)
                    """, p);
            return null;
        });
    }
    @Override public Optional<Head> head(UUID id, boolean lock) {
        return safe(() -> sql.query("SELECT * FROM assignment_publication_heads WHERE assignment_id=UUID_TO_BIN(:id)"
                + (lock ? " FOR UPDATE" : ""), ids(id), (rs, row) -> new Head(id, uuid(rs,"owner_id"),
                new Scope(ScopeKind.valueOf(rs.getString("scope_kind")), uuid(rs,"organization_id")),
                rs.getLong("row_version"), uuid(rs,"draft_id"), uuid(rs,"publication_id"), uuid(rs,"published_draft_id")))
                .stream().findFirst());
    }
    @Override public Optional<Receipt> receipt(UUID id, UUID actor, UUID command) {
        return safe(() -> sql.query("""
                SELECT * FROM assignment_publication_receipts WHERE assignment_id=UUID_TO_BIN(:id)
                    AND actor_id=UUID_TO_BIN(:actor) AND command_id=UUID_TO_BIN(:command)
                """, ids(id).addValue("actor",actor.toString()).addValue("command",command.toString()), (rs,row) -> {
            var operation = Operation.valueOf(rs.getString("operation"));
            return new Receipt(operation, rs.getString("request_hash"), rs.getLong("result_version"),
                    uuid(rs, operation == Operation.PUBLISH ? "publication_id" : "draft_id"));
        }).stream().findFirst());
    }
    @Override public Optional<Captured> snapshot(UUID assignmentId, UUID id, Status status) {
        return safe(() -> sql.query("SELECT * FROM " + table(status) + " WHERE assignment_id=UUID_TO_BIN(:assignment) AND id=UUID_TO_BIN(:id)",
                ids(id).addValue("assignment",assignmentId.toString()), (rs,row) -> {
            var audience = new Audience(rs.getInt("audience_schema_version"), AudienceKind.valueOf(rs.getString("audience_kind")),
                    uuid(rs,"audience_source_version_id"), json(rs.getString("audience_members"),new TypeReference<List<UUID>>(){}));
            var definition = new Definition(uuid(rs,"quiz_revision_id"),rs.getInt("content_schema_version"),
                    new PolicyReference(rs.getInt("policy_schema_version"),new PresetReference(rs.getString("preset_id"),rs.getInt("preset_version"))),
                    audience,new Availability(instant(rs,"opens_at"),instant(rs,"closes_at")),
                    new Capacity(rs.getObject("capacity_limit",Integer.class), rs.getString("capacity_enforcement") == null ? null
                            : CapacityEnforcement.valueOf(rs.getString("capacity_enforcement"))),
                    new GuestAccess(rs.getBoolean("guest_access"),DisplayNameRequirement.valueOf(rs.getString("guest_display_name")),
                            EmailRequirement.valueOf(rs.getString("guest_email"))));
            require(status.name().equals(rs.getString("status")),CORRUPT);
            return new Captured(new Snapshot(id,assignmentId,rs.getInt("version"),rs.getInt("schema_version"),status,
                    uuid(rs,"owner_id"),new Scope(ScopeKind.valueOf(rs.getString("scope_kind")),uuid(rs,"organization_id")),
                    definition,instant(rs,"recorded_at")),json(rs.getString("participant_designations"),new TypeReference<Map<UUID,String>>(){}));
        }).stream().findFirst());
    }
    @Override public boolean revisionExists(UUID id, int version) {
        return safe(() -> !sql.query("SELECT id FROM quiz_revisions WHERE id=UUID_TO_BIN(:id) AND schema_version=:version FOR SHARE",
                ids(id).addValue("version",version),(rs,row)->rs.getBytes(1)).isEmpty());
    }
    @Override public void insert(Captured capture) {
        safe(() -> {
            var s=capture.snapshot(); var d=s.definition(); var a=d.audience(); var p=d.policy();
            var values=ids(s.id()).addValue("assignment",s.assignmentId().toString()).addValue("number",s.version())
                    .addValue("schema",s.schemaVersion()).addValue("status",s.status().name()).addValue("owner",s.ownerId().toString())
                    .addValue("scope",s.scope().kind().name()).addValue("org",string(s.scope().organizationId()))
                    .addValue("revision",d.quizRevisionId().toString()).addValue("contentVersion",d.contentSchemaVersion())
                    .addValue("policyVersion",p.schemaVersion()).addValue("preset",p.preset().id()).addValue("presetVersion",p.preset().version())
                    .addValue("audienceVersion",a.schemaVersion()).addValue("audienceKind",a.kind().name()).addValue("source",a.sourceVersionId().toString())
                    .addValue("members",json(a.memberIds())).addValue("labels",json(capture.designations()))
                    .addValue("opens",timestamp(d.availability().opensAt())).addValue("closes",timestamp(d.availability().closesAt()))
                    .addValue("capacity",d.capacity().limit()).addValue("enforcement",d.capacity().enforcement()==null?null:d.capacity().enforcement().name())
                    .addValue("guest",d.guests().enabled()).addValue("name",d.guests().displayName().name()).addValue("email",d.guests().email().name())
                    .addValue("time",timestamp(s.recordedAt()));
            sql.update("INSERT INTO " + table(s.status()) + """
                    (id,assignment_id,version,schema_version,status,owner_id,scope_kind,organization_id,quiz_revision_id,content_schema_version,
                    policy_schema_version,preset_id,preset_version,audience_schema_version,audience_kind,audience_source_version_id,audience_members,
                    participant_designations,opens_at,closes_at,capacity_limit,capacity_enforcement,guest_access,guest_display_name,guest_email,recorded_at)
                    VALUES(UUID_TO_BIN(:id),UUID_TO_BIN(:assignment),:number,:schema,:status,UUID_TO_BIN(:owner),:scope,UUID_TO_BIN(:org),
                    UUID_TO_BIN(:revision),:contentVersion,:policyVersion,:preset,:presetVersion,:audienceVersion,:audienceKind,UUID_TO_BIN(:source),
                    :members,:labels,:opens,:closes,:capacity,:enforcement,:guest,:name,:email,:time)
                    """,values);
            return null;
        });
    }
    @Override public void point(Head before, Head after) {
        safe(() -> {
            require(sql.update("""
                    UPDATE assignment_publication_heads SET row_version=:version,draft_id=UUID_TO_BIN(:draft),
                        publication_id=UUID_TO_BIN(:publication),published_draft_id=UUID_TO_BIN(:publishedDraft)
                    WHERE assignment_id=UUID_TO_BIN(:id) AND row_version=:expected
                    """,ids(before.assignmentId()).addValue("version",after.version()).addValue("expected",before.version())
                    .addValue("draft",string(after.draftId())).addValue("publication",string(after.publicationId()))
                    .addValue("publishedDraft",string(after.publishedDraftId())))==1,STALE);
            return null;
        });
    }
    @Override public void record(UUID id, UUID actor, UUID command, Receipt receipt, Instant now) {
        safe(() -> {
            sql.update("""
                    INSERT INTO assignment_publication_receipts(assignment_id,actor_id,command_id,operation,request_hash,
                        result_version,draft_id,publication_id,recorded_at)
                    VALUES(UUID_TO_BIN(:id),UUID_TO_BIN(:actor),UUID_TO_BIN(:command),:operation,:hash,:version,
                        UUID_TO_BIN(:draft),UUID_TO_BIN(:publication),:time)
                    """,ids(id).addValue("actor",actor.toString()).addValue("command",command.toString())
                    .addValue("operation",receipt.operation().name()).addValue("hash",receipt.fingerprint()).addValue("version",receipt.version())
                    .addValue("draft",receipt.operation()==Operation.PUBLISH?null:receipt.snapshotId().toString())
                    .addValue("publication",receipt.operation()==Operation.PUBLISH?receipt.snapshotId().toString():null).addValue("time",timestamp(now)));
            return null;
        });
    }
    private <T> T safe(Supplier<T> work) {
        try { return work.get(); }
        catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
        catch (IllegalArgumentException failure) { throw new uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException(CORRUPT); }
    }
    private <T> T json(String value, TypeReference<T> type) {
        try { require(value!=null,CORRUPT); return mapper.readValue(value,type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException(CORRUPT); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException(CORRUPT); }
    }
    private static String table(Status s) { return s==Status.DRAFT?"assignment_drafts":"assignment_versions"; }
    private static MapSqlParameterSource ids(UUID id) { return new MapSqlParameterSource("id",id.toString()); }
    private static String string(UUID id) { return id==null?null:id.toString(); }
    private static Timestamp timestamp(Instant t) { return t==null?null:Timestamp.from(t); }
    private static Instant instant(ResultSet rs,String column) throws SQLException { var t=rs.getTimestamp(column);return t==null?null:t.toInstant(); }
    private static UUID uuid(ResultSet rs,String column) throws SQLException {
        var bytes=rs.getBytes(column); if(bytes==null)return null;
        require(bytes.length==16,CORRUPT); var b=java.nio.ByteBuffer.wrap(bytes); return new UUID(b.getLong(),b.getLong());
    }
}
