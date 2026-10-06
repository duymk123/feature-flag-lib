package com.example.featureflag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the executor used by evaluateAll(). */
@ConfigurationProperties(prefix = "feature-flag.executor")
public class FeatureFlagExecutorProperties {

    private int corePoolSize = 4;
    private int maxPoolSize = 8;
    private int queueCapacity = 16;
    private int maxPendingTasks = 24;
    private String threadNamePrefix = "ff-eval-";
    private boolean waitForTasksToCompleteOnShutdown = true;
    private int awaitTerminationSeconds = 10;

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getMaxPendingTasks() {
        return maxPendingTasks;
    }

    public void setMaxPendingTasks(int maxPendingTasks) {
        this.maxPendingTasks = maxPendingTasks;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public void setThreadNamePrefix(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }

    public boolean isWaitForTasksToCompleteOnShutdown() {
        return waitForTasksToCompleteOnShutdown;
    }

    public void setWaitForTasksToCompleteOnShutdown(boolean waitForTasksToCompleteOnShutdown) {
        this.waitForTasksToCompleteOnShutdown = waitForTasksToCompleteOnShutdown;
    }

    public int getAwaitTerminationSeconds() {
        return awaitTerminationSeconds;
    }

    public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
        this.awaitTerminationSeconds = awaitTerminationSeconds;
    }
}
