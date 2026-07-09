package ru.beeline.staging.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared thread pool the pipeline workers (Pre-Adapter/Adapter/Validator/Transformer/Saver) submit
 * fetched external tasks to, instead of processing them one-by-one on the @Scheduled poll() thread —
 * lets different artifactTypes and different products within the same scan run concurrently, bounded
 * by pool size rather than by BPMN cardinality (see artifact-pipeline-process.bpmn isSequential=false).
 */
@Configuration
public class WorkerExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    ExecutorService pipelineWorkerExecutor(@Value("${staging.worker.pool-size:10}") int poolSize) {
        return Executors.newFixedThreadPool(poolSize, new CustomizableThreadFactory("staging-worker-"));
    }
}
