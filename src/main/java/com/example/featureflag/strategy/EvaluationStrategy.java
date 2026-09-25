package com.example.featureflag.strategy;

import java.util.List;
import java.util.Map;

/**
 * Giao diện định nghĩa một chiến lược đánh giá cờ tính năng (Strategy Pattern).
 * Mỗi chiến lược cụ thể (User, Role, IP, Release Date, Rollout) sẽ cài đặt interface này.
 */
public interface EvaluationStrategy {

    /**
     * Danh sách các mã định danh chiến lược mà class này đảm nhận xử lý.
     * Ví dụ: ["username", "users_by_name"]
     */
    List<String> getSupportedStrategyIds();

    /**
     * Thực hiện đánh giá điều kiện dựa trên tham số và ngữ cảnh.
     *
     * @param params Các tham số cấu hình của chiến lược từ DB/JSON.
     * @param context Ngữ cảnh chứa thông tin User, Roles, IP.
     * @return true nếu thỏa mãn điều kiện bật cờ; false nếu không thỏa mãn.
     */
    boolean evaluate(Map<String, String> params, EvaluationContext context);
}
