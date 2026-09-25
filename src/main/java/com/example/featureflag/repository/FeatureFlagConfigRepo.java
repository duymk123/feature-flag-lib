package com.example.featureflag.repository;

import com.example.featureflag.entity.FeatureFlagConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;


@Repository
public interface FeatureFlagConfigRepo extends JpaRepository<FeatureFlagConfig, String> {

    /**
     * Tìm tất cả cấu hình của một cờ cụ thể theo tên
     * Một cờ có thể có nhiều dòng cấu hình
     */
    List<FeatureFlagConfig> findByFlagNameIgnoreCase(String flagName);

    /**
     * Lấy danh sách tên tất cả các cờ duy nhất hiện có trong hệ thống để phục vụ hàm evaluateAll().
     */
    @Query("SELECT DISTINCT f.flagName FROM FeatureFlagConfig f")
    List<String> findDistinctFlagNames();
}
