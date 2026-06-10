package ru.beeline.staging.worker;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;

@Component
public class TransformerWorker extends AbstractWorker {

    @Override
    protected String topic() { return "transformer"; }

    @Override
    protected String workerId() { return "staging-transformer-worker"; }

    @Override
    protected void process(LockedExternalTask task) {
        String type = (String) task.getVariable("artifactType");
        String uid  = (String) task.getVariable("artifactUid");
        log.info("stage=transformer, type={}, uid={}", type, uid);
        // TODO: transform raw data to canonical model using type-specific transformer registry
    }
}
