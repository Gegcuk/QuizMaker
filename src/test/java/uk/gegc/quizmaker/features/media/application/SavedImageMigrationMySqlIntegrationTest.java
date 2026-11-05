package uk.gegc.quizmaker.features.media.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Tag("db-serial")
class SavedImageMigrationMySqlIntegrationTest {
    @Test
    void migrationPreservesLegacyAssetsWithoutInventingHistoryAndEnforcesBindingIdentity() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String name = "image_migration_" + UUID.randomUUID().toString().replace("-", "");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        try {
            var source = new DriverManagerDataSource(base + name + options, user, password);
            var sql = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("91").load().migrate();
            sql.update("""
                    INSERT INTO media_assets(asset_id, object_key, type, status, mime_type, size_bytes, width, height, created_by)
                    VALUES (UNHEX(LPAD('1',32,'0')), 'legacy/image.png', 'IMAGE', 'READY', 'image/png', 7, 640, 480, 'legacy-author')
                    """);
            var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("92").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(sql.queryForObject("SELECT library_removed FROM media_assets", Boolean.class)).isFalse();
            assertThat(sql.queryForObject("SELECT object_key FROM media_assets", String.class)).isEqualTo("legacy/image.png");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM saved_image_versions", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM saved_image_bindings", Integer.class)).isZero();
            sql.update("""
                    INSERT INTO saved_image_versions(id,asset_id,source_key,source_etag,object_key,mime_type,size_bytes,width,height,created_at,ready)
                    VALUES (UNHEX(LPAD('2',32,'0')),UNHEX(LPAD('1',32,'0')),'legacy/image.png','etag-A','retained/A','image/png',7,640,480,NOW(6),TRUE)
                    """);
            sql.update(bindingInsert("3", "1"));
            assertThatThrownBy(() -> sql.update(bindingInsert("4", "9")))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> sql.update(bindingInsert("4", "1")))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> sql.update("DELETE FROM saved_image_versions"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> sql.update("DELETE FROM media_assets"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM saved_image_bindings", Integer.class)).isEqualTo(1);
        } finally {
            admin.execute("DROP DATABASE " + name);
        }
    }

    private static String bindingInsert(String id, String asset) {
        return "INSERT INTO saved_image_bindings(id,question_id,asset_id,image_version_id,created_at) VALUES "
                + "(UNHEX(LPAD('" + id + "',32,'0')),UNHEX(LPAD('5',32,'0')),UNHEX(LPAD('" + asset
                + "',32,'0')),UNHEX(LPAD('2',32,'0')),NOW(6))";
    }
}
