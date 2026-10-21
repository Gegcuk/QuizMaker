package uk.gegc.quizmaker.shared.testing;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class HikariCiEnvironmentContractTest {

    @Test
    void workflowPoolSettingsBindWithoutOpeningAConnection() throws Exception {
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<?, ?> workflow = yaml.load(Files.readString(Path.of(".github/workflows/ci.yml")));
        var jobs = (Map<?, ?>) workflow.get("jobs");
        var build = (Map<?, ?>) jobs.get("build-and-test");
        var environment = (Map<?, ?>) build.get("env");
        Map<String, Object> poolSettings = new LinkedHashMap<>();
        environment.forEach((name, value) -> {
            if (name.toString().startsWith("SPRING_DATASOURCE_HIKARI_")) {
                poolSettings.put(name.toString(), value.toString());
            }
        });
        assertThat(poolSettings).isNotEmpty();

        // Spring Boot selects its environment-variable mapper using this source name too.
        var source = new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, poolSettings);
        var binder = new Binder(ConfigurationPropertySources.from(source));
        try (var dataSource = spy(new HikariDataSource())) {
            // A malformed nested property can invoke getConnection() while binding.
            // Fail locally instead of opening a real JDBC connection in this unit test.
            doThrow(new SQLException("Property binding must not open JDBC connections"))
                    .when(dataSource).getConnection();

            assertThatCode(() -> binder.bind("spring.datasource.hikari", Bindable.ofInstance(dataSource)))
                    .doesNotThrowAnyException();
            verify(dataSource, never()).getConnection();
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(10);
            assertThat(dataSource.getMinimumIdle()).isEqualTo(2);
            assertThat(dataSource.getConnectionTimeout()).isEqualTo(30_000);
            assertThat(dataSource.getValidationTimeout()).isEqualTo(5_000);
        }
    }
}
