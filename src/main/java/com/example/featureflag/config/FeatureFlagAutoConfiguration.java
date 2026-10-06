package com.example.featureflag.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;

/**
 * class này sẽ tự động:
 * 1. Kích hoạt Spring AOP Proxy (@EnableAspectJAutoProxy).
 * 2. Quét toàn bộ Component, Service, Client trong package "com.example.featureflag".
 * 3. Đăng ký package "com.example.featureflag" vào AutoConfigurationPackages để Spring Data JPA
 *    và Entity tự động nhận diện mà không ghi đè cấu hình JPA của ứng dụng chính.
 * 4. Cung cấp ThreadPoolTaskExecutor phục vụ đánh giá từng feature flag song song có giới hạn.
 */
@AutoConfiguration
@EnableAspectJAutoProxy
@ComponentScan(basePackages = "com.example.featureflag")
@AutoConfigurationPackage(basePackages = "com.example.featureflag")
@EnableConfigurationProperties(FeatureFlagExecutorProperties.class)
public class FeatureFlagAutoConfiguration {

    @Bean(name = "featureFlagExecutor")
    @ConditionalOnMissingBean(name = "featureFlagExecutor")
    public ThreadPoolTaskExecutor featureFlagExecutor(FeatureFlagExecutorProperties properties) {
        if (properties.getCorePoolSize() < 1
                || properties.getMaxPoolSize() < properties.getCorePoolSize()
                || properties.getQueueCapacity() < 0
                || properties.getMaxPendingTasks() < 1
                || properties.getAwaitTerminationSeconds() < 0) {
            throw new IllegalArgumentException("Invalid feature-flag.executor configuration");
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // số thread tối thiểu luôn tồn tại trong pool, kể cả khi k có task nào đang chạy
        executor.setCorePoolSize(properties.getCorePoolSize());

        // số thread tối đa pool được phép tạo ra ( pool không tạo thêm thread khi vượt quá CorePoolSize, chỉ tạo thêm khi queue đầy
        executor.setMaxPoolSize(properties.getMaxPoolSize());

        // hàng đợi task, khi tất cả core thread đều bận => có thể chứa queue task, => khi đó thread pool mới tạo thêm thread
        executor.setQueueCapacity(properties.getQueueCapacity());

        // Tiền tố tên thread (log hiển thị rõ [ff-eval-1], [ff-eval-2]...)
        executor.setThreadNamePrefix(properties.getThreadNamePrefix());

        // Backpressure: khi pool đầy, chạy task trên thread submit để làm chậm producer
        // thay vì loại bỏ task. Khi app đã shutdown thì báo lỗi để future không bị treo.
        executor.setRejectedExecutionHandler((task, pool) -> {
            if (pool.isShutdown()) {
                throw new RejectedExecutionException("Feature flag executor is shutting down");
            }
            task.run();
        });

        // Graceful shutdown: Đảm bảo hoàn tất các task dở dang khi tắt ứng dụng
        executor.setWaitForTasksToCompleteOnShutdown(properties.isWaitForTasksToCompleteOnShutdown());

        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());

        executor.initialize();
        return executor;
    }
}
