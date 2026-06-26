package ru.beeline.staging.worker;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PreAdapterAckWorker extends AbstractWorker {

    @Override
    protected String topic() { return "pre-adapter-ack"; }

    @Override
    protected String stageName() { return "pre-adapter"; }

    @Override
    protected String workerId() { return "staging-pre-adapter-ack-worker"; }

    @Override
    protected List<String> variablesToFetch() {
        return List.of("artifactType", "artifactUid", "metadataJson");
    }

    @Override
    protected Map<String, Object> process(LockedExternalTask task) {
        return null;
    }
}
