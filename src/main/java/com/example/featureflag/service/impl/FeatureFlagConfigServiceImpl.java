package com.example.featureflag.service.impl;

import com.example.featureflag.dto.FeatureFlagSyncItem;
import com.example.featureflag.dto.FeatureFlagSyncRequest;
import com.example.featureflag.dto.StrategyItemSync;
import com.example.featureflag.entity.FeatureFlagConfig;
import com.example.featureflag.repository.FeatureFlagConfigRepo;
import com.example.featureflag.service.FeatureFlagConfigService;
import com.example.featureflag.strategy.EvaluationContext;
import com.example.featureflag.strategy.EvaluationStrategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Service lưu trữ và đánh giá cờ tính năng Feature Flag từ Database và In-Memory Cache cục bộ.
 *
 * <p>Kiến trúc tối ưu hóa hiệu năng cao:</p>
 * <ul>
 *   <li><b>Strategy Pattern</b>: Tách riêng các chiến lược đánh giá (Username, Role, IP, Release Date, Rollout)
 *       thành các class độc lập, tuân thủ nguyên lý Open/Closed Principle (OCP).</li>
 *   <li><b>In-Memory Cache (RAM)</b>: Lưu trữ toàn bộ cờ trên RAM để phục vụ {@code isEnabled()} và {@code evaluateAll()}
 *       với độ trễ siêu tốc (~ 0.001ms), triệt tiêu hoàn toàn nghẽn I/O Database.</li>
 *   <li><b>Context Snapshotting</b>: Trích xuất an toàn ngữ cảnh (Username, Roles, Client IP) tại Request Thread cha
 *       trước khi chuyển giao cho các Worker Thread.</li>
 *   <li><b>Xử lý song song qua ThreadPoolTaskExecutor</b>: Tận dụng Spring Thread Pool kiểm soát tài nguyên
 *       để đánh giá đồng thời các cờ.</li>
 * </ul>
 */
@Service
@Slf4j
public class FeatureFlagConfigServiceImpl implements FeatureFlagConfigService {

    private final FeatureFlagConfigRepo featureFlagConfigRepo;
    private final ObjectMapper objectMapper;
    private final Executor featureFlagExecutor;
    private final Map<String, EvaluationStrategy> strategyMap = new ConcurrentHashMap<>();

    /**
     * In-Memory Cache lưu trữ danh sách rules theo tên cờ (Key: UPPERCASE flagName).
     */
    private final Map<String, List<FeatureFlagConfig>> flagCache = new ConcurrentHashMap<>();

    public FeatureFlagConfigServiceImpl(
            FeatureFlagConfigRepo featureFlagConfigRepo,
            ObjectMapper objectMapper,
            @Qualifier("featureFlagExecutor") Executor featureFlagExecutor,
            List<EvaluationStrategy> strategies) {
        this.featureFlagConfigRepo = featureFlagConfigRepo;
        this.objectMapper = objectMapper;
        this.featureFlagExecutor = featureFlagExecutor;

        // Đăng ký toàn bộ các Strategy được Spring phát hiện tự động
        if (strategies != null) {
            for (EvaluationStrategy strategy : strategies) {
                for (String id : strategy.getSupportedStrategyIds()) {
                    strategyMap.put(id.toLowerCase(Locale.ROOT), strategy);
                }
            }
        }
        log.info("Feature Flag Service khởi tạo hoàn tất với {} loại chiến lược: {}",
                strategyMap.size(), strategyMap.keySet());
    }

    /**
     * Khởi tạo nạp dữ liệu từ Database vào In-Memory Cache ngay khi ứng dụng khởi động.
     */
    @PostConstruct
    public void initCache() {
        refreshCache();
    }

    /**
     * Nạp mới toàn bộ cờ từ Database vào In-Memory Cache RAM. ==> tránh tình trạng mỗi lần gọi .isEnable => request xuống db
     */
    public synchronized void refreshCache() {
        try {
            List<FeatureFlagConfig> allConfigs = featureFlagConfigRepo.findAll();
            Map<String, List<FeatureFlagConfig>> newCache = allConfigs.stream()
                    .collect(Collectors.groupingBy(c -> c.getFlagName().toUpperCase(Locale.ROOT)));

            flagCache.clear();
            flagCache.putAll(newCache);
            log.info("Đã đồng bộ {} cờ tính năng vào In-Memory Cache (RAM)", flagCache.size());
        } catch (Exception e) {
            log.warn("Chưa thể tải cờ vào Cache lúc khởi động (có thể DB chưa sẵn sàng): {}", e.getMessage());
        }
    }

