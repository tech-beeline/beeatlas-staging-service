/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging.config;

import java.util.Map;
import java.util.concurrent.Executor;

public class PipelineExecutors {

    private final Executor scanExecutor;
    private final Executor defaultArtifactExecutor;
    private final Map<String, Executor> artifactExecutorByConfigCode;

    public PipelineExecutors(Executor scanExecutor, Executor defaultArtifactExecutor,
                              Map<String, Executor> artifactExecutorByConfigCode) {
        this.scanExecutor = scanExecutor;
        this.defaultArtifactExecutor = defaultArtifactExecutor;
        this.artifactExecutorByConfigCode = artifactExecutorByConfigCode;
    }

    public Executor scan() {
        return scanExecutor;
    }

    public Executor artifact(String configCode) {
        return configCode == null ? defaultArtifactExecutor
                : artifactExecutorByConfigCode.getOrDefault(configCode, defaultArtifactExecutor);
    }
}
