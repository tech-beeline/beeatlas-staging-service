/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "staging.executor")
public class PipelineExecutorProperties {

    private int schedulerPoolSize = 10;
    private int scanPoolSize = 4;
    private int artifactPoolSize = 8;
    private int queueCapacity = 5000;

    // configuration.code -> pool size, for the configs that need more (or fewer) artifact threads
    // than artifactPoolSize. Example:
    //   staging.executor.artifact-pool-overrides.sparx-e2e-prod: 20
    private Map<String, Integer> artifactPoolOverrides = new HashMap<>();
}
