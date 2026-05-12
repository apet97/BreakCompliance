package me.apet97.breakcompliance.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded executor for background ingestion runs. The detailed-report
 * fetch can take 30s+ on large workspaces, and we now return 202 from the
 * controller after kicking the work off here so the sidebar can poll the
 * run state rather than block on a single synchronous request.
 *
 * <p>Pool sizing is deliberately small: Hikari is capped at 10 connections
 * (see {@code application.yaml}) and an async run claims a connection
 * during finalize. Keeping max threads ≤4 leaves plenty of pool headroom
 * for sync request handlers (session lookups, settings reads). The 50-task
 * queue absorbs short bursts; {@code CallerRunsPolicy} provides
 * back-pressure if a flood ever overruns the queue — the request thread
 * runs the task itself rather than dropping it.
 */
@Configuration
public class AsyncConfig {

    public static final String INGEST_EXECUTOR_BEAN = "ingestExecutor";

    @Bean(name = INGEST_EXECUTOR_BEAN)
    public Executor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ingest-");
        // CallerRunsPolicy: when the queue is full, run the task on the
        // submitting thread instead of dropping it. The controller path
        // gracefully degrades to sync (the 202 still goes out — just
        // after the work has already finished).
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