    /**
     * Đồng bộ hóa toàn bộ bản snapshot cờ tính năng mới nhất vào Database và tự động làm mới Cache RAM.
     */
    @Override
    @Transactional
    public int syncSnapshot(FeatureFlagSyncRequest request) {
        if (request == null || request.getFeatures() == null) {
            log.warn("Payload snapshot cờ rỗng -> Bỏ qua đồng bộ");
            return 0;
        }

        String version = hasText(request.getVersion()) ? request.getVersion() : "v-" + System.currentTimeMillis();
        List<FeatureFlagConfig> configs = new ArrayList<>();

        for (FeatureFlagSyncItem feature : request.getFeatures()) {
            if (feature.getFlagName() == null || feature.getFlagName().isBlank()) {
                continue;
            }
            configs.add(toConfig(feature, version));
        }

        // 1. Lưu vào Database trong một Transaction duy nhất
        featureFlagConfigRepo.deleteAllInBatch();
        featureFlagConfigRepo.saveAll(configs);

        // 2. Làm mới In-Memory Cache ngay lập tức
        refreshCache();

        log.info("Đã đồng bộ {} cờ tính năng với phiên bản snapshot {} và cập nhật RAM", configs.size(), version);
        return configs.size();
    }

    /**
     * Đánh giá xem một cờ tính năng có đang BẬT (true) hay TẮT (false) đối với request hiện tại.
     * <p>Tốc độ siêu tốc O(1) từ In-Memory Cache mà không chạm vào Database.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(String flagName) {
        if (flagName == null || flagName.isBlank()) {
            return false;
        }

        String normalizedName = flagName.trim().toUpperCase(Locale.ROOT);
        List<FeatureFlagConfig> configs = flagCache.get(normalizedName);

        // Cache miss: Nếu cờ chưa có trong cache (ví dụ mới thêm mà chưa sync), đọc DB và cập nhật cache
        if (configs == null) {
            configs = featureFlagConfigRepo.findByFlagNameIgnoreCase(normalizedName);
            flagCache.put(normalizedName, configs != null ? configs : Collections.emptyList());
        }

        if (configs == null || configs.isEmpty()) {
            log.debug("Cờ [{}] không có cấu hình -> Mặc định: TẮT (false)", flagName);
            return false;
        }

        EvaluationContext context = captureContext();
        return configs.stream().anyMatch(c -> evaluateConfig(c, context));
    }

    /**
     * Đánh giá toàn bộ các cờ tính năng hiện có cho người dùng hiện tại.
     * <p>Đọc trực tiếp từ In-Memory Cache và đánh giá song song trên ThreadPoolTaskExecutor.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Boolean> evaluateAll() {
        // 1. Snapshot Context tại luồng cha
        EvaluationContext context = captureContext();

        // 2. Đọc snapshot cờ từ In-Memory Cache (nếu trống thì refresh một lần)
        Map<String, List<FeatureFlagConfig>> currentSnapshot = new HashMap<>(flagCache);
        if (currentSnapshot.isEmpty()) {
            refreshCache();
            currentSnapshot = new HashMap<>(flagCache);
            if (currentSnapshot.isEmpty()) {
                return Collections.emptyMap();
            }
        }

        // 3. Đánh giá song song trên ThreadPoolTaskExecutor
        Map<String, Boolean> result = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = currentSnapshot.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(entry -> CompletableFuture.runAsync(() -> {
                    String flagName = entry.getKey();
                    boolean enabled = entry.getValue().stream().anyMatch(rule ->
                            evaluateConfig(rule, context)
                    );
                    result.put(flagName, enabled);
                }, featureFlagExecutor))
                .toList();

        // Chờ toàn bộ các thread đánh giá xong
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return result;
    }

    /**
     * Đánh giá chi tiết một rule FeatureFlagConfig với ngữ cảnh context.
     */
    private boolean evaluateConfig(FeatureFlagConfig config, EvaluationContext context) {
        // Kiểm tra công tắc tổng
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return false;
        }

        List<StrategyItemSync> strategies = parseStrategies(config.getStrategies());

