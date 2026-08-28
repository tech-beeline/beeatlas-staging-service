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

// taskScheduler: without a bean here, all @Scheduled methods share Spring's single-threaded
// default and serialize against each other.
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

    // CallerRunsPolicy: if the queue is ever full, whoever tried to submit (a scheduler tick, a
    // scan fanning out its children) just runs the task itself instead of getting a
    // RejectedExecutionException — natural backpressure, nothing is lost or silently dropped.
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
