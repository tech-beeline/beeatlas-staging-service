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
        // No ", public" fallback: on prod, public already hosts a fully-migrated Camunda install
        // belonging to a different microservice (schema history since 2024-08-16). If staging_camunda
        // has no tables yet, Camunda's own schema-version check resolves through search_path fallback,
        // finds public's ACT_GE_PROPERTY already there, concludes "already up to date", and silently
        // deploys everything into public instead of bootstrapping staging_camunda fresh. Keeping
        // search_path scoped to staging_camunda only forces a clean bootstrap in our own schema.
        dataSource.setConnectionInitSql("SET search_path TO " + camundaSchema);

        // Camunda's own schema-update (camunda.bpm.database.schema-update) only creates TABLES
        // inside an already-existing schema — it never creates the schema/namespace itself. On a
        // brand-new database (fresh environment, or one where the schema was manually dropped) that
        // left search_path resolving nowhere useful and Camunda silently bootstrapping into whatever
        // schema happened to be next (see SFDM-3926 prod incident: it fell through to `public`, which
        // was already home to a different microservice's own Camunda install). CREATE SCHEMA IF NOT
        // EXISTS is schema-qualified and safe to run before the schema exists / before search_path
        // resolves to anything.
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + camundaSchema);
            log.info("Ensured schema '{}' exists for the Camunda engine", camundaSchema);
        }

        return dataSource;
    }
}
