package com.example.featureflag.strategy.impl;

import com.example.featureflag.strategy.EvaluationContext;
import com.example.featureflag.strategy.EvaluationStrategy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Chiến lược kiểm tra vai trò / quyền hạn người dùng (User Role / Authority).
 */
@Component
public class UserRoleStrategy implements EvaluationStrategy {

    private static final List<String> SUPPORTED_IDS = List.of("user-role", "user_role", "role");

    @Override
    public List<String> getSupportedStrategyIds() {
        return SUPPORTED_IDS;
    }

    @Override
    public boolean evaluate(Map<String, String> params, EvaluationContext context) {
        Collection<? extends GrantedAuthority> authorities = context.authorities();
        if (authorities == null || authorities.isEmpty()) {
            return false;
        }

        String roles = params.getOrDefault("roles", params.getOrDefault("role", params.getOrDefault("value", "")));
        if (roles == null || roles.isBlank()) {
            return false;
        }

        return Arrays.stream(roles.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(role -> authorities.stream().anyMatch(a ->
                        a.getAuthority().equalsIgnoreCase(role) ||
                        a.getAuthority().equalsIgnoreCase("ROLE_" + role)));
    }
}
