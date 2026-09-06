package com.eventlab.jobforge.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.eventlab.jobforge")
public class JobForgeApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobForgeApiApplication.class, args);
    }
}
