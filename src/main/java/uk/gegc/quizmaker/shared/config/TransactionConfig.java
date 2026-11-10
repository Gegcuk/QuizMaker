package uk.gegc.quizmaker.shared.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.shared.transaction.SafeJpaTransactionManager;

@Configuration
public class TransactionConfig {

    @Bean
    @ConditionalOnMissingBean(TransactionManager.class)
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        var manager = new SafeJpaTransactionManager();
        manager.setEntityManagerFactory(entityManagerFactory);
        // Retain Boot's transaction customizers and JpaTransactionManager's lifecycle/defaults.
        customizers.ifAvailable(value -> value.customize(manager));
        return manager;
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
