/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnExpression("'${staging.sparx.datasource.url:}'.length() > 0")
public class SparxDataSourceConfig {

    @Value("${staging.sparx.datasource.url}")
    private String url;

    @Value("${staging.sparx.datasource.username}")
    private String username;

    @Value("${staging.sparx.datasource.password}")
    private String password;

    @Bean("sparxJdbcTemplate")
    JdbcTemplate sparxJdbcTemplate() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setReadOnly(true);
        config.setPoolName("sparx-pool");
        return new JdbcTemplate(new HikariDataSource(config));
    }
}
