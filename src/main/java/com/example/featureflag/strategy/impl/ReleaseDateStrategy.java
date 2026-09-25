package com.example.featureflag.strategy.impl;

import com.example.featureflag.strategy.EvaluationContext;
import com.example.featureflag.strategy.EvaluationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Chiến lược bật cờ theo ngày giờ phát hành (Release Date).
 */
@Component
@Slf4j
public class ReleaseDateStrategy implements EvaluationStrategy {

    private static final List<String> SUPPORTED_IDS = List.of("release-date", "release_date");

    @Override
    public List<String> getSupportedStrategyIds() {
        return SUPPORTED_IDS;
    }

    @Override
    public boolean evaluate(Map<String, String> params, EvaluationContext context) {
        String dateStr = params.getOrDefault("date", params.getOrDefault("releaseDate", params.getOrDefault("value", "")));
        if (dateStr == null || dateStr.isBlank()) {
            return false;
        }

        try {
            String trimmed = dateStr.trim();
            LocalDateTime releaseDate = trimmed.length() <= 10
                    ? LocalDate.parse(trimmed).atStartOfDay()
                    : LocalDateTime.parse(trimmed.replace(" ", "T"));
            return LocalDateTime.now().isAfter(releaseDate);
        } catch (Exception e) {
            log.warn("Không thể parse ngày phát hành release-date: {}", dateStr);
            return false;
        }
    }
}
