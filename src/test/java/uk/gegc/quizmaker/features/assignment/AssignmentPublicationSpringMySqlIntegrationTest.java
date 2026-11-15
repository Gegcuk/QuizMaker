package uk.gegc.quizmaker.features.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.assignment.application.*;
import uk.gegc.quizmaker.features.assignment.application.impl.AssignmentPublicationServiceImpl;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublication.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException;
import uk.gegc.quizmaker.features.assignment.infra.repository.JdbcAssignmentPublicationRepository;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.config.TransactionConfig;
import uk.gegc.quizmaker.shared.security.MembershipResolver;
import uk.gegc.quizmaker.shared.transaction.SafeJpaTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.assignment.AssignmentFixtures.*;
import static uk.gegc.quizmaker.features.assignment.AssignmentPublicationMySqlIntegrationTest.login;
import static uk.gegc.quizmaker.features.assignment.AssignmentPublicationMySqlIntegrationTest.rejected;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException.Reason.*;

/** Production service/repository/identity proxies, real UserRepository and SafeJpaTransactionManager.
 * Source/membership and SQL-backed scope-role readers are explicit local integration fixtures.
 */
@Tag("db-serial")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties={"spring.jpa.hibernate.ddl-auto=none","spring.flyway.enabled=true",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners=AssignmentPublicationSpringMySqlIntegrationTest.SchemaCleanup.class,
        mergeMode=TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Import({AssignmentPublicationServiceImpl.class,JdbcAssignmentPublicationRepository.class,
        AuthenticatedUserLookupImpl.class,TransactionConfig.class,AssignmentPublicationSpringMySqlIntegrationTest.Config.class})
class AssignmentPublicationSpringMySqlIntegrationTest {
    static final String SCHEMA="assignment_spring_"+UUID.randomUUID().toString().replace("-","");
    static final UUID SCHOOL_B=UUID.randomUUID();
    static final String PORT=System.getenv().getOrDefault("TEST_MYSQL_PORT","3306");
    static final String ADMIN=System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME","root");
    static final String PASSWORD=System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD","root");
    static final String OPTIONS="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    static String server() {
        if(!PORT.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        return "jdbc:mysql://127.0.0.1:"+PORT+"/";
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->server()+SCHEMA+OPTIONS+"&createDatabaseIfNotExist=true");
        p.add("spring.datasource.username",()->ADMIN); p.add("spring.datasource.password",()->PASSWORD);
    }
    public static class SchemaCleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext ignored) {
            new JdbcTemplate(new DriverManagerDataSource(server()+OPTIONS,ADMIN,PASSWORD)).execute("DROP DATABASE IF EXISTS "+SCHEMA);
        }
    }
    static class State {
        final Set<UUID> memberships=new HashSet<>();
        final AtomicInteger sourceCalls=new AtomicInteger();
        final List<String> isolations=new ArrayList<>();
        boolean sourceAvailable;
        AssignmentAuthorityFixture scoped;
    }
    @TestConfiguration(proxyBeanMethods=false)
    @EnableMethodSecurity
    static class Config {
        @Bean State state() { return new State(); }
        @Bean Clock clock() { return Clock.fixed(TIME,ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean AssignmentManagementAuthority authority(JdbcTemplate sql,State state) {
            state.scoped=new AssignmentAuthorityFixture(sql);
            return context->{
                state.isolations.add(sql.queryForObject("SELECT @@transaction_isolation",String.class));
                return state.scoped.allows(context);
            };
        }
        @Bean MembershipResolver memberships(State state) {
            return new MembershipResolver() {
                @Override public boolean isMemberOfOrganization(UUID actor,UUID organization) {
                    return actor.equals(OWNER) && state.memberships.contains(organization);
                }
            };
        }
        @Bean AssignmentSourceVerifier sources(State state) {
            return (actor,scope,definition)->{
                state.sourceCalls.incrementAndGet();
                if(!state.sourceAvailable || !actor.equals(OWNER) || !definition.equals(definition()))
                    throw new AssignmentPublicationException(INVALID_REFERENCE);
                return Map.of(MEMBER,"Participant A");
            };
        }
    }
    @Autowired AssignmentPublicationService service;
    @Autowired AuthenticatedUserLookup users;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate sql;
    @Autowired DataSource dataSource;
    @Autowired State state;
    @MockitoSpyBean JdbcAssignmentPublicationRepository repository;
    @PersistenceContext EntityManager entityManager;

    @BeforeEach void fixtures() {
        state.memberships.clear(); state.memberships.addAll(Set.of(ORGANIZATION,SCHOOL_B));
        state.isolations.clear(); state.scoped.requests.clear(); state.sourceCalls.set(0); state.sourceAvailable=true;
        sql.update("INSERT IGNORE INTO users(user_id,username,email,password) VALUES(UUID_TO_BIN(?),'author','authority@example.invalid','not-a-login')",OWNER.toString());
        sql.update("INSERT IGNORE INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1,'Source fixture',?)",
                CONTENT.toString(),UUID.randomUUID().toString(),OWNER.toString(),Timestamp.from(TIME));
        sql.update("INSERT IGNORE INTO roles(role_name,description,is_default) VALUES('scope-teacher','Local fixture',false),('scope-student','Local fixture',false)");
        long teacher=sql.queryForObject("SELECT role_id FROM roles WHERE role_name='scope-teacher'",Long.class);
        long student=sql.queryForObject("SELECT role_id FROM roles WHERE role_name='scope-student'",Long.class);
        for(long role:List.of(teacher,student)) sql.update("INSERT IGNORE INTO user_roles(user_id,role_id) VALUES(UUID_TO_BIN(?),?)",OWNER.toString(),role);
        sql.update("INSERT IGNORE INTO role_permissions(role_id,permission_id) SELECT ?,permission_id FROM permissions WHERE permission_name IN ('ASSIGNMENT_CREATE','ASSIGNMENT_PUBLISH')",teacher);
        AssignmentAuthorityFixture.createTable(sql); sql.update("DELETE FROM fixture_assignment_role_scopes");
        AssignmentAuthorityFixture.bind(sql,OWNER,teacher,ScopeKind.ORGANIZATION,ORGANIZATION);
        AssignmentAuthorityFixture.bind(sql,OWNER,student,ScopeKind.ORGANIZATION,SCHOOL_B);
        login("author");
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }
    Save create(UUID organization) { return new Save(UUID.randomUUID(),0,UUID.randomUUID(),new Scope(ScopeKind.ORGANIZATION,organization),definition()); }
    Publish publish(Result draft) { return new Publish(draft.captured().snapshot().assignmentId(),draft.version(),UUID.randomUUID()); }
    int count(UUID id,String table) { return sql.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE assignment_id=UUID_TO_BIN(?)",Integer.class,id.toString()); }
    void restoredPoolIsolation() throws Exception {
        try(Connection connection=dataSource.getConnection()) { assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ); }
    }

    @Test void realProxiesAndJpaEnforceCurrentScopedAuthorityOnEveryOperationAndReplay() throws Exception {
        assertThat(manager).isInstanceOf(SafeJpaTransactionManager.class);
        assertThat(AopUtils.isAopProxy(service)).isTrue(); assertThat(AopUtils.isAopProxy(repository)).isTrue();
        assertThat(AopUtils.isAopProxy(users)).isTrue();
        var create=create(ORGANIZATION); var draft=service.create(create); var publish=publish(draft); var result=service.publish(publish);
        var edit=new Save(create.assignmentId(),result.version(),UUID.randomUUID(),create.scope(),create.definition());
        var saved=service.save(edit);
        state.sourceAvailable=false;
        assertThat(service.draft(create.assignmentId()).captured()).isEqualTo(saved.captured());
        assertThat(service.current(create.assignmentId()).orElseThrow().captured()).isEqualTo(result.captured());
        assertThat(service.read(create.assignmentId(),result.captured().snapshot().id()).captured()).isEqualTo(result.captured());
        assertThat(service.create(create).replayed()).isTrue(); assertThat(service.save(edit).replayed()).isTrue();
        assertThat(service.publish(publish).replayed()).isTrue();
        assertThat(state.scoped.requests).extracting(AssignmentManagementAuthority.Context::action).containsAll(EnumSet.allOf(AssignmentManagementAuthority.Action.class));
        assertThat(state.scoped.requests).allSatisfy(c->{ assertThat(c.actorId()).isEqualTo(OWNER); assertThat(c.assignmentId()).isEqualTo(create.assignmentId()); assertThat(c.scope()).isEqualTo(create.scope()); });
        sql.update("DELETE FROM fixture_assignment_role_scopes WHERE scope_id=UUID_TO_BIN(?)",ORGANIZATION.toString());
        for(Runnable operation:List.<Runnable>of(()->service.create(create),()->service.save(edit),()->service.publish(publish),
                ()->service.draft(create.assignmentId()),()->service.current(create.assignmentId()),
                ()->service.read(create.assignmentId(),result.captured().snapshot().id()))) rejected(operation,UNAVAILABLE);
        assertThat(count(create.assignmentId(),"assignment_publication_receipts")).isEqualTo(3);
        assertThat(count(create.assignmentId(),"assignment_versions")).isEqualTo(1);
        assertThat(state.isolations).containsOnly("READ-COMMITTED"); restoredPoolIsolation();
    }
    @Test void aStudentMembershipAndAGlobalTeacherRoleDoNotAuthorizeSchoolBCreation() {
        var b=create(SCHOOL_B); rejected(()->service.create(b),UNAVAILABLE);
        assertThat(state.sourceCalls).hasValue(0);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM assignments WHERE id=UUID_TO_BIN(?)",Integer.class,b.assignmentId().toString())).isZero();
        for(String table:List.of("assignment_drafts","assignment_versions","assignment_publication_heads","assignment_publication_receipts")) assertThat(count(b.assignmentId(),table)).isZero();
        assertThat(service.create(create(ORGANIZATION)).captured().snapshot().scope().organizationId()).isEqualTo(ORGANIZATION);
    }
    @Test void jdbcPublicationAndReceiptJoinTheJpaCallersRollback() throws Exception {
        var draft=service.create(create(ORGANIZATION)); var publish=publish(draft);
        var outer=new TransactionTemplate(manager); outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(status->{
            var owner=entityManager.find(User.class,OWNER); owner.setDisplayName("Unrelated caller change"); entityManager.flush();
            assertThat(((Number)entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue())
                    .isEqualTo(sql.queryForObject("SELECT CONNECTION_ID()",Long.class));
            assertThat(service.publish(publish).replayed()).isFalse(); assertThat(entityManager.contains(owner)).isTrue();
            assertThat(count(publish.assignmentId(),"assignment_versions")).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(service.current(publish.assignmentId())).isEmpty();
        assertThat(count(publish.assignmentId(),"assignment_versions")).isZero();
        assertThat(count(publish.assignmentId(),"assignment_publication_receipts")).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT display_name FROM users WHERE user_id=UUID_TO_BIN(?)",String.class,OWNER.toString())).isNull();
        assertThat(service.publish(publish).replayed()).isFalse(); assertThat(service.publish(publish).replayed()).isTrue();
        restoredPoolIsolation();
    }
    @Test void aReachedInsertFailureRollsBackThroughTheRepositoryAndJpaProxiesThenRetries() {
        var draft=service.create(create(ORGANIZATION)); var publish=publish(draft); var reached=new AtomicBoolean();
        JdbcAssignmentPublicationRepository target=AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(invocation->{
            invocation.callRealMethod(); Captured capture=invocation.getArgument(0);
            if(capture.snapshot().status()==Status.PUBLISHED) {
                assertThat(count(publish.assignmentId(),"assignment_versions")).isEqualTo(1);
                reached.set(true); throw new AssignmentPublicationException(UNAVAILABLE);
            }
            return null;
        }).when(target).insert(any(Captured.class));
        rejected(()->service.publish(publish),UNAVAILABLE); assertThat(reached).isTrue();
        assertThat(service.current(publish.assignmentId())).isEmpty(); assertThat(count(publish.assignmentId(),"assignment_versions")).isZero();
        assertThat(count(publish.assignmentId(),"assignment_publication_receipts")).isEqualTo(1);
        doCallRealMethod().when(target).insert(any(Captured.class));
        assertThat(service.publish(publish).replayed()).isFalse(); assertThat(service.publish(publish).replayed()).isTrue();
    }
    @Test void methodSecurityDeniesUnauthenticatedEntryBeforeTheAuthorityBoundary() {
        var create=create(ORGANIZATION); SecurityContextHolder.clearContext();
        assertThatThrownBy(()->service.create(create)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThat(state.scoped.requests).isEmpty(); assertThat(state.sourceCalls).hasValue(0);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM assignments WHERE id=UUID_TO_BIN(?)",Integer.class,create.assignmentId().toString())).isZero();
    }
    @Test void incompatibleJpaCallersAreRejectedWithoutInvokingTheAuthorityBoundary() {
        var create=create(ORGANIZATION); var outer=new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status->rejected(()->service.create(create),UNSUPPORTED_TRANSACTION));
        assertThat(state.scoped.requests).isEmpty(); assertThat(state.sourceCalls).hasValue(0);
    }
}
