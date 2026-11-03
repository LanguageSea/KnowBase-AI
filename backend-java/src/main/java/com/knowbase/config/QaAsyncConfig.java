package com.knowbase.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSE generation runs off the servlet request thread. Virtual threads keep the
 * implementation small while allowing each in-flight answer to block on its
 * upstream HTTP stream independently.
 */
@Configuration
public class QaAsyncConfig {

    @Bean(name = "qaStreamExecutor", destroyMethod = "close")
    public ExecutorService qaStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
