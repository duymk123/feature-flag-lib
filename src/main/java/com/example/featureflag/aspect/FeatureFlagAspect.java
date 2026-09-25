package com.example.featureflag.aspect;

import com.example.featureflag.annotation.RequireFeature;
import com.example.featureflag.client.FeatureFlagClient;
import com.example.featureflag.exception.FeatureFlagDisabledException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**

 * Tự động chặn trước (@Before) khi bất kỳ method nào có gắn @RequireFeature được gọi.
 *
 * Cơ chế hoạt động:
 * 1. Lấy danh sách tên cờ từ requireFeature.features().
 * 2. Lặp qua từng cờ và gọi featureFlagClient.isEnabled(feature).
 * 3. Nếu bất kỳ cờ nào bị TẮT:
 * 4. Nếu tất cả cờ đều BẬT: Cho phép method tiếp tục thực thi nghiệp vụ bình thường.
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class FeatureFlagAspect {

    private final FeatureFlagClient featureFlagClient;

    @Before("@annotation(requireFeature)")
    public void checkFeatureFlag(JoinPoint joinPoint, RequireFeature requireFeature) {
        String[] features = requireFeature.features();

        if (features != null) {
            for (String feature : features) {
                // Kiểm tra trạng thái cờ
                if (!featureFlagClient.isEnabled(feature)) {
                    log.warn("Tính năng [{}] đang bị TẮT. Đã chặn gọi method: {}", 
                            feature, joinPoint.getSignature().toShortString());

                    // Ném exception để dừng thực thi method lập tức và trả về HTTP 400
                    throw new FeatureFlagDisabledException(HttpStatus.BAD_REQUEST, requireFeature.message());
                }
            }
        }
    }
}
