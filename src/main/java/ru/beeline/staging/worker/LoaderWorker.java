package ru.beeline.staging.worker;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LoaderWorker extends AbstractWorker {

    @Override
    protected String topic() { return "loader"; }

    @Override
    protected String workerId() { return "staging-loader-worker"; }

    @Override
    protected List<String> variablesToFetch() { return List.of("artifactType", "artifactUid"); }

    @Override
    protected void process(LockedExternalTask task) {
        String type = (String) task.getVariables().get("artifactType");
        String uid  = (String) task.getVariables().get("artifactUid");
        log.info("stage=loader, type={}, uid={}", type, uid);
        // TODO: download raw bytes from MinIO, persist raw_data_refs, set rawDataRefId variable
    }
}
