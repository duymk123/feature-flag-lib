package com.example.featureflag.strategy;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * Ngữ cảnh người dùng và request tại thời điểm đánh giá cờ tính năng.
 * Đóng gói an toàn để truyền giữa các luồng mà không phụ thuộc vào ThreadLocal.
 */
public record EvaluationContext(
        String username,
        Collection<? extends GrantedAuthority> authorities,
        String clientIp
) {
    public static EvaluationContext of(String username, Collection<? extends GrantedAuthority> authorities, String clientIp) {
        return new EvaluationContext(
                username,
                authorities != null ? List.copyOf(authorities) : List.of(),
                clientIp
        );
    }

    public static EvaluationContext empty() {
        return new EvaluationContext(null, List.of(), null);
    }
}
