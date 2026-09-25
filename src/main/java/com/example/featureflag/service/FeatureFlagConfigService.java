package com.example.featureflag.service;

import com.example.featureflag.dto.FeatureFlagSyncRequest;

import java.util.Map;

public interface FeatureFlagConfigService {

    /**
     * Nhận và lưu trữ bản snapshot cấu hình cờ mới nhất vào Database cục bộ (deleteAll & saveAll).
     */
    int syncSnapshot(FeatureFlagSyncRequest request);

    /**
     * Đánh giá trạng thái BẬT/TẮT của một cờ tính năng cho ngữ cảnh request hiện tại:
     *      Tự động trích xuất IP từ HttpServletRequest (hỗ trợ X-Forwarded-For).
     *      Tự động trích xuất Username và Roles từ Spring SecurityContext.
     *      Đánh giá các chiến lược theo logic cấu hình (AND/OR).
     */
    boolean isEnabled(String flagName);

    /**
     * Đánh giá toàn bộ danh sách cờ tính năng hiện có trong hệ thống và trả về json trong đăng nhập
     * Thường được gọi trong quá trình Login để trả về JSON trạng thái cờ cho Frontend lưu vào session/store.</p>
     */
    Map<String, Boolean> evaluateAll();
}