package ru.beeline.staging.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class CamundaDataSourceConfig {

    @Bean(name = "camundaDataSourceProperties")
    @ConfigurationProperties("spring.datasource.camunda")
    public DataSourceProperties camundaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "camundaDataSource")
    @Primary
    public DataSource camundaDataSource() {
        return camundaDataSourceProperties().initializeDataSourceBuilder().build();
    }
}
