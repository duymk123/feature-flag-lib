package com.example.featureflag.strategy.impl;

import com.example.featureflag.strategy.EvaluationContext;
import com.example.featureflag.strategy.EvaluationStrategy;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Chiến lược kiểm tra danh sách trắng địa chỉ IP của Client (IP Whitelist).
 */
@Component
public class ClientIpStrategy implements EvaluationStrategy {

    private static final List<String> SUPPORTED_IDS = List.of("remote-client-ip", "ip_whitelist", "ip");

    @Override
    public List<String> getSupportedStrategyIds() {
        return SUPPORTED_IDS;
    }

    @Override
    public boolean evaluate(Map<String, String> params, EvaluationContext context) {
        String clientIp = context.clientIp();
        if (clientIp == null || clientIp.isBlank()) {
            return false;
        }

        String ips = params.getOrDefault("ips", params.getOrDefault("value", ""));
        if (ips == null || ips.isBlank()) {
            return false;
        }

        String normalizedClientIp = normalizeIp(clientIp);

        return Arrays.stream(ips.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::normalizeIp)
                .anyMatch(ip -> ip.equals(normalizedClientIp));
    }

    private String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return ip;
        }
        String normalized = ip.trim();
        try {
            InetAddress address = InetAddress.getByName(normalized);
            if (address.isLoopbackAddress()) {
                return "127.0.0.1";
            }
            if (normalized.startsWith("::ffff:")) {
                return normalized.substring(7);
            }
            return address.getHostAddress();
        } catch (Exception e) {
            return normalized;
        }
    }
}
