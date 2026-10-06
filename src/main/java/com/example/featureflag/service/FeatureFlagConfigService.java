package com.example.featureflag.service;

import com.example.featureflag.dto.FeatureFlagSyncRequest;

import java.util.Map;

public interface FeatureFlagConfigService {

    /** Lưu snapshot cờ mới vào DB cục bộ và làm mới cache cấu hình. */
    int syncSnapshot(FeatureFlagSyncRequest request);

    /** Đánh giá một flag theo context của request hiện tại. */
    boolean isEnabled(String flagName);

    /** Đánh giá toàn bộ flag cho context hiện tại, thường được gọi khi login hoặc refresh token. */
    Map<String, Boolean> evaluateAll();
}
