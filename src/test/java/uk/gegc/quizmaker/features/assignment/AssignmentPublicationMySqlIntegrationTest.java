package uk.gegc.quizmaker.features.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.assignment.application.*;
import uk.gegc.quizmaker.features.assignment.application.impl.AssignmentPublicationServiceImpl;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublication.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException;
import uk.gegc.quizmaker.features.assignment.infra.repository.JdbcAssignmentPublicationRepository;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.MembershipResolver;

import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.assignment.AssignmentFixtures.*;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException.Reason.*;

/** Real #621 writes/MySQL, with a SQL-backed current scope-role authority fixture.
 * Versioned source and membership readers are fixtures.
 * The real active-user facade uses a SQL-backed UserRepository test adapter (no fabricated IDs).
 * No proof of #533 HTTP, #534 start, #835 transfer or #798 cancellation is claimed.
 */
@Tag("db-serial")
class AssignmentPublicationMySqlIntegrationTest {
    static final String SCHEMA="assignment_publication_"+UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate admin,sql;
    static DriverManagerDataSource source;
    static DataSourceTransactionManager manager;
    static boolean created;
    static final UUID OTHER=UUID.randomUUID(), ORPHAN=UUID.randomUUID();
    static final Map<String,UUID> HISTORICAL=Map.of("assignment_drafts",UUID.randomUUID(),"assignment_versions",UUID.randomUUID());
    static long fixtureRole, studentRole;
    static ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    static final ThreadLocal<List<String>> statements=ThreadLocal.withInitial(ArrayList::new);
    AssignmentPublicationService service;
    HookRepository repository;
    Set<UUID> organizations;
    AtomicBoolean sourceAvailable;
    Map<Definition,Map<UUID,String>> verified;
    SimpleMeterRegistry metrics;
    AssignmentAuthorityFixture authority;
    enum Fault { VERSION_INSERT, POINTER_UPDATE, RECEIPT_INSERT }

