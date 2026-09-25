package com.example.featureflag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Đại diện cho thông tin của 1 Feature Flag trong bản snapshot đồng bộ từ server trung tâm.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeatureFlagSyncItem {
    private String flagName;
    private Boolean enabled;
    private String strategyLogic;
    private String version;
    private List<StrategyItemSync> strategies;
}
