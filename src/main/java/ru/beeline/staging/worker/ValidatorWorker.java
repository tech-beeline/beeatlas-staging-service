package ru.beeline.staging.worker;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ValidatorWorker extends AbstractWorker {

    @Override
    protected String topic() { return "validator"; }

    @Override
    protected String workerId() { return "staging-validator-worker"; }

    @Override
    protected List<String> variablesToFetch() { return List.of("artifactType", "artifactUid"); }

    @Override
    protected void process(LockedExternalTask task) {
        String type = (String) task.getVariables().get("artifactType");
        String uid  = (String) task.getVariables().get("artifactUid");
        log.info("stage=validator, type={}, uid={}", type, uid);
        // TODO: validate raw data structure and business rules for this artifactType
    }
}
