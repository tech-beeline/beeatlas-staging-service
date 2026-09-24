/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@RequiredArgsConstructor
public class PipelineExecutorConfig {

    private final PipelineExecutorProperties props;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(props.getSchedulerPoolSize());
        scheduler.setThreadNamePrefix("staging-sched-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public PipelineExecutors pipelineExecutors() {
        Executor scanExecutor = buildExecutor("staging-scan-", props.getScanPoolSize());
        Executor defaultArtifactExecutor = buildExecutor("staging-artifact-", props.getArtifactPoolSize());

        Map<String, Executor> overrides = new HashMap<>();
        props.getArtifactPoolOverrides().forEach((configCode, poolSize) ->
                overrides.put(configCode, buildExecutor("staging-artifact-" + configCode + "-", poolSize)));

        return new PipelineExecutors(scanExecutor, defaultArtifactExecutor, overrides);
    }

    private ThreadPoolTaskExecutor buildExecutor(String threadNamePrefix, int poolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(props.getQueueCapacity());
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
