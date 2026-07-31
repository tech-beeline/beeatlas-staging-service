/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class FlywayConfig {

    @Bean
    Flyway flyway(@Qualifier("stagingDataSource") DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas("staging")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(true)
                .load();
        flyway.migrate();
        return flyway;
    }
}
