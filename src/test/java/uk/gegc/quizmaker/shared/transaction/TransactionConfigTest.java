package uk.gegc.quizmaker.shared.transaction;

import jakarta.persistence.EntityManagerFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizer;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.DefaultJpaDialect;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.shared.config.TransactionConfig;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionConfigTest {
    @Test void bootCustomizersAndTheSameFactoryDataSourceDialectAndSettingsAreRetained() {
        var provider=new SafeJpaTransactionManagerTest.Provider();
        var dataSource=mock(DataSource.class);var dialect=new DefaultJpaDialect();var initialized=new AtomicInteger();
        when(provider.factory.createEntityManager(anyMap())).thenAnswer(i->provider.factory.createEntityManager());
        TransactionManagerCustomizer<JpaTransactionManager> customize=manager->{
            manager.setDataSource(dataSource);manager.setJpaDialect(dialect);manager.setDefaultTimeout(17);
            manager.setValidateExistingTransaction(true);manager.setNestedTransactionAllowed(true);
            manager.setGlobalRollbackOnParticipationFailure(false);manager.setFailEarlyOnGlobalRollbackOnly(true);
            manager.setRollbackOnCommitFailure(true);
            manager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_ON_ACTUAL_TRANSACTION);
            manager.setJpaPropertyMap(Map.of("test-hint","fixture"));
            manager.setEntityManagerInitializer(em->initialized.incrementAndGet());
        };
        new ApplicationContextRunner().withUserConfiguration(TransactionConfig.class)
                .withBean(EntityManagerFactory.class,()->provider.factory)
                .withBean(TransactionManagerCustomizers.class,()->TransactionManagerCustomizers.of(List.of(customize)))
                .run(context->{
                    assertThat(context).hasSingleBean(PlatformTransactionManager.class);
                    var manager=context.getBean(SafeJpaTransactionManager.class);
                    assertThat(manager.getEntityManagerFactory()).isSameAs(provider.factory);
                    assertThat(manager.getDataSource()).isSameAs(dataSource);assertThat(manager.getJpaDialect()).isSameAs(dialect);
                    assertThat(manager.getDefaultTimeout()).isEqualTo(17);assertThat(manager.isValidateExistingTransaction()).isTrue();
                    assertThat(manager.isNestedTransactionAllowed()).isTrue();assertThat(manager.isGlobalRollbackOnParticipationFailure()).isFalse();
                    assertThat(manager.isFailEarlyOnGlobalRollbackOnly()).isTrue();assertThat(manager.isRollbackOnCommitFailure()).isTrue();
                    assertThat(manager.getTransactionSynchronization()).isEqualTo(AbstractPlatformTransactionManager.SYNCHRONIZATION_ON_ACTUAL_TRANSACTION);
                    assertThat(manager.getJpaPropertyMap()).containsExactlyEntriesOf(Map.of("test-hint","fixture"));
                    var template=context.getBean(TransactionTemplate.class);assertThat(template.getTransactionManager()).isSameAs(manager);
                    template.executeWithoutResult(status->{});
                    assertThat(initialized).hasValue(1);assertThat(provider.calls).contains("applied-0");
                });
    }
    @Test void defaultsMatchOrdinaryJpaManagerWithoutSelectingNewTransactionPolicy() {
        var factory=mock(EntityManagerFactory.class);
        var ordinary=new JpaTransactionManager();ordinary.setEntityManagerFactory(factory);ordinary.afterPropertiesSet();
        new ApplicationContextRunner().withUserConfiguration(TransactionConfig.class).withBean(EntityManagerFactory.class,()->factory)
                .run(context->{
                    var safe=context.getBean(SafeJpaTransactionManager.class);
                    assertThat(safe.getDefaultTimeout()).isEqualTo(ordinary.getDefaultTimeout());
                    assertThat(safe.isNestedTransactionAllowed()).isEqualTo(ordinary.isNestedTransactionAllowed());
                    assertThat(safe.isValidateExistingTransaction()).isEqualTo(ordinary.isValidateExistingTransaction());
                    assertThat(safe.isGlobalRollbackOnParticipationFailure()).isEqualTo(ordinary.isGlobalRollbackOnParticipationFailure());
                    assertThat(safe.isFailEarlyOnGlobalRollbackOnly()).isEqualTo(ordinary.isFailEarlyOnGlobalRollbackOnly());
                    assertThat(safe.isRollbackOnCommitFailure()).isEqualTo(ordinary.isRollbackOnCommitFailure());
                    assertThat(safe.getTransactionSynchronization()).isEqualTo(ordinary.getTransactionSynchronization());
                });
    }
    @Test void anExplicitExistingTransactionManagerStillTakesPrecedence() {
        var existing=mock(PlatformTransactionManager.class);
        new ApplicationContextRunner().withUserConfiguration(TransactionConfig.class)
                .withBean("existingManager",PlatformTransactionManager.class,()->existing)
                .run(context->{
                    assertThat(context).doesNotHaveBean(SafeJpaTransactionManager.class);
                    assertThat(context.getBean(TransactionTemplate.class).getTransactionManager()).isSameAs(existing);
                });
    }
}
