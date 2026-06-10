package ru.beeline.staging.worker;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;

@Component
public class PreAdapterWorker extends AbstractWorker {

    @Override
    protected String topic() { return "pre-adapter"; }

    @Override
    protected String workerId() { return "staging-pre-adapter-worker"; }

    @Override
    protected void process(LockedExternalTask task) {
        log.info("stage=pre-adapter, processInstance={}", task.getProcessInstanceId());
        // TODO: fetch data from source (Sparx/Grafana), store in MinIO, publish to staging.events
    }
}
