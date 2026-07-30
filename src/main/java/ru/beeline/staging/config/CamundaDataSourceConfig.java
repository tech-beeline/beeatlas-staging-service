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
        dataSource.setConnectionInitSql("SET search_path TO " + camundaSchema + ", public");
        return dataSource;
    }
}
