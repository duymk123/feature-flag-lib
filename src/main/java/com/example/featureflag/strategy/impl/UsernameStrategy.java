package com.example.featureflag.strategy.impl;

import com.example.featureflag.strategy.EvaluationContext;
import com.example.featureflag.strategy.EvaluationStrategy;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Chiến lược kiểm tra danh sách trắng tên người dùng (Username Whitelist).
 */
@Component
public class UsernameStrategy implements EvaluationStrategy {

    private static final List<String> SUPPORTED_IDS = List.of("username", "users_by_name");

    @Override
    public List<String> getSupportedStrategyIds() {
        return SUPPORTED_IDS;
    }

    @Override
    public boolean evaluate(Map<String, String> params, EvaluationContext context) {
        String currentUser = context.username();
        if (currentUser == null || currentUser.isBlank()) {
            return false;
        }

        String users = params.getOrDefault("users", params.getOrDefault("value", ""));
        if (users == null || users.isBlank()) {
            return false;
        }

        return Arrays.stream(users.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(u -> u.equalsIgnoreCase(currentUser));
    }
}
