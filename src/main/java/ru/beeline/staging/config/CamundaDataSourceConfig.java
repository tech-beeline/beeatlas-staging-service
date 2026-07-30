package ru.beeline.staging.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class CamundaDataSourceConfig {

    @Value("${staging.camunda.schema}")
    private String camundaSchema;

    @Bean(name = "camundaDataSourceProperties")
    @ConfigurationProperties("spring.datasource.camunda")
    public DataSourceProperties camundaDataSourceProperties() {
        return new DataSourceProperties();
    }

    
    @Bean(name = "camundaDataSource")
    @Primary
    public DataSource camundaDataSource() {
        HikariDataSource dataSource = (HikariDataSource) camundaDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        // No ", public" fallback: on prod, public already hosts a fully-migrated Camunda install
        // belonging to a different microservice (schema history since 2024-08-16). If staging_camunda
        // has no tables yet, Camunda's own schema-version check resolves through search_path fallback,
        // finds public's ACT_GE_PROPERTY already there, concludes "already up to date", and silently
        // deploys everything into public instead of bootstrapping staging_camunda fresh. Keeping
        // search_path scoped to staging_camunda only forces a clean bootstrap in our own schema.
        dataSource.setConnectionInitSql("SET search_path TO " + camundaSchema);
        return dataSource;
    }
}
