/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Slf4j
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
    public DataSource camundaDataSource() throws SQLException {
        HikariDataSource dataSource = (HikariDataSource) camundaDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setConnectionInitSql("SET search_path TO " + camundaSchema);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + camundaSchema);
            log.info("Ensured schema '{}' exists for the Camunda engine", camundaSchema);
        }

        return dataSource;
    }
}
