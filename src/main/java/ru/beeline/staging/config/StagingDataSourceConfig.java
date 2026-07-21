package ru.beeline.staging.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(
        basePackages = "ru.beeline.staging.repository",
        entityManagerFactoryRef = "stagingEntityManagerFactory",
        transactionManagerRef = "stagingTransactionManager"
)
public class StagingDataSourceConfig {

    @Bean(name = "stagingDataSource")
    @ConfigurationProperties("spring.datasource.staging.hikari")
    public DataSource stagingDataSource() {
        return new HikariDataSource();
    }

    @Bean(name = "stagingEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean stagingEntityManagerFactory(
            @Qualifier("stagingDataSource") DataSource dataSource,
            EntityManagerFactoryBuilder builder) {
        return builder
                .dataSource(dataSource)
                .packages("ru.beeline.staging.domain")
                .persistenceUnit("staging")
                .build();
    }

    @Bean(name = "stagingTransactionManager")
    public PlatformTransactionManager stagingTransactionManager(
            @Qualifier("stagingEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    // Bound to the same DataSource bean as the JPA EntityManagerFactory above, so when invoked inside a
    // @Transactional method managed by stagingTransactionManager it shares that transaction's connection
    // and sees uncommitted writes made via JPA in the same transaction (identity-generated inserts flush
    // immediately, so this is safe to use right after repository.save() calls).
    @Bean(name = "stagingJdbcTemplate")
    public JdbcTemplate stagingJdbcTemplate(@Qualifier("stagingDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
