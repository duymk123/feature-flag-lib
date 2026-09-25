package com.example.featureflag.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * class này sẽ tự động:
 * 1. Kích hoạt Spring AOP Proxy (@EnableAspectJAutoProxy).
 * 2. Quét toàn bộ Component, Service, Client trong package "com.example.featureflag".
 * 3. Đăng ký package "com.example.featureflag" vào AutoConfigurationPackages để Spring Data JPA
 *    và Entity tự động nhận diện mà không ghi đè cấu hình JPA của ứng dụng chính.
 * 4. Cung cấp ThreadPoolTaskExecutor phục vụ đánh giá tính năng song song (Concurrent Feature Flag Evaluation).
 */
@AutoConfiguration
@EnableAspectJAutoProxy
@ComponentScan(basePackages = "com.example.featureflag")
@AutoConfigurationPackage(basePackages = "com.example.featureflag")
public class FeatureFlagAutoConfiguration {

    @Bean(name = "featureFlagExecutor")
    @ConditionalOnMissingBean(name = "featureFlagExecutor")
    public ThreadPoolTaskExecutor featureFlagExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // số thread tối thiểu luôn tồn tại trong pool, kể cả khi k có task nào đang chạy
        executor.setCorePoolSize(4);

        // số thread tối đa pool được phép tạo ra ( pool không tạo thêm thread khi vượt quá CorePoolSize, chỉ tạo thêm khi queue đầy
        executor.setMaxPoolSize(16);

        // hàng đợi task, khi tất cả core thread đều bận => có thể chứa queue task, => khi đó thread pool mới tạo thêm thread
        executor.setQueueCapacity(500);

        // Tiền tố tên thread (log hiển thị rõ [ff-eval-1], [ff-eval-2]...)
        executor.setThreadNamePrefix("ff-eval-");

        // Graceful shutdown: Đảm bảo hoàn tất các task dở dang khi tắt ứng dụng
        executor.setWaitForTasksToCompleteOnShutdown(true);

        executor.setAwaitTerminationSeconds(10);

        executor.initialize();
        return executor;
    }
}
