package ru.beeline.staging.worker;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TransformerWorker extends AbstractWorker {

    @Override
    protected String topic() { return "transformer"; }

    @Override
    protected String workerId() { return "staging-transformer-worker"; }

    @Override
    protected List<String> variablesToFetch() { return List.of("artifactType", "artifactUid"); }

    @Override
    protected void process(LockedExternalTask task) {
        String type = (String) task.getVariables().get("artifactType");
        String uid  = (String) task.getVariables().get("artifactUid");
        log.info("stage=transformer, type={}, uid={}", type, uid);
        // TODO: transform raw data to canonical model using type-specific transformer registry
    }
}
