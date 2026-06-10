package ru.beeline.staging;

import org.camunda.bpm.spring.boot.starter.annotation.EnableProcessApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableProcessApplication
@EnableScheduling
@SpringBootApplication
public class StagingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StagingServiceApplication.class, args);
    }
}
