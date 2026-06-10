package ru.beeline.staging.worker;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;

@Component
public class ValidatorWorker extends AbstractWorker {

    @Override
    protected String topic() { return "validator"; }

    @Override
    protected String workerId() { return "staging-validator-worker"; }

    @Override
    protected void process(LockedExternalTask task) {
        String type = (String) task.getVariable("artifactType");
        String uid  = (String) task.getVariable("artifactUid");
        log.info("stage=validator, type={}, uid={}", type, uid);
        // TODO: validate raw data structure and business rules for this artifactType
    }
}
