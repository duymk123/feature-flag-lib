package com.example.featureflag.strategy.impl;

import com.example.featureflag.strategy.EvaluationContext;
import com.example.featureflag.strategy.EvaluationStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Chiến lược triển khai theo tỷ lệ phần trăm (Gradual Rollout) dựa trên hash định danh (Username hoặc IP).
 */
@Component
public class GradualRolloutStrategy implements EvaluationStrategy {

    private static final List<String> SUPPORTED_IDS = List.of(
            "gradual-rollout", "gradual_rollout", "gradual_rollout_user_id", "rollout"
    );

    @Override
    public List<String> getSupportedStrategyIds() {
        return SUPPORTED_IDS;
    }

    @Override
    public boolean evaluate(Map<String, String> params, EvaluationContext context) {
        String pctStr = params.getOrDefault("percentage", params.getOrDefault("value", "0"));
        try {
            int percentage = Integer.parseInt(pctStr.trim().replace("%", ""));
            if (percentage <= 0) return false;
            if (percentage >= 100) return true;

            String id = context.username();
            if (id == null || id.isBlank()) {
                id = context.clientIp();
            }
            if (id == null || id.isBlank()) {
                return false;
            }

            int hash = Math.abs(id.hashCode()) % 100;
            return hash < percentage;
        } catch (Exception e) {
            return false;
        }
    }
}