        //  Nếu công tắc tổng BẬT và không có chiến lược ràng buộc -> BẬT cho toàn bộ người dùng
        if (strategies.isEmpty()) {
            return true;
        }

        // 3. Đánh giá tập hợp các chiến lược theo logic AND / OR qua Strategy Pattern
        String logic = config.getStrategyLogic() != null ? config.getStrategyLogic() : "OR";
        if ("AND".equalsIgnoreCase(logic)) {
            return strategies.stream()
                    .allMatch(s -> evaluateSingleStrategy(s.getStrategyId(), s.getParams(), context));
        } else {
            return strategies.stream()
                    .anyMatch(s -> evaluateSingleStrategy(s.getStrategyId(), s.getParams(), context));
        }
    }

    /**
     * Ủy thác việc đánh giá chiến lược cho đúng Strategy class phụ trách (Strategy Pattern).
     */
    private boolean evaluateSingleStrategy(String strategyId, Map<String, String> params, EvaluationContext context) {
        if (strategyId == null)
            return false;

        String key = strategyId.toLowerCase(Locale.ROOT);
        EvaluationStrategy strategy = strategyMap.get(key);

        if (strategy != null) {
            Map<String, String> safeParams = params != null ? params : Collections.emptyMap();
            return strategy.evaluate(safeParams, context);
        }

        log.warn("Không tìm thấy chiến lược xử lý cho '{}' -> Mặc định coi như tắt (false)", strategyId);
        return false;
    }

    /**
     * Snapshot an toàn toàn bộ ngữ cảnh người dùng & request tại Request Thread hiện tại.
     */
    private EvaluationContext captureContext() {
        return EvaluationContext.of(
                getCurrentUsername(),
                getCurrentAuthorities(),
                resolveClientIp()
        );
    }

    /**
     * Parse chuỗi JSON cấu hình chiến lược từ DB thành danh sách đối tượng StrategyItemSync.
     */
    private List<StrategyItemSync> parseStrategies(String json) {
        if (!hasText(json)) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<StrategyItemSync>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("Không thể parse JSON chiến lược: {}", json);
            return List.of();
        }
    }

    /**
     * Lấy Username của tài khoản đang thực hiện request từ Spring Security Context.
     */
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return auth.getName();
    }

    /**
     * Lấy danh sách các quyền hạn / vai trò (Authorities/Roles) của User hiện tại từ Spring Security Context.
     */
    private Collection<? extends GrantedAuthority> getCurrentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return List.of();
        return auth.getAuthorities();
    }

    /**
     * Ánh xạ đối tượng DTO FeatureFlagSyncItem nhận từ file snapshot sang Entity FeatureFlagConfig để lưu trữ.
     */
    private FeatureFlagConfig toConfig(FeatureFlagSyncItem feature, String version) {
        FeatureFlagConfig config = new FeatureFlagConfig();
        config.setFlagName(feature.getFlagName().trim().toUpperCase(Locale.ROOT));
        config.setEnabled(feature.getEnabled());
        config.setStrategies(strategiesToJson(feature.getStrategies()));
        config.setStrategyLogic(feature.getStrategyLogic());
        config.setAppliedVersion(version);
        return config;
    }

    /**
     * Chuyển danh sách chiến lược thành chuỗi JSON để lưu gọn trong 1 cột database.
     */
    private String strategiesToJson(List<StrategyItemSync> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(strategies);
        } catch (JsonProcessingException e) {
            log.warn("Lỗi serialize strategies sang JSON", e);
            return null;
        }
    }

    /**
     * Trích xuất địa chỉ IP thực tế của Client gửi request từ HttpServletRequest hiện tại.
     */
    private String resolveClientIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return null;
        }

        HttpServletRequest request = servletAttrs.getRequest();
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (hasText(forwardedFor)) {
            return normalizeClientIp(forwardedFor.split(",")[0]);
        }
        return normalizeClientIp(request.getRemoteAddr());
    }

    private String normalizeClientIp(String ip) {
        if (!hasText(ip)) {
            return ip;
        }
        String normalizedIp = ip.trim();
        try {
            InetAddress address = InetAddress.getByName(normalizedIp);
            if (address.isLoopbackAddress()) {
                return "127.0.0.1";
            }
            if (normalizedIp.startsWith("::ffff:")) {
                return normalizedIp.substring(7);
            }
            return address.getHostAddress();
        } catch (Exception e) {
            return normalizedIp;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
