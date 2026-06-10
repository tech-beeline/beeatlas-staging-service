package ru.beeline.staging.worker;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.stereotype.Component;

@Component
public class SaverWorker extends AbstractWorker {

    @Override
    protected String topic() { return "saver"; }

    @Override
    protected String workerId() { return "staging-saver-worker"; }

    @Override
    protected void process(LockedExternalTask task) {
        String type = (String) task.getVariable("artifactType");
        String uid  = (String) task.getVariable("artifactUid");
        log.info("stage=saver, type={}, uid={}", type, uid);
        // TODO: persist canonical model to DB using type-specific saver registry
    }
}
