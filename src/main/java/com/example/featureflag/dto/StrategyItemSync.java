package com.example.featureflag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Đại diện cho một chiến lược đánh giá cờ (Strategy).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StrategyItemSync {
    private String strategyId;
    private Map<String, String> params;
}