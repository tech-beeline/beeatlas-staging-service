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

    /**
     * Camunda's own auto schema-update DDL and runtime queries are unqualified, so they land
     * wherever the connection's search_path points — this puts Camunda's tables in their own
     * schema instead of the default one, which fdm-bpm's Camunda engine also writes into
     * (same shared bpm database). The schema must already exist; this connection only sets
     * search_path, it never issues CREATE SCHEMA.
     */
    @Bean(name = "camundaDataSource")
    @Primary
    public DataSource camundaDataSource() {
        HikariDataSource dataSource = (HikariDataSource) camundaDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setConnectionInitSql("SET search_path TO " + camundaSchema + ", public");
        return dataSource;
    }
}