    @BeforeAll static void database() {
        String port=System.getenv().getOrDefault("TEST_MYSQL_PORT","3306");
        if(!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base="jdbc:mysql://127.0.0.1:"+port+"/";
        String options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user=System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME","root");
        String password=System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD","root");
        admin=new JdbcTemplate(new DriverManagerDataSource(base+options,user,password));
        admin.execute("CREATE DATABASE "+SCHEMA+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); created=true;
        source=new CountingSource(base+SCHEMA+options,user,password); sql=new JdbcTemplate(source);
        manager=new DataSourceTransactionManager(source);
        migrate("112");
        for(var entry:Map.of(OWNER,"author",OTHER,"other").entrySet()) sql.update(
                "INSERT INTO users(user_id,username,email,password) VALUES(UUID_TO_BIN(?),?,?,'not-a-login')",
                entry.getKey().toString(),entry.getValue(),entry.getValue()+"@example.invalid");
        sql.update("INSERT INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1,'Source fixture',?)",
                CONTENT.toString(),UUID.randomUUID().toString(),OWNER.toString(),Timestamp.from(TIME));
        sql.update("INSERT INTO assignments(id,created_at) VALUES(UUID_TO_BIN(?),?)",ORPHAN.toString(),Timestamp.from(TIME));
        for(var entry:HISTORICAL.entrySet()) sql.update("INSERT INTO "+entry.getKey()+"""
                (id,assignment_id,version,schema_version,status,owner_id,scope_kind,organization_id,quiz_revision_id,content_schema_version,
                policy_schema_version,preset_id,preset_version,audience_schema_version,audience_kind,audience_source_version_id,
                audience_members,opens_at,closes_at,capacity_limit,capacity_enforcement,guest_access,guest_display_name,guest_email,recorded_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),1,1,?,UUID_TO_BIN(?),'PERSONAL',NULL,UUID_TO_BIN(?),1,
                1,'FREE_PRACTICE',1,1,'ROSTER',UUID_TO_BIN(?),?,NULL,NULL,NULL,NULL,false,'NONE','NONE',?)
                """,entry.getValue().toString(),ORPHAN.toString(),entry.getKey().equals("assignment_drafts")?"DRAFT":"PUBLISHED",
                OWNER.toString(),CONTENT.toString(),AUDIENCE.toString(),"[\""+MEMBER+"\"]",Timestamp.from(TIME));
        var legacyRoleGrants=sql.queryForList("SELECT role_id,permission_id FROM role_permissions ORDER BY role_id,permission_id");
        migrate("113"); migrate("113");
        assertThat(sql.queryForList("SELECT role_id,permission_id FROM role_permissions ORDER BY role_id,permission_id")).isEqualTo(legacyRoleGrants);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM assignment_publication_heads",Integer.class)).isZero();
        for(var entry:HISTORICAL.entrySet()) {
            var historical=sql.queryForMap("SELECT BIN_TO_UUID(owner_id) owner_id,audience_members,participant_designations,version FROM "+entry.getKey()+" WHERE id=UUID_TO_BIN(?)",entry.getValue().toString());
            assertThat(historical).containsEntry("owner_id",OWNER.toString()).containsEntry("version",1).containsEntry("participant_designations",null);
            assertThat(historical.get("audience_members").toString()).contains(MEMBER.toString());
        }
        sql.update("INSERT INTO roles(role_name,description,is_default) VALUES('assignment-fixture','Local test fixture',false)");
        fixtureRole=sql.queryForObject("SELECT role_id FROM roles WHERE role_name='assignment-fixture'",Long.class);
        for(UUID id:List.of(OWNER,OTHER)) sql.update("INSERT INTO user_roles(user_id,role_id) VALUES(UUID_TO_BIN(?),?)",id.toString(),fixtureRole);
        sql.update("INSERT INTO roles(role_name,description,is_default) VALUES('assignment-student-fixture','Local student fixture',false)");
        studentRole=sql.queryForObject("SELECT role_id FROM roles WHERE role_name='assignment-student-fixture'",Long.class);
        sql.update("INSERT INTO user_roles(user_id,role_id) VALUES(UUID_TO_BIN(?),?)",OWNER.toString(),studentRole);
        AssignmentAuthorityFixture.createTable(sql);
    }
    static void migrate(String target) { Flyway.configure().dataSource(source).locations("classpath:db/migration").target(target).load().migrate(); }
    @AfterAll static void drop() { if(created) admin.execute("DROP DATABASE "+SCHEMA); }
    @BeforeEach void setup() {
        statements.get().clear();
        organizations=new HashSet<>(Set.of(ORGANIZATION)); sourceAvailable=new AtomicBoolean(true); verified=new HashMap<>();
        verified.put(definition(),Map.of(MEMBER,"Participant A")); metrics=new SimpleMeterRegistry();
        sql.update("INSERT IGNORE INTO role_permissions(role_id,permission_id) SELECT ?,permission_id FROM permissions WHERE permission_name IN ('ASSIGNMENT_CREATE','ASSIGNMENT_PUBLISH')",fixtureRole);
        sql.update("UPDATE users SET is_active=true,is_deleted=false WHERE user_id IN (UUID_TO_BIN(?),UUID_TO_BIN(?))",OWNER.toString(),OTHER.toString());
        sql.update("DELETE FROM fixture_assignment_role_scopes");
        bindScope(OWNER,fixtureRole,ScopeKind.PERSONAL,OWNER);
        bindScope(OTHER,fixtureRole,ScopeKind.PERSONAL,OTHER);
        bindScope(OWNER,fixtureRole,ScopeKind.ORGANIZATION,ORGANIZATION);
        authority=new AssignmentAuthorityFixture(sql);
        repository=new HookRepository(); service=build(repository,List.of(verifier())); login("author");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); metrics.close(); }
    AssignmentSourceVerifier verifier() {
        return (actor,scope,definition)-> {
            // Explicit authorized immutable source fixtures, not acceptance of arbitrary supplied IDs.
            if(!sourceAvailable.get() || !Set.of(OWNER,OTHER).contains(actor) || !verified.containsKey(definition))
                throw new AssignmentPublicationException(INVALID_REFERENCE);
            return verified.get(definition);
        };
    }
    AssignmentPublicationService build(HookRepository repo,List<AssignmentSourceVerifier> verifiers) {
        return build(repo,verifiers,List.of(authority));
    }
    AssignmentPublicationService build(HookRepository repo,List<AssignmentSourceVerifier> verifiers,
            List<AssignmentManagementAuthority> authorities) {
        UserRepository users=mock(UserRepository.class);
        when(users.lockActiveAuthenticatedUserId(anyString())).thenAnswer(invocation->sql.query(
                "SELECT BIN_TO_UUID(user_id) FROM users WHERE username=? AND is_active=true AND is_deleted=false AND username<>'anonymous' FOR SHARE",
                (rs,row)->UUID.fromString(rs.getString(1)),invocation.<String>getArgument(0)).stream().findFirst());
        return new AssignmentPublicationServiceImpl(repo,new AuthenticatedUserLookupImpl(users),verifiers,authorities,new MembershipResolver() {
            @Override public boolean isMemberOfOrganization(UUID actor,UUID org) { return organizations.contains(org); }
        },Clock.fixed(TIME,ZoneOffset.UTC),metrics,mapper,manager);
    }
    static void login(String name) {
        var principal=org.springframework.security.core.userdetails.User.withUsername(name).password("unused").authorities("STALE_CACHED_AUTHORITY").build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
    }
    Save createCommand() { return new Save(UUID.randomUUID(),0,UUID.randomUUID(),new Scope(ScopeKind.PERSONAL,null),definition()); }
    Publish publication(Result draft) { return new Publish(draft.captured().snapshot().assignmentId(),draft.version(),UUID.randomUUID()); }
    Definition changed() {
        var d=definition(); var changed=new Definition(d.quizRevisionId(),d.contentSchemaVersion(),d.policy(),d.audience(),
                new Availability(TIME.minusSeconds(1),TIME.plusSeconds(7200)),d.capacity(),d.guests());
        verified.put(changed,Map.of(MEMBER,"Participant A")); return changed;
    }
    static void rejected(Runnable action,AssignmentPublicationException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AssignmentPublicationException.class,e->{
            assertThat(e.reason()).isEqualTo(reason); assertThat(e.getCause()).isNull();
            assertThat(e).hasMessage("Assignment publication rejected: "+reason);
        });
    }
    int count(UUID id,String table) { return sql.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE assignment_id=UUID_TO_BIN(?)",Integer.class,id.toString()); }
    static void bindScope(UUID actor,long role,ScopeKind kind,UUID scopeId) {
        AssignmentAuthorityFixture.bind(sql,actor,role,kind,scopeId);
    }

    @Test void publishesAThenBPreservingCapturedIdentityOldBindingsAndExactReceiptsAfterRestart() {
        var command=createCommand(); var draft=service.create(command); var publish=publication(draft);
        assertThat(service.create(command)).isEqualTo(new Result(draft.version(),draft.captured(),true));
        var a=service.publish(publish); var binding=a.captured();
        var edited=service.save(new Save(command.assignmentId(),a.version(),UUID.randomUUID(),command.scope(),changed()));
        var b=service.publish(publication(edited));
        assertThat(b.captured().snapshot().version()).isEqualTo(2);
        assertThat(service.current(command.assignmentId()).orElseThrow().captured()).isEqualTo(b.captured());
        assertThat(service.read(command.assignmentId(),a.captured().snapshot().id()).captured()).isEqualTo(binding);
        assertThat(binding.designations()).containsExactlyEntriesOf(Map.of(MEMBER,"Participant A"));
        assertThat(binding.snapshot().definition()).isEqualTo(command.definition());
        service=build(new HookRepository(),List.of(verifier()));
        assertThat(service.publish(publish)).isEqualTo(new Result(a.version(),binding,true));
        assertThat(service.current(command.assignmentId()).orElseThrow().captured()).isEqualTo(b.captured());
        assertThat(count(command.assignmentId(),"assignment_versions")).isEqualTo(2);
        assertThatThrownBy(()->sql.update("UPDATE assignment_versions SET participant_designations=JSON_OBJECT() WHERE id=UUID_TO_BIN(?)",binding.snapshot().id().toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql.update("DELETE FROM assignment_versions WHERE id=UUID_TO_BIN(?)",binding.snapshot().id().toString())).isInstanceOf(DataAccessException.class);
    }
    @Test void repeatPublicationOfUnchangedDraftDoesNotCreateAnotherVersion() {
        var draft=service.create(createCommand()); var a=service.publish(publication(draft));
        var result=service.publish(new Publish(a.captured().snapshot().assignmentId(),a.version(),UUID.randomUUID()));
        assertThat(result.captured()).isEqualTo(a.captured()); assertThat(result.version()).isEqualTo(a.version());
        assertThat(count(a.captured().snapshot().assignmentId(),"assignment_versions")).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(Fault.class)
    void reachedWriteFailureRollsBackVersionPointerAndReceiptThenRetryCommits(Fault fault) {
        var command=createCommand(); var draft=service.create(command); var a=service.publish(publication(draft));
        var edit=service.save(new Save(command.assignmentId(),a.version(),UUID.randomUUID(),command.scope(),changed()));
        var publish=publication(edit); repository.fault=fault;
        rejected(()->service.publish(publish),UNAVAILABLE);
        assertThat(repository.reached.get()).isTrue();
        assertThat(service.current(command.assignmentId()).orElseThrow().captured()).isEqualTo(a.captured());
        assertThat(service.draft(command.assignmentId())).isEqualTo(edit);
        assertThat(count(command.assignmentId(),"assignment_versions")).isEqualTo(1);
        assertThat(count(command.assignmentId(),"assignment_publication_receipts")).isEqualTo(3);
        repository.fault=null;
        var b=service.publish(publish); assertThat(b.replayed()).isFalse();
        assertThat(service.publish(publish).replayed()).isTrue();
        assertThat(count(command.assignmentId(),"assignment_versions")).isEqualTo(2);
    }
    @Test void deniesStaleChangedReplayAndOperationKeyReuseWithoutWrites() {
        var command=createCommand(); var draft=service.create(command);
        rejected(()->service.save(new Save(command.assignmentId(),draft.version(),command.commandId(),command.scope(),changed())),CONFLICT);
        rejected(()->service.publish(new Publish(command.assignmentId(),draft.version(),command.commandId())),CONFLICT);
        var a=service.publish(publication(draft));
        rejected(()->service.publish(publication(draft)),STALE);
        rejected(()->service.save(new Save(command.assignmentId(),draft.version(),UUID.randomUUID(),command.scope(),changed())),STALE);
        assertThat(count(command.assignmentId(),"assignment_drafts")).isEqualTo(1);
        assertThat(service.current(command.assignmentId()).orElseThrow().captured()).isEqualTo(a.captured());
    }
    @Test void authenticatesAndChecksCurrentDedicatedRightsAndExactOwnership() {
        var command=createCommand(); var draft=service.create(command);
        login("other"); rejected(()->service.publish(publication(draft)),UNAVAILABLE);
        rejected(()->service.draft(command.assignmentId()),UNAVAILABLE);
        // A valid control for that same principal, permission and source scope.
        assertThat(service.create(createCommand()).captured().snapshot().ownerId()).isEqualTo(OTHER);
        login("author");
        sql.update("DELETE rp FROM role_permissions rp JOIN permissions p ON p.permission_id=rp.permission_id WHERE role_id=? AND p.permission_name='ASSIGNMENT_PUBLISH'",fixtureRole);
        rejected(()->service.publish(publication(draft)),UNAVAILABLE);
        SecurityContextHolder.clearContext(); rejected(()->service.publish(publication(draft)),UNAUTHENTICATED);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("author",null,List.of()));
        rejected(()->service.publish(publication(draft)),UNAUTHENTICATED);
        login("author"); sql.update("UPDATE users SET is_active=false WHERE user_id=UUID_TO_BIN(?)",OWNER.toString());
        rejected(()->service.save(new Save(command.assignmentId(),draft.version(),UUID.randomUUID(),command.scope(),changed())),UNAVAILABLE);
        assertThat(count(command.assignmentId(),"assignment_versions")).isZero();
    }
    @Test void organizationScopeRequiresCurrentMembershipWithoutAnAdminBypass() {
        var command=createCommand(); var scoped=new Save(command.assignmentId(),0,command.commandId(),new Scope(ScopeKind.ORGANIZATION,ORGANIZATION),command.definition());
        var draft=service.create(scoped); organizations.clear();
        rejected(()->service.publish(publication(draft)),UNAVAILABLE);
        rejected(()->service.current(command.assignmentId()),UNAVAILABLE);
        organizations.add(ORGANIZATION); assertThat(service.publish(publication(draft)).captured().snapshot().scope()).isEqualTo(scoped.scope());
    }
    @Test void teacherAuthorityInSchoolADoesNotPermitCreatingInSchoolBAsAStudent() {
        var schoolB=UUID.randomUUID(); organizations.add(schoolB);
        bindScope(OWNER,studentRole,ScopeKind.ORGANIZATION,schoolB);
        var a=createCommand();
        var allowed=new Save(a.assignmentId(),0,a.commandId(),new Scope(ScopeKind.ORGANIZATION,ORGANIZATION),a.definition());
        assertThat(service.create(allowed).captured().snapshot().scope()).isEqualTo(allowed.scope());
        var b=new Save(UUID.randomUUID(),0,UUID.randomUUID(),new Scope(ScopeKind.ORGANIZATION,schoolB),a.definition());
        rejected(()->service.create(b),UNAVAILABLE);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM assignments WHERE id=UUID_TO_BIN(?)",Integer.class,b.assignmentId().toString())).isZero();
        for(String table:List.of("assignment_drafts","assignment_versions","assignment_publication_heads","assignment_publication_receipts"))
            assertThat(count(b.assignmentId(),table)).isZero();
    }
    @Test void losingScopedAuthorityDeniesWritesReadsAndExactReplayWhileMembershipRemains() {
        var schoolB=UUID.randomUUID(); organizations.add(schoolB);
        bindScope(OWNER,fixtureRole,ScopeKind.ORGANIZATION,schoolB);
        bindScope(OWNER,studentRole,ScopeKind.ORGANIZATION,schoolB);
        var original=createCommand();
        var createB=new Save(original.assignmentId(),0,original.commandId(),new Scope(ScopeKind.ORGANIZATION,schoolB),original.definition());
        var draft=service.create(createB);
        var publishB=publication(draft); var published=service.publish(publishB);
        var editB=new Save(createB.assignmentId(),published.version(),UUID.randomUUID(),createB.scope(),changed());
        var edited=service.save(editB);
        // These successful controls reach the same reads/replays without consulting the source verifier.
        sourceAvailable.set(false);
        assertThat(service.draft(createB.assignmentId()).captured()).isEqualTo(edited.captured());
        assertThat(service.current(createB.assignmentId()).orElseThrow().captured()).isEqualTo(published.captured());
        assertThat(service.read(createB.assignmentId(),published.captured().snapshot().id()).captured()).isEqualTo(published.captured());
        assertThat(service.create(createB).replayed()).isTrue();
        assertThat(service.save(editB).replayed()).isTrue();
        assertThat(service.publish(publishB).replayed()).isTrue();
        var before=assignmentState(createB.assignmentId());
        sql.update("DELETE FROM fixture_assignment_role_scopes WHERE actor_id=UUID_TO_BIN(?) AND role_id=? AND scope_id=UUID_TO_BIN(?)",
                OWNER.toString(),fixtureRole,schoolB.toString());
        assertThat(organizations).contains(ORGANIZATION,schoolB);
        for(Runnable command:List.<Runnable>of(
                ()->service.draft(createB.assignmentId()),()->service.current(createB.assignmentId()),
                ()->service.read(createB.assignmentId(),published.captured().snapshot().id()),
                ()->service.create(createB),()->service.save(editB),()->service.publish(publishB),
                ()->service.save(new Save(createB.assignmentId(),edited.version(),UUID.randomUUID(),createB.scope(),createB.definition())),
                ()->service.publish(publication(edited)))) rejected(command,UNAVAILABLE);
        assertThat(assignmentState(createB.assignmentId())).isEqualTo(before);
        // School A remains a successful control for that actor and the same current role permissions.
        sourceAvailable.set(true);
        var a=createCommand();
        assertThat(service.create(new Save(a.assignmentId(),0,a.commandId(),new Scope(ScopeKind.ORGANIZATION,ORGANIZATION),a.definition()))
                .captured().snapshot().scope().organizationId()).isEqualTo(ORGANIZATION);
    }
    @Test void aCallerCannotSubstituteSchoolAForThePersistedSchoolBDuringSaveOrCreateReplay() {
        var schoolB=UUID.randomUUID(); organizations.add(schoolB);
        bindScope(OWNER,fixtureRole,ScopeKind.ORGANIZATION,schoolB);
        var original=createCommand();
        var createB=new Save(original.assignmentId(),0,original.commandId(),new Scope(ScopeKind.ORGANIZATION,schoolB),original.definition());
        var draft=service.create(createB);
        sql.update("DELETE FROM fixture_assignment_role_scopes WHERE scope_id=UUID_TO_BIN(?)",schoolB.toString());
        var scopeA=new Scope(ScopeKind.ORGANIZATION,ORGANIZATION);
        authority.requests.clear();
        rejected(()->service.save(new Save(createB.assignmentId(),draft.version(),UUID.randomUUID(),scopeA,createB.definition())),UNAVAILABLE);
        rejected(()->service.create(new Save(createB.assignmentId(),0,createB.commandId(),scopeA,createB.definition())),UNAVAILABLE);
        assertThat(authority.requests).hasSize(2).allSatisfy(request->{
            assertThat(request.actorId()).isEqualTo(OWNER);
            assertThat(request.assignmentId()).isEqualTo(createB.assignmentId());
            assertThat(request.scope()).isEqualTo(createB.scope());
        });
        assertThat(count(createB.assignmentId(),"assignment_publication_receipts")).isEqualTo(1);
    }
    @Test void missingOrAmbiguousAuthorityNeverFallsBackToGlobalRolesForReadsOrReplay() {
        var create=createCommand(); var draft=service.create(create); var publish=publication(draft); var published=service.publish(publish);
        var before=assignmentState(create.assignmentId());
        for(List<AssignmentManagementAuthority> providers:List.of(List.<AssignmentManagementAuthority>of(),List.<AssignmentManagementAuthority>of(authority,authority))) {
            service=build(repository,List.of(verifier()),providers);
            var fresh=createCommand();
            for(Runnable command:List.<Runnable>of(()->service.create(fresh),()->service.create(create),
                    ()->service.save(new Save(create.assignmentId(),published.version(),UUID.randomUUID(),create.scope(),create.definition())),
                    ()->service.publish(publish),()->service.draft(create.assignmentId()),()->service.current(create.assignmentId()),
                    ()->service.read(create.assignmentId(),published.captured().snapshot().id()))) rejected(command,UNAVAILABLE);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM assignments WHERE id=UUID_TO_BIN(?)",Integer.class,fresh.assignmentId().toString())).isZero();
        }
        assertThat(assignmentState(create.assignmentId())).isEqualTo(before);
    }
    Map<String,Long> assignmentState(UUID assignment) {
        var state=new TreeMap<String,Long>();
        for(String table:List.of("assignment_drafts","assignment_versions","assignment_publication_heads","assignment_publication_receipts"))
            state.put(table,(long)count(assignment,table));
        state.put("version",sql.queryForObject("SELECT row_version FROM assignment_publication_heads WHERE assignment_id=UUID_TO_BIN(?)",Long.class,assignment.toString()));
        return state;
    }
    @Test void missingAmbiguousRevokedAndForeignSourcesFailClosedAndCannotCaptureLegacyIdentities() {
        var command=createCommand();
        service=build(repository,List.of()); rejected(()->service.create(command),INVALID_REFERENCE);
        assertThat(count(command.assignmentId(),"assignment_drafts")).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM assignments WHERE id=UUID_TO_BIN(?)",Integer.class,command.assignmentId().toString())).isZero();
        service=build(repository,List.of(verifier(),verifier())); rejected(()->service.create(command),INVALID_REFERENCE);
        service=build(repository,List.of(verifier()));
        var d=command.definition(); var foreign=new Definition(UUID.randomUUID(),1,d.policy(),d.audience(),d.availability(),d.capacity(),d.guests());
        rejected(()->service.create(new Save(command.assignmentId(),0,command.commandId(),command.scope(),foreign)),INVALID_REFERENCE);
        verified.put(foreign,Map.of(MEMBER,"Participant A"));
        rejected(()->service.create(new Save(command.assignmentId(),0,command.commandId(),command.scope(),foreign)),INVALID_REFERENCE);
        var draft=service.create(command); sourceAvailable.set(false);
        rejected(()->service.publish(publication(draft)),INVALID_REFERENCE);
        sourceAvailable.set(true); assertThat(service.publish(publication(draft)).replayed()).isFalse();
        rejected(()->service.create(new Save(ORPHAN,0,UUID.randomUUID(),command.scope(),d)),UNAVAILABLE);
        assertThat(count(ORPHAN,"assignment_publication_heads")).isZero();
    }
    @Test void participatesInOuterRcAndRollbackAndRejectsIncompatibleCallersBeforeSourceWork() {
        var draft=service.create(createCommand()); var command=publication(draft);
        var outer=new TransactionTemplate(manager); outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(status->{ assertThat(service.publish(command).replayed()).isFalse(); status.setRollbackOnly(); });
        assertThat(service.current(command.assignmentId())).isEmpty();
        assertThat(count(command.assignmentId(),"assignment_versions")).isZero();
        assertThat(service.publish(command).replayed()).isFalse();
        for(int isolation:List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_SERIALIZABLE,TransactionDefinition.ISOLATION_DEFAULT)) {
            outer.setIsolationLevel(isolation); outer.executeWithoutResult(status->rejected(()->service.publish(command),UNSUPPORTED_TRANSACTION));
        }
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); outer.setReadOnly(true);
        outer.executeWithoutResult(status->rejected(()->service.current(command.assignmentId()),UNSUPPORTED_TRANSACTION));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void concurrentPublicationHasOneWinnerOrOneExactReplay(boolean identical) throws Exception {
        var draft=service.create(createCommand()); var first=publication(draft);
        var second=identical?first:publication(draft);
        repository.held=new CountDownLatch(1); repository.release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var holder=pool.submit(()->asAuthor("publication-holder",()->service.publish(first)));
            await(repository.held);
            var peer=pool.submit(()->asAuthor("publication-peer",()-> {
                try { return (Object)service.publish(second); }
                catch(AssignmentPublicationException rejected) { return rejected; }
            }));
            waitForHeadLockWait();
            repository.release.countDown();
            var winner=holder.get(20,TimeUnit.SECONDS); var other=peer.get(20,TimeUnit.SECONDS);
            if(identical) assertThat(other).isEqualTo(new Result(winner.version(),winner.captured(),true));
            else assertThat(other).isInstanceOfSatisfying(AssignmentPublicationException.class,e->assertThat(e.reason()).isEqualTo(STALE));
            assertThat(count(first.assignmentId(),"assignment_versions")).isEqualTo(1);
            assertThat(count(first.assignmentId(),"assignment_publication_receipts")).isEqualTo(2);
            assertThat(service.current(first.assignmentId()).orElseThrow().captured()).isEqualTo(winner.captured());
        } finally { repository.release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void independentAssignmentOnTheSameSourceCommitsBeforeHolderIsReleased() throws Exception {
        var a=service.create(createCommand()); var b=service.create(createCommand());
        repository.held=new CountDownLatch(1); repository.release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var holder=pool.submit(()->asAuthor("publication-holder",()->service.publish(publication(a))));
            await(repository.held);
            var peer=pool.submit(()->asAuthor("publication-peer",()->service.publish(publication(b))));
            var committed=peer.get(20,TimeUnit.SECONDS);
            assertThat(service.current(b.captured().snapshot().assignmentId()).orElseThrow().captured()).isEqualTo(committed.captured());
            assertThat(repository.release.getCount()).isEqualTo(1);
            repository.release.countDown();
            assertThat(holder.get(20,TimeUnit.SECONDS).replayed()).isFalse();
        } finally { repository.release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void caughtParticipatingFailureMakesOuterTransactionRollbackOnly() {
        var draft=service.create(createCommand()); var command=publication(draft);
        repository.fault=Fault.VERSION_INSERT;
        var outer=new TransactionTemplate(manager); outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(()->outer.executeWithoutResult(status->{
            rejected(()->service.publish(command),UNAVAILABLE);
            assertThat(status.isRollbackOnly()).isTrue();
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(repository.reached.get()).isTrue();
        assertThat(service.current(command.assignmentId())).isEmpty();
        repository.fault=null; assertThat(service.publish(command).replayed()).isFalse();
    }
    @Test void pointersCannotReferenceAnotherAssignmentAndHistoricalReadsAreScoped() {
        var a=service.publish(publication(service.create(createCommand())));
        var b=service.publish(publication(service.create(createCommand())));
        var id=a.captured().snapshot().assignmentId(); var foreign=b.captured().snapshot().id();
        rejected(()->service.read(id,foreign),UNAVAILABLE);
        assertThatThrownBy(()->sql.update("UPDATE assignment_publication_heads SET publication_id=UUID_TO_BIN(?) WHERE assignment_id=UUID_TO_BIN(?)",
                foreign.toString(),id.toString())).isInstanceOf(DataAccessException.class);
        assertThat(service.current(id).orElseThrow().captured()).isEqualTo(a.captured());
    }
    @Test void rejectsChangedSourceIdentityAndProtectsDraftCapturesAgainstOverwrite() {
        var draft=service.create(createCommand()); var command=publication(draft);
        verified.put(definition(),Map.of(MEMBER,"Changed source designation"));
        rejected(()->service.publish(command),INVALID_REFERENCE);
        assertThat(service.draft(command.assignmentId()).captured()).isEqualTo(draft.captured());
        assertThat(service.current(command.assignmentId())).isEmpty();
        verified.put(definition(),Map.of(MEMBER,"Participant A"));
        assertThatThrownBy(()->sql.update("UPDATE assignment_drafts SET owner_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)",
                OTHER.toString(),draft.captured().snapshot().id().toString())).isInstanceOf(DataAccessException.class);
        assertThat(count(command.assignmentId(),"assignment_versions")).isZero();
        assertThat(service.draft(command.assignmentId()).captured()).isEqualTo(draft.captured());
        assertThat(service.publish(command).replayed()).isFalse();
    }
    @Test void threeAssignmentsUseFourScalarReadsEachWithoutLoadingQuestionAggregates() {
        var publications=new ArrayList<Result>();
        for(int i=0;i<3;i++) publications.add(service.publish(publication(service.create(createCommand()))));
        assertThat(statements.get()).anyMatch(q->q.contains("SELECT id FROM quiz_revisions"));
        assertThat(statements.get()).noneMatch(q->q.contains("quiz_revision_items") || q.contains("question_revisions"));
        for(var publication:publications) {
            statements.get().clear();
            assertThat(service.current(publication.captured().snapshot().assignmentId()).orElseThrow().captured()).isEqualTo(publication.captured());
            assertThat(statements.get()).hasSize(4);
            assertThat(statements.get()).noneMatch(q->q.contains("quiz_revisions") || q.contains("quiz_revision_items") || q.contains("question_revisions"));
        }
    }
    static class CountingSource extends DriverManagerDataSource {
        CountingSource(String url,String user,String password) { super(url,user,password); }
        @Override public Connection getConnection() throws SQLException { return observe(super.getConnection()); }
        @Override public Connection getConnection(String user,String password) throws SQLException { return observe(super.getConnection(user,password)); }
        Connection observe(Connection connection) {
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement") && args[0] instanceof String query) statements.get().add(query);
                try { return method.invoke(connection,args); }
                catch(InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
    }
    static <T> T asAuthor(String thread,Supplier<T> work) {
        Thread.currentThread().setName(thread); login("author");
        try { return work.get(); } finally { SecurityContextHolder.clearContext(); }
    }
    static void waitForHeadLockWait() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline) {
            int waits=admin.queryForObject("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='assignment_publication_heads'
                    """,Integer.class,SCHEMA);
            if(waits>0) return;
            Thread.sleep(20);
        }
        fail("Publication peer never reached the assignment root lock");
    }

    class HookRepository extends JdbcAssignmentPublicationRepository {
        volatile Fault fault;
        final AtomicBoolean reached=new AtomicBoolean();
        volatile CountDownLatch held,release;
        HookRepository() { super(new NamedParameterJdbcTemplate(source),mapper); }
        @Override public void insert(Captured c) {
            super.insert(c);
            if(c.snapshot().status()==Status.PUBLISHED) {
                assertThat(sql.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("READ-COMMITTED");
                assertThat(count(c.snapshot().assignmentId(),"assignment_versions")).isGreaterThan(0);
                checkpoint(Fault.VERSION_INSERT);
                if(held!=null && Thread.currentThread().getName().equals("publication-holder")) { held.countDown(); await(release); }
            }
        }
        @Override public void point(Head before,Head after) { super.point(before,after); if(after.publicationId()!=null) checkpoint(Fault.POINTER_UPDATE); }
        @Override public void record(UUID id,UUID actor,UUID command,Receipt r,Instant now) {
            super.record(id,actor,command,r,now); if(r.operation()==Operation.PUBLISH) checkpoint(Fault.RECEIPT_INSERT);
        }
        void checkpoint(Fault at) { if(fault==at) { reached.set(true); throw new IllegalStateException("private source diagnostic must not escape"); } }
    }
    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(20,TimeUnit.SECONDS)).isTrue(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
