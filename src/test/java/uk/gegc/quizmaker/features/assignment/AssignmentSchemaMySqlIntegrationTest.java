package uk.gegc.quizmaker.features.assignment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.assignment.AssignmentFixtures.*;

/** Real migration/constraints, not proof of #621 publication or #534 starts. Own isolated MySQL catalog. */
@Tag("db-serial")
class AssignmentSchemaMySqlIntegrationTest {
    private static final String SCHEMA = "assignment_contract_" + UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate admin, sql;
    private static NamedParameterJdbcTemplate named;
    private static DriverManagerDataSource source;
    private static boolean created;
    private static Map<String, Object> legacyLinkBeforeUpgrade, revisionBeforeUpgrade;
    private static Map<String, Integer> assignmentsAfterUpgrade;
    private static final UUID LEGACY_QUIZ = UUID.randomUUID(), LEGACY_LINK = UUID.randomUUID();

    @BeforeAll
    static void connectAndMigrate() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        created = true;
        source = new DriverManagerDataSource(base + SCHEMA + options, user, password);
        sql = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
        migrate("108");
        sql.update("INSERT INTO users(user_id,username,email,password) VALUES(UUID_TO_BIN(?),'assignment-fixture','fixture@example.invalid','not-a-login')", OWNER.toString());
        sql.update("""
                INSERT INTO quizzes(quiz_id,creator_id,category_id,title,visibility,difficulty,status,
                    estimated_time_min,is_repetition_enabled,is_timer_enabled)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'Legacy quiz','PRIVATE','EASY','DRAFT',5,false,false)
                """, LEGACY_QUIZ.toString(), OWNER.toString(), "00000000-0000-0000-0000-000000000001");
        sql.update("""
                INSERT INTO share_links(id,quiz_id,created_by,token_hash,scope,one_time)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),REPEAT('a',64),'QUIZ_ATTEMPT_START',false)
                """, LEGACY_LINK.toString(), LEGACY_QUIZ.toString(), OWNER.toString());
        sql.update("""
                INSERT INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1,'Captured revision',?)
                """, CONTENT.toString(), LEGACY_QUIZ.toString(), OWNER.toString(), Timestamp.from(TIME));
        legacyLinkBeforeUpgrade = sql.queryForMap("SELECT * FROM share_links WHERE id=UUID_TO_BIN(?)", LEGACY_LINK.toString());
        revisionBeforeUpgrade = sql.queryForMap("SELECT * FROM quiz_revisions WHERE id=UUID_TO_BIN(?)", CONTENT.toString());
        migrate("109");
        assignmentsAfterUpgrade = assignmentCounts();
    }

    @AfterAll static void drop() { if (created) admin.execute("DROP DATABASE " + SCHEMA); }
    private static void migrate(String target) {
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target(target).load().migrate();
    }

    @Test
    void upgradeAndRepeatPreserveActualLegacyLinkAndRevisionWithoutInventingAssignments() {
        var beforeRepeat = assignmentCounts();
        migrate("109");
        migrate("109");
        assertThat(sql.queryForMap("SELECT * FROM share_links WHERE id=UUID_TO_BIN(?)", LEGACY_LINK.toString())).usingRecursiveComparison().isEqualTo(legacyLinkBeforeUpgrade);
        assertThat(sql.queryForMap("SELECT * FROM quiz_revisions WHERE id=UUID_TO_BIN(?)", CONTENT.toString())).usingRecursiveComparison().isEqualTo(revisionBeforeUpgrade);
        assertThat(assignmentsAfterUpgrade.values()).containsOnly(0);
        assertThat(assignmentCounts()).isEqualTo(beforeRepeat);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='109' AND success=1", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"assignment_drafts", "assignment_versions"})
    void storesExactVersionAndAllIndependentConfigurationFacts(String table) {
        var row = row(table);
        insert(table, row);
        var stored = sql.queryForMap("""
                SELECT BIN_TO_UUID(assignment_id) assignment_id, BIN_TO_UUID(owner_id) owner_id, scope_kind,
                    BIN_TO_UUID(organization_id) organization_id, BIN_TO_UUID(quiz_revision_id) quiz_revision_id,
                    preset_id,preset_version,audience_kind,BIN_TO_UUID(audience_source_version_id) source_version,
                    audience_members,opens_at,closes_at,capacity_limit,capacity_enforcement,guest_access,version,status
                FROM %s WHERE id=UUID_TO_BIN(?)
                """.formatted(table), row.get("id"));
        assertThat(stored).containsEntry("assignment_id", row.get("assignment_id")).containsEntry("owner_id", OWNER.toString())
                .containsEntry("organization_id", ORGANIZATION.toString()).containsEntry("quiz_revision_id", CONTENT.toString())
                .containsEntry("preset_id", "FREE_PRACTICE").containsEntry("preset_version", 1)
                .containsEntry("source_version", AUDIENCE.toString()).containsEntry("capacity_limit", 30)
                .containsEntry("capacity_enforcement", "HARD").containsEntry("status", row.get("status"));
        assertThat(stored.get("audience_members").toString()).contains(MEMBER.toString());
        assertThat(stored.get("opens_at").toString()).startsWith("2026-11-11");
    }

    @ParameterizedTest @ValueSource(strings = {"assignment_drafts", "assignment_versions"})
    void constraintsRejectUnknownVersionsMissingReferencesAndContradictoryConfiguration(String table) {
        var mutations = new ArrayList<Map<String, Object>>();
        for (String version : List.of("schema_version", "content_schema_version", "policy_schema_version", "audience_schema_version", "preset_version"))
            mutations.add(Map.of(version, 2));
        for (String required : List.of("owner_id", "quiz_revision_id", "audience_source_version_id", "audience_members", "recorded_at")) {
            var value = new HashMap<String, Object>(); value.put(required, null); mutations.add(value);
        }
        mutations.add(Map.of("version", 0));
        mutations.add(Map.of("status", "UNKNOWN"));
        mutations.add(Map.of("status", table.equals("assignment_drafts") ? "PUBLISHED" : "DRAFT"));
        mutations.add(Map.of("quiz_revision_id", UUID.randomUUID().toString()));
        mutations.add(Map.of("assignment_id", UUID.randomUUID().toString()));
        for (String policy : List.of("latest", "free_practice", "FREE_PRACTICE ", "FORMAL")) mutations.add(Map.of("preset_id", policy));
        mutations.add(Map.of("scope_kind", "PERSONAL"));
        mutations.add(Map.of("scope_kind", "UNKNOWN"));
        mutations.add(Map.of("audience_kind", "PUBLIC"));
        for (String members : List.of("null", "{}", "[null]", "[1]", "[\"not-a-user\"]", "[\"" + MEMBER + "\",\"" + MEMBER + "\"]"))
            mutations.add(Map.of("audience_members", members));
        mutations.add(Map.of("closes_at", Timestamp.from(TIME)));
        mutations.add(Map.of("closes_at", Timestamp.from(TIME.minusSeconds(1))));
        mutations.add(Map.of("capacity_limit", 0));
        mutations.add(Map.of("capacity_limit", 10001));
        mutations.add(Map.of("capacity_enforcement", "UNKNOWN"));
        var nullMode = new HashMap<String, Object>(); nullMode.put("capacity_enforcement", null); mutations.add(nullMode);
        var nullLimit = new HashMap<String, Object>(); nullLimit.put("capacity_limit", null); mutations.add(nullLimit);
        mutations.add(Map.of("guest_access", 2));
        mutations.add(Map.of("guest_display_name", "REQUIRED"));
        mutations.add(Map.of("guest_email", "OPTIONAL"));
        mutations.add(Map.of("guest_email", "UNKNOWN"));
        for (var mutation : mutations) {
            var row = row(table); row.putAll(mutation);
            int before = count(table);
            assertThatThrownBy(() -> insert(table, row)).as("Rejected fields %s in %s", mutation.keySet(), table)
                    .isInstanceOf(DataAccessException.class);
            assertThat(count(table)).isEqualTo(before);
        }
        // Negative cases all use the same supported insertion path as this positive control.
        insert(table, row(table));
    }

    @Test
    void supportsApprovedBoundsUnboundedCapacityEmptyRosterAndEveryGuestIdentityCombination() {
        for (var name : List.of("NONE", "OPTIONAL", "REQUIRED")) for (var email : List.of("NONE", "OPTIONAL", "REQUIRED", "REQUIRED_VERIFIED")) {
            var row = row("assignment_versions");
            row.put("guest_access", 1); row.put("guest_display_name", name); row.put("guest_email", email);
            row.put("scope_kind", "PERSONAL"); row.put("organization_id", null);
            row.put("capacity_limit", null); row.put("capacity_enforcement", null);
            row.put("opens_at", null); row.put("closes_at", null); row.put("audience_members", "[]");
            insert("assignment_versions", row);
        }
        for (int capacity : List.of(1, 10000)) for (String mode : List.of("HARD", "SOFT")) {
            var row = row("assignment_versions"); row.put("capacity_limit", capacity); row.put("capacity_enforcement", mode);
            row.put("audience_kind", "ROSTER"); insert("assignment_versions", row);
        }
    }

    @Test
    void publishedSnapshotsRejectMutationAndDeletionIncludingCapturedAudience() {
        var row = row("assignment_versions"); insert("assignment_versions", row);
        var before = sql.queryForMap("SELECT * FROM assignment_versions WHERE id=UUID_TO_BIN(?)", row.get("id"));
        for (String column : List.of("owner_id", "quiz_revision_id", "audience_members", "closes_at")) {
            assertThatThrownBy(() -> sql.update("UPDATE assignment_versions SET " + column + "=" + column + " WHERE id=UUID_TO_BIN(?)", row.get("id")))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("Published assignment is immutable");
        }
        assertThatThrownBy(() -> sql.update("DELETE FROM assignment_versions WHERE id=UUID_TO_BIN(?)", row.get("id")))
                .isInstanceOf(DataAccessException.class);
        assertThat(sql.queryForMap("SELECT * FROM assignment_versions WHERE id=UUID_TO_BIN(?)", row.get("id")))
                .usingRecursiveComparison().isEqualTo(before);
        assertThatThrownBy(() -> sql.update("DELETE FROM quiz_revisions WHERE id=UUID_TO_BIN(?)", CONTENT.toString()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void reachedWriteRollsBackIdentityAndSnapshotAndFreshRetrySucceeds() {
        var row = row("assignment_versions");
        // row() creates a committed parent for ordinary tests. Use a new parent created inside this transaction.
        UUID newParent = UUID.randomUUID(); row.put("assignment_id", newParent.toString());
        var reached = new AtomicBoolean();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            parent(newParent); insert("assignment_versions", row);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM assignment_versions WHERE id=UUID_TO_BIN(?)",
                    Integer.class, row.get("id"))).isEqualTo(1);
            reached.set(true);
            throw new IllegalStateException("assignment fixture checkpoint");
        })).hasMessage("assignment fixture checkpoint");
        assertThat(reached).isTrue();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM assignments WHERE id=UUID_TO_BIN(?)", Integer.class, newParent.toString())).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM assignment_versions WHERE id=UUID_TO_BIN(?)", Integer.class, row.get("id"))).isZero();
        tx.executeWithoutResult(status -> { parent(newParent); insert("assignment_versions", row); });
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM assignment_versions WHERE id=UUID_TO_BIN(?)", Integer.class, row.get("id"))).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateVersionHasOneWinnerAndCannotOverwriteAfterReconnect() throws Exception {
        var a = row("assignment_versions"); var b = new LinkedHashMap<>(a);
        b.put("id", UUID.randomUUID().toString());
        b.put("audience_source_version_id", UUID.randomUUID().toString()); b.put("audience_members", "[\"" + OTHER_MEMBER + "\"]");
        var start = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> contender(a, start));
            var second = workers.submit(() -> contender(b, start));
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            var restarted = new JdbcTemplate(new DriverManagerDataSource(source.getUrl(), source.getUsername(), source.getPassword()));
            assertThat(restarted.queryForObject("SELECT COUNT(*) FROM assignment_versions WHERE assignment_id=UUID_TO_BIN(?) AND version=1", Integer.class, a.get("assignment_id"))).isEqualTo(1);
            var winner = restarted.queryForMap("SELECT BIN_TO_UUID(id) id,audience_members FROM assignment_versions WHERE assignment_id=UUID_TO_BIN(?)", a.get("assignment_id"));
            assertThat(winner.get("audience_members").toString()).contains(winner.get("id").equals(a.get("id")) ? MEMBER.toString() : OTHER_MEMBER.toString());
        } finally { workers.shutdownNow(); }
    }

    @Test
    void indexesAndForeignKeysAreLocalToIdentityAndImmutableContent() {
        for (String table : List.of("assignment_drafts", "assignment_versions")) {
            assertThat(sql.queryForList("SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=?", String.class, table))
                    .contains("uq_" + (table.equals("assignment_drafts") ? "assignment_draft" : "assignment_version") + "_number",
                            "ix_" + (table.equals("assignment_drafts") ? "assignment_draft" : "assignment_version") + "_owner",
                            "ix_" + (table.equals("assignment_drafts") ? "assignment_draft" : "assignment_version") + "_organization",
                            "ix_" + (table.equals("assignment_drafts") ? "assignment_draft" : "assignment_version") + "_content");
            assertThat(sql.queryForList("SELECT referenced_table_name FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name=?", String.class, table))
                    .containsExactlyInAnyOrder("assignments", "quiz_revisions");
        }
    }

    @Test
    void aLaterSnapshotAndMutableQuizEditsDoNotRebindCapturedVersion() {
        var first = row("assignment_versions"); insert("assignment_versions", first);
        var later = new LinkedHashMap<>(first);
        UUID secondContent = UUID.randomUUID();
        sql.update("""
                INSERT INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1,'Later revision',?)
                """, secondContent.toString(), LEGACY_QUIZ.toString(), OWNER.toString(), Timestamp.from(TIME));
        later.put("id", UUID.randomUUID().toString()); later.put("version", 2);
        later.put("quiz_revision_id", secondContent.toString());
        later.put("audience_source_version_id", UUID.randomUUID().toString());
        later.put("audience_members", "[\"" + OTHER_MEMBER + "\"]");
        insert("assignment_versions", later);
        sql.update("UPDATE quizzes SET title='Changed draft' WHERE quiz_id=UUID_TO_BIN(?)", LEGACY_QUIZ.toString());
        var versions = sql.queryForList("""
                SELECT BIN_TO_UUID(v.quiz_revision_id) revision_id, q.title, v.audience_members
                FROM assignment_versions v JOIN quiz_revisions q ON q.id=v.quiz_revision_id
                WHERE v.assignment_id=UUID_TO_BIN(?) ORDER BY v.version
                """, first.get("assignment_id"));
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0)).containsEntry("revision_id", CONTENT.toString()).containsEntry("title", "Captured revision");
        assertThat(versions.get(0).get("audience_members").toString()).contains(MEMBER.toString()).doesNotContain(OTHER_MEMBER.toString());
        assertThat(versions.get(1)).containsEntry("revision_id", secondContent.toString()).containsEntry("title", "Later revision");
    }

    private boolean contender(Map<String, Object> row, CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try { new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(s -> insert("assignment_versions", row)); return true; }
        catch (org.springframework.dao.DuplicateKeyException expected) { return false; }
    }
    private static Map<String, Integer> assignmentCounts() {
        return Map.of("assignments", count("assignments"), "assignment_drafts", count("assignment_drafts"),
                "assignment_versions", count("assignment_versions"));
    }
    private static int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static void parent(UUID id) { sql.update("INSERT INTO assignments VALUES(UUID_TO_BIN(?),?)", id.toString(), Timestamp.from(TIME)); }
    private static Map<String, Object> row(String table) {
        UUID parent = UUID.randomUUID(); parent(parent);
        var value = new LinkedHashMap<String, Object>();
        value.put("id", UUID.randomUUID().toString()); value.put("assignment_id", parent.toString());
        value.put("version", 1); value.put("schema_version", 1); value.put("status", table.equals("assignment_drafts") ? "DRAFT" : "PUBLISHED");
        value.put("owner_id", OWNER.toString()); value.put("scope_kind", "ORGANIZATION"); value.put("organization_id", ORGANIZATION.toString());
        value.put("quiz_revision_id", CONTENT.toString()); value.put("content_schema_version", 1);
        value.put("policy_schema_version", 1); value.put("preset_id", "FREE_PRACTICE"); value.put("preset_version", 1);
        value.put("audience_schema_version", 1); value.put("audience_kind", "GROUP"); value.put("audience_source_version_id", AUDIENCE.toString());
        value.put("audience_members", "[\"" + MEMBER + "\"]"); value.put("opens_at", Timestamp.from(TIME)); value.put("closes_at", Timestamp.from(TIME.plusSeconds(3600)));
        value.put("capacity_limit", 30); value.put("capacity_enforcement", "HARD"); value.put("guest_access", 0);
        value.put("guest_display_name", "NONE"); value.put("guest_email", "NONE"); value.put("recorded_at", Timestamp.from(TIME));
        return value;
    }
    private static void insert(String table, Map<String, Object> row) {
        Set<String> ids = Set.of("id", "assignment_id", "owner_id", "organization_id", "quiz_revision_id", "audience_source_version_id");
        String columns = String.join(",", row.keySet());
        String values = row.keySet().stream().map(key -> ids.contains(key) ? "UUID_TO_BIN(:" + key + ")" : ":" + key)
                .collect(java.util.stream.Collectors.joining(","));
        named.update("INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")", row);
    }
}
