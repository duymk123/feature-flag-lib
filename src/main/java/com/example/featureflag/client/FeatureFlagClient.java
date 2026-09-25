package com.example.featureflag.client;

import com.example.featureflag.dto.FeatureFlagSyncRequest;
import com.example.featureflag.service.FeatureFlagConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

import static java.util.Collections.emptyMap;

/**
 * Client tiện ích cho các microservice gọi kiểm tra cờ nội bộ.
 * Các hàm chính:
 * - isEnabled(flagName): Kiểm tra 1 cờ bật/tắt trong code.
 * - evaluateAll(): Lấy toàn bộ cờ cho Frontend lúc đăng nhập.
 * - syncSnapshot(request): Đồng bộ snapshot cấu hình vào Database.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FeatureFlagClient {

    private final FeatureFlagConfigService featureFlagConfigService;

    /**
     * Kiểm tra cờ tính năng có đang BẬT cho người dùng/request hiện tại không.
     *
     * @param flagName tên cờ (ví dụ: "BUY_NOW", "ORDER_DETAIL")
     * @return true nếu BẬT, false nếu TẮT hoặc gặp lỗi ngoại lệ
     */
    public boolean isEnabled(String flagName) {
        try {
            boolean enabled = featureFlagConfigService.isEnabled(flagName);
            log.debug("Đánh giá cờ cục bộ [{}] -> Kết quả: {}", flagName, enabled);
            return enabled;
        } catch (RuntimeException e) {
            log.error("Lỗi khi đánh giá cờ cục bộ [{}]. Tự động Fallback: false (TẮT)", flagName, e);
            return false;
        }
    }

    /**
     * Đánh giá và trả về toàn bộ cờ tính năng hiện có trong hệ thống dưới dạng Map.
     * Thường dùng khi người dùng đăng nhập để đính kèm vào JSON trả về cho Frontend.
     *
     * @return Map&lt;String, Boolean&gt; danh sách cờ và trạng thái
     */
    public Map<String, Boolean> evaluateAll() {
        try {
            return featureFlagConfigService.evaluateAll();
        } catch (RuntimeException e) {
            log.error("Lỗi khi đánh giá toàn bộ cờ. Fallback: Map rỗng.", e);
            return emptyMap();
        }
    }

    /**
     * Đồng bộ trực tiếp dữ liệu snapshot vào Database.
     *
     * @param request DTO chứa snapshot cờ cần đồng bộ
     * @return Số lượng bản ghi cấu hình đã được đồng bộ
     */
    public int syncSnapshot(FeatureFlagSyncRequest request) {
        return featureFlagConfigService.syncSnapshot(request);
    }
}
