package ru.beeline.staging.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
}
