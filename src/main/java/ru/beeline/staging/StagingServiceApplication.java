/*
 * Copyright (c) 2024 PJSC VimpelCom
 */

package ru.beeline.staging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class StagingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StagingServiceApplication.class, args);
    }
}
