package com.openclaw.kbbridge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务配置。
 * <p>
 * 启用 Spring @Async 支持，配置入库任务专用线程池。
 * 线程池大小通过 kb.ingest.async-pool-size 配置（默认 4）。
 * </p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 入库任务异步执行线程池。
     *
     * @param kbProperties 统一配置
     * @return 配置好的线程池执行器
     */
    @Bean("ingestTaskExecutor")
    public Executor ingestTaskExecutor(KbProperties kbProperties) {
        int poolSize = kbProperties.getIngest().getAsyncPoolSize();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
