package ru.beeline.staging.worker;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

public abstract class AbstractWorker {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    protected ExternalTaskService externalTaskService;

    protected abstract String topic();

    protected abstract String workerId();

    protected abstract void process(LockedExternalTask task);

    @Scheduled(fixedDelayString = "${staging.worker.poll-interval-ms:500}")
    public void poll() {
        List<LockedExternalTask> tasks = externalTaskService
                .fetchAndLock(10, workerId())
                .topic(topic(), 30_000L)
                .execute();

        for (LockedExternalTask task : tasks) {
            try {
                process(task);
                externalTaskService.complete(task.getId(), workerId());
            } catch (Exception e) {
                log.error("Worker {} failed on task {}", workerId(), task.getId(), e);
                int retries = task.getRetries() != null ? Math.max(0, task.getRetries() - 1) : 2;
                externalTaskService.handleFailure(
                        task.getId(), workerId(), e.getMessage(), e.toString(), retries, 5_000L);
            }
        }
    }
}
