package ru.beeline.staging.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("camundaTimerSchedules")
@Getter
public class CamundaTimerSchedules {

    private final String preAdapterSparxCycle;

    public CamundaTimerSchedules(
            @Value("${staging.camunda.timer.pre-adapter-sparx-cycle:R/PT6H}") String preAdapterSparxCycle) {
        this.preAdapterSparxCycle = preAdapterSparxCycle;
    }
}
