package uk.gegc.quizmaker.features.assignment;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gegc.quizmaker.features.assignment.application.AssignmentManagementAuthority;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.ScopeKind;

/** Local current scope-role reader; no production grant/delegation writer is claimed. */
final class AssignmentAuthorityFixture implements AssignmentManagementAuthority {
    private final JdbcTemplate sql;
    final List<Context> requests=new CopyOnWriteArrayList<>();
    AssignmentAuthorityFixture(JdbcTemplate sql) { this.sql=sql; }

    @Override public boolean allows(Context context) {
        requests.add(context);
        var scope=context.scope();
        var scopeId=scope.kind()==ScopeKind.PERSONAL ? context.actorId() : scope.organizationId();
        return !sql.query("""
                SELECT p.permission_name FROM fixture_assignment_role_scopes s
                JOIN user_roles ur ON ur.user_id=s.actor_id AND ur.role_id=s.role_id
                JOIN role_permissions rp ON rp.role_id=s.role_id
                JOIN permissions p ON p.permission_id=rp.permission_id
                WHERE s.actor_id=UUID_TO_BIN(?) AND s.scope_kind=? AND s.scope_id=UUID_TO_BIN(?)
                    AND p.permission_name=? FOR SHARE
                """,(rs,row)->rs.getString(1),context.actorId().toString(),scope.kind().name(),scopeId.toString(),
                context.action().permission().name()).isEmpty();
    }
    static void createTable(JdbcTemplate sql) {
        sql.execute("""
                CREATE TABLE IF NOT EXISTS fixture_assignment_role_scopes (
                    actor_id BINARY(16) NOT NULL, role_id BIGINT NOT NULL,
                    scope_kind VARCHAR(20) NOT NULL, scope_id BINARY(16) NOT NULL,
                    PRIMARY KEY(actor_id,role_id,scope_kind,scope_id),
                    FOREIGN KEY(actor_id,role_id) REFERENCES user_roles(user_id,role_id)
                ) ENGINE=InnoDB
                """);
    }
    static void bind(JdbcTemplate sql,UUID actor,long role,ScopeKind kind,UUID scopeId) {
        sql.update("INSERT INTO fixture_assignment_role_scopes(actor_id,role_id,scope_kind,scope_id) VALUES(UUID_TO_BIN(?),?,?,UUID_TO_BIN(?))",
                actor.toString(),role,kind.name(),scopeId.toString());
    }
}
