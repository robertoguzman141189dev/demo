package com.eventlab.jobforge.worker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class CountingProcessorConfiguration {

    @Bean
    @Primary
    public CountingTaskProcessor countingTaskProcessor(WorkerProperties properties) {
        return new CountingTaskProcessor(properties);
    }
}
