package com.example.featureflag.service.impl;

import com.example.featureflag.config.FeatureFlagExecutorProperties;
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
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FeatureFlagConfigServiceImpl implements FeatureFlagConfigService {

    private final FeatureFlagConfigRepo featureFlagConfigRepo;
    private final ObjectMapper objectMapper;
    private final Executor featureFlagExecutor;
    private final int maxPendingTasks;
    private final Map<String, EvaluationStrategy> strategyMap = new ConcurrentHashMap<>();

    /**
     * In-Memory Cache lưu trữ danh sách rules theo tên cờ (Key: UPPERCASE flagName).
     */
    private final Map<String, List<FeatureFlagConfig>> flagCache = new ConcurrentHashMap<>();

    /**
     * In-Memory Cache tra cứu nhanh cờ theo ID (Key: id) để phục vụ kiểm tra cờ cha O(1).
     */
    private final Map<String, FeatureFlagConfig> idCache = new ConcurrentHashMap<>();

    @Autowired
    public FeatureFlagConfigServiceImpl(
            FeatureFlagConfigRepo featureFlagConfigRepo,
            ObjectMapper objectMapper,
            @Qualifier("featureFlagExecutor") Executor featureFlagExecutor,
            List<EvaluationStrategy> strategies,
            FeatureFlagExecutorProperties executorProperties) {
        this.featureFlagConfigRepo = featureFlagConfigRepo;
        this.objectMapper = objectMapper;
        this.featureFlagExecutor = featureFlagExecutor;
        this.maxPendingTasks = executorProperties.getMaxPendingTasks();

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

    /** Constructor dùng khi khởi tạo service trực tiếp ngoài Spring. */
    public FeatureFlagConfigServiceImpl(
            FeatureFlagConfigRepo featureFlagConfigRepo,
            ObjectMapper objectMapper,
            @Qualifier("featureFlagExecutor") Executor featureFlagExecutor,
            List<EvaluationStrategy> strategies) {
        this(featureFlagConfigRepo, objectMapper, featureFlagExecutor, strategies, new FeatureFlagExecutorProperties());
    }

    // ========================================================================
    // CACHE LIFECYCLE: nạp cấu hình từ DB vào RAM khi khởi động hoặc đồng bộ
    // ========================================================================

    /** Nạp cache cấu hình ngay sau khi Spring khởi tạo service. */
    @PostConstruct
    public void initCache() {
        refreshCache();
    }

    /**
     * Nạp mới toàn bộ cờ từ Database vào In-Memory Cache RAM.
     */
    public synchronized void refreshCache() {
        try {
            List<FeatureFlagConfig> allConfigs = featureFlagConfigRepo.findAll();
            Map<String, List<FeatureFlagConfig>> newFlagCache = allConfigs.stream()
                    .collect(Collectors.groupingBy(c -> normalizeFlagName(c.getFlagName())));

            Map<String, FeatureFlagConfig> newIdCache = allConfigs.stream()
                    .filter(c -> hasText(c.getId()))
                    .collect(Collectors.toMap(FeatureFlagConfig::getId, c -> c, (existing, replacing) -> existing));

            flagCache.clear();
            flagCache.putAll(newFlagCache);

            idCache.clear();
            idCache.putAll(newIdCache);
            // Pre-parse strategies JSON once to avoid ObjectMapper.readValue() per evaluate call
            for (FeatureFlagConfig config : allConfigs) {
                config.setParsedStrategies(parseStrategies(config.getStrategies()));
            }

            log.info("Đã đồng bộ {} cờ tính năng vào In-Memory Cache (RAM)", flagCache.size());
        } catch (Exception e) {
            log.warn("Chưa thể tải cờ vào Cache lúc khởi động (có thể DB chưa sẵn sàng): {}", e.getMessage());
        }
    }

    /** Parse JSON strategies once when configuration enters the cache. */
    private List<StrategyItemSync> parseStrategies(String json) {
        if (!hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<StrategyItemSync>>() { });
        } catch (JsonProcessingException e) {
            log.error("Không thể parse JSON chiến lược: {}", json, e);
            throw new IllegalArgumentException("Lỗi cú pháp JSON chiến lược: " + json, e);
        }
    }

    // ========================================================================
    // SNAPSHOT SYNC: lưu snapshot mới vào DB rồi làm mới cache RAM
    // ========================================================================

    /** Đồng bộ snapshot cấu hình và cập nhật cache RAM sau khi lưu thành công. */
    @Override
    @Transactional
    public int syncSnapshot(FeatureFlagSyncRequest request) {
        if (request == null || request.getFeatures() == null) {
            log.warn("Payload snapshot cờ rỗng -> Bỏ qua đồng bộ");
            return 0;
        }

        String version = resolveSnapshotVersion(request);

        List<FeatureFlagConfig> existingConfigs = featureFlagConfigRepo.findAll();
        Map<String, FeatureFlagConfig> existingByName = indexConfigsByFlagName(existingConfigs);
        SnapshotMapping mapping = mapIncomingSnapshot(request.getFeatures(), version, existingByName);
        List<FeatureFlagConfig> toDelete = findRemovedConfigs(existingConfigs, mapping.incomingFlagNames());

        if (!toDelete.isEmpty()) {
            featureFlagConfigRepo.deleteAll(toDelete);
        }

        featureFlagConfigRepo.saveAll(mapping.configsToSave());
        featureFlagConfigRepo.flush();

        refreshCache();

        log.info("Đã đồng bộ {} cờ tính năng với phiên bản snapshot {} và cập nhật RAM",
                mapping.configsToSave().size(), version);
        return mapping.configsToSave().size();
    }

    /** Tạo lookup hiện tại để cập nhật entity cũ thay vì tạo trùng config. */
    private Map<String, FeatureFlagConfig> indexConfigsByFlagName(List<FeatureFlagConfig> configs) {
        return configs.stream()
                .collect(Collectors.toMap(
                        config -> normalizeFlagName(config.getFlagName()),
                        config -> config,
                        (existing, replacing) -> existing
                ));
    }

    /** Map các mục hợp lệ trong snapshot sang entity cần lưu và ghi nhận tên flag đầu vào. */
    private SnapshotMapping mapIncomingSnapshot(
            List<FeatureFlagSyncItem> features,
            String version,
            Map<String, FeatureFlagConfig> existingByName) {
        List<FeatureFlagConfig> configsToSave = new ArrayList<>();
        Set<String> incomingFlagNames = new HashSet<>();

        for (FeatureFlagSyncItem feature : features) {
            FeatureFlagConfig config = toConfig(feature, version, existingByName);
            if (config == null) {
                continue;
            }
            incomingFlagNames.add(normalizeFlagName(config.getFlagName()));
            configsToSave.add(config);
        }

        return new SnapshotMapping(configsToSave, incomingFlagNames);
    }

    /** Bỏ cấu hình cũ không còn xuất hiện trong snapshot mới. */
    private List<FeatureFlagConfig> findRemovedConfigs(
            List<FeatureFlagConfig> existingConfigs,
            Set<String> incomingFlagNames) {
        return existingConfigs.stream()
                .filter(config -> !incomingFlagNames.contains(normalizeFlagName(config.getFlagName())))
                .toList();
    }

    /** Chuyển một mục snapshot thành entity mới hoặc cập nhật entity hiện có. */
    private FeatureFlagConfig toConfig(
            FeatureFlagSyncItem feature,
            String version,
            Map<String, FeatureFlagConfig> existingByName) {
        if (feature == null || !hasText(feature.getFlagName())) {
            return null;
        }

        String flagName = normalizeFlagName(feature.getFlagName());
        FeatureFlagConfig config = existingByName.get(flagName);
        if (config == null) {
            config = new FeatureFlagConfig();
            if (hasText(feature.getId())) {
                config.setId(feature.getId().trim());
            }
            config.setFlagName(flagName);
            config.setNewEntity(true);
        }

        config.setEnabled(Boolean.TRUE.equals(feature.getEnabled()));
        config.setParentId(hasText(feature.getParentId()) ? feature.getParentId().trim() : null);
        config.setStrategies(strategiesToJson(feature.getStrategies()));
        config.setStrategyLogic(feature.getStrategyLogic());
        config.setAppliedVersion(version);
        return config;
    }

    private String resolveSnapshotVersion(FeatureFlagSyncRequest request) {
        return hasText(request.getVersion()) ? request.getVersion() : "v-" + System.currentTimeMillis();
    }

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

    private record SnapshotMapping(
            List<FeatureFlagConfig> configsToSave,
            Set<String> incomingFlagNames) { }

    // ========================================================================
    // SINGLE-FLAG EVALUATION: public check and its evaluation helpers
    // ========================================================================

    /** Đánh giá một flag theo context của request hiện tại. */
    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(String flagName) {
        if (flagName == null || flagName.isBlank()) {
            return false;
        }

        String normalizedName = normalizeFlagName(flagName.trim());
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
        return configs.stream().anyMatch(c -> evaluateConfig(c, context, new HashSet<>()));
    }

    /** Đánh giá một config: công tắc, cờ cha và strategies AND/OR. */
    private boolean evaluateConfig(FeatureFlagConfig config, EvaluationContext context, Set<String> visited) {
        if (config == null) {
            return false;
        }
        try {
            // 1. Kiểm tra công tắc tổng của chính cờ này
            if (!Boolean.TRUE.equals(config.getEnabled())) {
                return false;
            }

            // 2. Kiểm tra quan hệ Cha - Con (Hierarchy / parentId):
            // Nếu có cờ cha -> cờ cha OFF kéo theo cờ con OFF, cờ cha ON mới check tiếp cờ con.
            if (hasText(config.getParentId())) {
                boolean parentActive = isParentActive(config.getParentId(), context, visited);
                if (!parentActive) {
                    log.debug("Cờ [{}] bị TẮT do cờ cha (parentId={}) đang TẮT", config.getFlagName(), config.getParentId());
                    return false;
                }
            }

            // 3. Nếu không có chiến lược ràng buộc -> BẬT cho toàn bộ người dùng
            List<StrategyItemSync> strategies = config.getParsedStrategies() != null ? config.getParsedStrategies() : parseStrategies(config.getStrategies());
            if (strategies.isEmpty()) {
                return true;
            }

            // 4. Đánh giá tập hợp các chiến lược theo logic AND / OR qua Strategy Pattern
            String logic = config.getStrategyLogic() != null ? config.getStrategyLogic() : "OR";
            if ("AND".equalsIgnoreCase(logic)) {
                return strategies.stream()
                        .allMatch(s -> evaluateSingleStrategy(s.getStrategyId(), s.getParams(), context));
            } else {
                return strategies.stream()
                        .anyMatch(s -> evaluateSingleStrategy(s.getStrategyId(), s.getParams(), context));
            }
        } catch (Exception e) {
            log.error("Lỗi khi đánh giá config của cờ [{}]: {}. Mặc định coi như false",
                    config.getFlagName(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Kiểm tra đệ quy xem cờ cha có đang hoạt động hay không.
     * Chống vòng lặp vô tận (Circular Dependency) bằng tập hợp visited.
     */
    private boolean isParentActive(String parentId, EvaluationContext context, Set<String> visited) {
        if (!hasText(parentId)) {
            return true;
        }

        // Kiểm tra vòng lặp phụ thuộc (A -> B -> A)
        if (!visited.add(parentId)) {
            log.warn("Phát hiện vòng lặp cờ cha - con (Circular Dependency) tại '{}' -> Mặc định coi cờ cha TẮT (false)", parentId);
            return false;
        }

        // Tìm cờ cha theo ID trong idCache (parentId là ID, không dùng tên cờ)
        FeatureFlagConfig parentConfig = idCache.get(parentId);

        if (parentConfig == null) {
            log.warn("Không tìm thấy cấu hình cờ cha với ID '{}' -> Mặc định coi cờ cha TẮT (false)", parentId);
            return false;
        }

        // Đệ quy đánh giá cờ cha với context hiện tại
        return evaluateConfig(parentConfig, context, visited);
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

    // ========================================================================
    // ALL-FLAGS EVALUATION: bounded tasks, one flag per task
    // ========================================================================

    /**
     * Đánh giá toàn bộ cờ theo context của request hiện tại.
     * Mỗi task xử lý một flag; số task đang chạy/chờ bị giới hạn bởi {@code maxPendingTasks}.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Boolean> evaluateAll() {
        EvaluationContext context = captureContext();
        Map<String, List<FeatureFlagConfig>> snapshot = new HashMap<>(flagCache);

        if (snapshot.isEmpty()) {
            refreshCache();
            snapshot = new HashMap<>(flagCache);
            if (snapshot.isEmpty()) {
                return Collections.emptyMap();
            }
        }

        Map<String, Boolean> results = new HashMap<>(snapshot.size());
        CompletionService<FlagEvaluationResult> completionService =
                new ExecutorCompletionService<>(featureFlagExecutor);
        Map<Future<FlagEvaluationResult>, String> pendingTasks = new HashMap<>();
        Iterator<Map.Entry<String, List<FeatureFlagConfig>>> remainingFlags = snapshot.entrySet().iterator();

        submitUntilWindowFull(remainingFlags, context, completionService, pendingTasks);

        while (!pendingTasks.isEmpty()) {
            try {
                Future<FlagEvaluationResult> completedTask = completionService.take();
                String flagName = pendingTasks.remove(completedTask);

                try {
                    FlagEvaluationResult evaluation = completedTask.get();
                    results.put(evaluation.flagName(), evaluation.enabled());
                } catch (ExecutionException e) {
                    log.error("Error evaluating flag [{}]. Default: false", flagName, e.getCause());
                    results.put(flagName, false);
                }

                submitUntilWindowFull(remainingFlags, context, completionService, pendingTasks);
            } catch (InterruptedException e) {
                pendingTasks.keySet().forEach(task -> task.cancel(true));
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while evaluating feature flags", e);
            }
        }

        return results;
    }

    /** Submit cờ tiếp theo cho tới khi chạm giới hạn task đang chạy/chờ. */
    private void submitUntilWindowFull(
            Iterator<Map.Entry<String, List<FeatureFlagConfig>>> remainingFlags,
            EvaluationContext context,
            CompletionService<FlagEvaluationResult> completionService,
            Map<Future<FlagEvaluationResult>, String> pendingTasks) {
        while (remainingFlags.hasNext() && pendingTasks.size() < maxPendingTasks) {
            Map.Entry<String, List<FeatureFlagConfig>> flag = remainingFlags.next();
            String flagName = flag.getKey();
            List<FeatureFlagConfig> configs = flag.getValue();

            Future<FlagEvaluationResult> task = completionService.submit(() -> {
                boolean enabled = false;
                try {
                    enabled = configs != null && configs.stream().anyMatch(config ->
                            evaluateConfig(config, context, new HashSet<>())
                    );
                } catch (Exception e) {
                    log.error("Error evaluating flag [{}]. Default: false", flagName, e);
                }
                return new FlagEvaluationResult(flagName, enabled);
            });
            pendingTasks.put(task, flagName);
        }
    }

    private record FlagEvaluationResult(String flagName, boolean enabled) { }

    // ========================================================================
    // REQUEST CONTEXT: capture ThreadLocal data before dispatching worker tasks
    // ========================================================================

    private EvaluationContext captureContext() {
        return EvaluationContext.of(
                getCurrentUsername(),
                getCurrentAuthorities(),
                resolveClientIp()
        );
    }

    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return auth.getName();
    }

    private Collection<? extends GrantedAuthority> getCurrentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? List.of() : auth.getAuthorities();
    }

    private String resolveClientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }

        HttpServletRequest request = servletAttributes.getRequest();
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

    // ========================================================================
    // SHARED UTILITIES
    // ========================================================================

    private String normalizeFlagName(String flagName) {
        return flagName.toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
