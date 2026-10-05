# Roadmap Feature Flag trong `tracking-order` và `feature-flag-lib`

> Tài liệu này mô tả call flow trong source hiện tại. `tracking-order` dùng `feature-flag-lib` để nhận snapshot, cache cấu hình tại DB/RAM local, và đánh giá cờ cho request. `feature-flag-service` là bên phát snapshot; nó không phải DB mà thư viện truy vấn trực tiếp.

## 1. Bản đồ tổng quan

```mermaid
flowchart LR
    A[Admin thay đổi flag] --> B[feature-flag-service lưu cấu hình]
    B -->|Apply: JSON multipart| C[tracking-order /sync-file]
    C --> D[InternalFeatureFlagServiceImpl]
    D --> E[FeatureFlagClient.syncSnapshot]
    E --> F[FeatureFlagConfigServiceImpl.syncSnapshot]
    F --> G[(tracking-order DB: feature_flag_configs)]
    F --> H[refreshCache: flagCache + idCache]

    I[Login / Refresh /me] --> J[AuthServiceImpl]
    J --> K[FeatureFlagClient.evaluateAll]
    K --> L[FeatureFlagConfigServiceImpl.evaluateAll]
    H --> L
    L --> M[Capture context]
    L --> N[Chia flag thành batch]
    N --> O[Đánh giá root + parent + strategies]
    O --> P[Map flagName -> Boolean]
    P --> Q[AuthRes.features]

    R[Order endpoint] --> S[@RequireFeature hoặc isEnabled]
    S --> T[FeatureFlagAspect / FeatureFlagClient]
    T --> L2[FeatureFlagConfigServiceImpl.isEnabled]
    H --> L2
    L2 --> U[Cho chạy nghiệp vụ hoặc ném FeatureFlagDisabledException]
```

### 1.1 Khởi tạo thư viện

Spring Boot tìm `FeatureFlagAutoConfiguration` qua `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Ứng dụng cũng có thể dùng `@EnableFeatureFlag`, annotation này `@Import` cùng auto-configuration. Auto-configuration bật AOP, component scan package `com.example.featureflag`, đăng ký `FeatureFlagExecutorProperties`, tạo bean `featureFlagExecutor`; khi `FeatureFlagConfigServiceImpl` được tạo, constructor nhận các `EvaluationStrategy` rồi đăng ký alias vào `strategyMap`.

## 2. Hai luồng sử dụng chính

### 2.1 Login/refresh: lấy map cờ cho frontend

```text
AuthController.login/refresh/getMe
  -> AuthServiceImpl.login/refresh/getMe
  -> FeatureFlagClient.evaluateAll()
  -> FeatureFlagConfigService.evaluateAll()
  -> FeatureFlagConfigServiceImpl.evaluateAll()
  -> context + cache snapshot + batch tasks
  -> evaluateConfig -> evaluateSingleStrategy -> EvaluationStrategy.evaluate
  -> Map<String, Boolean>
  -> AuthRes.features
```

- `login()` đánh giá sau khi xác thực user và đặt `Authentication` vào `SecurityContextHolder`.
- `refresh()` tạo access token mới rồi đánh giá lại map `features`.
- `getMe()` cũng gọi `evaluateAll()` để trả map hiện tại.
- Map kết quả được tạo cho lần gọi đó; thư viện không lưu một map kết quả dùng chung cho mọi user. Cache RAM dùng chung là **cấu hình** flag/rule.

### 2.2 Request nghiệp vụ: kiểm tra một flag

```text
OrderController
  -> OrderServiceImpl method
     -> (A) @RequireFeature -> FeatureFlagAspect.checkFeatureFlag
                           -> FeatureFlagClient.isEnabled
     -> (B) gọi trực tiếp FeatureFlagClient.isEnabled
  -> FeatureFlagConfigServiceImpl.isEnabled
  -> cache theo tên -> evaluateConfig -> parent/strategies
  -> true: tiếp tục; false: false hoặc FeatureFlagDisabledException
```

Ví dụ trong source `tracking-order`:

- `OrderServiceImpl.buyNow()` có `@RequireFeature(features = "BUY_NOW")`.
- `OrderServiceImpl.getOrderDetail()` có `@RequireFeature(features = "ORDER_DETAIL")`.
- Tính giá gọi `featureFlagClient.isEnabled("PRICE_INCREASE")` trực tiếp.

Nếu annotation có nhiều tên trong `features`, aspect kiểm tra lần lượt và chặn ngay khi **bất kỳ** flag nào false; do đó tất cả flag được liệt kê phải bật mới vào method.

## 3. Snapshot: từ Apply đến cache RAM local

### Bên nhận trong `tracking-order`

```text
POST /api/v1/feature-flags/sync-file
  -> InternalFeatureFlagController.syncFile
  -> InternalFeatureFlagServiceImpl.syncSnapshotFile
     -> validateToken
     -> ObjectMapper đọc JSON thành FeatureFlagSyncRequest
     -> FeatureFlagClient.syncSnapshot
  -> FeatureFlagConfigServiceImpl.syncSnapshot
     -> Repo.findAll
     -> map theo flagName
     -> cập nhật enabled, parentId, strategyLogic, strategies, version
     -> xóa các flag không còn trong snapshot
     -> saveAll + flush
     -> refreshCache
```

`enabled: false` trong snapshot được chuyển thành `config.enabled = false`. Sau khi ghi DB, `refreshCache()` nạp lại cache RAM. Nếu snapshot có mảng `features` rỗng, service sẽ xóa cấu hình cũ không còn trong snapshot; request `null` hoặc thiếu `features` bị bỏ qua.

### Khởi động tracking-order

`@PostConstruct initCache()` chạy `refreshCache()` sau khi service được tạo:

- Gọi `FeatureFlagConfigRepo.findAll()` trên DB **của tracking-order**.
- `flagCache`: `FLAG_NAME_UPPERCASE -> List<FeatureFlagConfig>`.
- `idCache`: `id -> FeatureFlagConfig`, dùng tra cờ cha.
- DB giữ cấu hình bền vững; RAM giữ bản copy để evaluation không query DB ở mỗi lần kiểm tra.

Vì vậy, bảng cần đối chiếu sau Apply là `feature_flag_configs` trong DB tracking-order. Bảng `feature_flags` của control/tenant service là dữ liệu nguồn khác.

## 4. Call graph các hàm public và điểm vào

| Điểm gọi / hàm | Gọi tiếp | Ý nghĩa |
|---|---|---|
| `FeatureFlagAutoConfiguration.featureFlagExecutor(properties)` | tạo `ThreadPoolTaskExecutor` bean `featureFlagExecutor` | Tạo pool riêng cho `evaluateAll`, bind thông số từ `feature-flag.executor.*`; handler dùng caller thread làm backpressure khi pool đầy. |
| `FeatureFlagConfigServiceImpl.initCache()` | `refreshCache()` | Nạp cấu hình local vào RAM khi ứng dụng khởi động. |
| `InternalFeatureFlagController.syncFile(...)` | `InternalFeatureFlagServiceImpl.syncSnapshotFile(...)` | HTTP multipart endpoint nhận file snapshot nội bộ. |
| `InternalFeatureFlagServiceImpl.syncSnapshotFile(token,file)` | `validateToken`, parse JSON, `FeatureFlagClient.syncSnapshot` | Xác thực internal token, parse snapshot, trả số dòng đã sync. |
| `FeatureFlagClient.syncSnapshot(request)` | `FeatureFlagConfigService.syncSnapshot` | Facade công khai để đồng bộ snapshot. |
| `FeatureFlagConfigServiceImpl.syncSnapshot(request)` | repository save/delete, `refreshCache()` | Cập nhật DB local và RAM từ snapshot. |
| `AuthServiceImpl.login/refresh/getMe` | `FeatureFlagClient.evaluateAll()` | Tạo map cờ phù hợp context của lần gọi hiện tại để đưa vào `AuthRes`. |
| `FeatureFlagClient.evaluateAll()` | `FeatureFlagConfigService.evaluateAll()` | Facade; nếu gặp `RuntimeException`, log và fallback về map rỗng. |
| `FeatureFlagConfigServiceImpl.evaluateAll()` | `captureContext`, cache, batch, `evaluateConfig` | Đánh giá tất cả flag; trả map `flagName -> enabled`. |
| `FeatureFlagClient.isEnabled(name)` | `FeatureFlagConfigService.isEnabled(name)` | Facade cho code nghiệp vụ và AOP; lỗi runtime fallback về false. |
| `FeatureFlagConfigServiceImpl.isEnabled(name)` | cache/Repo, `captureContext`, `evaluateConfig` | Đánh giá một flag theo context request hiện tại. |
| `FeatureFlagAspect.checkFeatureFlag(joinPoint, annotation)` | gọi `FeatureFlagClient.isEnabled` cho từng tên | Chạy trước method `@RequireFeature`; false thì ném `FeatureFlagDisabledException`. |

## 5. Hàm trong `FeatureFlagConfigServiceImpl`

| Hàm | Trách nhiệm |
|---|---|
| Constructor | Nhận repository, `ObjectMapper`, executor và danh sách strategy bean; đăng ký từng strategy theo các ID hỗ trợ vào `strategyMap`. |
| `initCache()` | Hook khởi động; gọi nạp cache. |
| `refreshCache()` | Đọc tất cả config từ DB local; group theo tên viết hoa; dựng `flagCache` và `idCache`. |
| `syncSnapshot(request)` | Upsert config theo tên flag, xóa tên bị loại khỏi snapshot, flush DB, refresh RAM; trả số config được lưu. |
| `isEnabled(flagName)` | Chuẩn hóa tên; đọc cache; cache miss thì query `findByFlagNameIgnoreCase`; đánh giá các config có tên đó. |
| `evaluateAll()` | Chụp context một lần, lấy snapshot cấu hình, chia batch theo `evaluationBatchSize`, tối đa `maxPendingBatches` future mỗi cửa sổ, chờ cửa sổ xong rồi tạo cửa sổ tiếp. |
| `evaluateConfig(config, context, visited)` | Kiểm tra config có tồn tại và `enabled`; kiểm tra parent; nếu không có rule thì bật; nếu có thì áp dụng AND/OR. Bắt lỗi và mặc định false. |
| `isParentActive(parentId, context, visited)` | Tìm parent trong `idCache`, đệ quy đánh giá parent, phát hiện vòng lặp bằng `visited`. |
| `evaluateSingleStrategy(id, params, context)` | Tìm implementation trong `strategyMap`; strategy không biết ID thì false. |
| `captureContext()` | Ghép username, authorities và client IP thành `EvaluationContext` trước khi chuyển batch sang worker. |
| `getCurrentUsername()` | Đọc tên user từ `SecurityContextHolder`; anonymous/chưa authenticated thì null. |
| `getCurrentAuthorities()` | Đọc danh sách quyền từ Authentication. |
| `resolveClientIp()` | Đọc `X-Forwarded-For` đầu tiên hoặc remote address. |
| `normalizeClientIp(ip)` | Chuẩn hóa loopback, IPv4-mapped IPv6 và IP qua `InetAddress`. |
| `parseStrategies(json)` | Parse JSON thành list `StrategyItemSync`; chuỗi rỗng trả list rỗng, JSON lỗi ném exception cho `evaluateConfig` xử lý. |
| `strategiesToJson(strategies)` | Serialize strategies trước khi lưu snapshot vào entity. |
| `hasText(value)` | Helper kiểm tra chuỗi khác null/rỗng/trắng. |
| `toConfig(feature,version)` | Hàm mapping DTO sang entity đang có trong source; cần kiểm tra caller trước khi coi là một bước runtime, hiện luồng `syncSnapshot` đang mapping trực tiếp trong vòng lặp. |

## 6. Rules và Strategy

### Thứ tự đánh giá một config

1. `config.enabled` false hoặc null → false ngay.
2. Có `parentId` → parent phải tìm thấy, bật và không tạo vòng lặp.
3. Không có strategy → true (vì bước 1 đã xác nhận công tắc bật).
4. Có strategies:
   - `strategyLogic = AND`: tất cả strategy phải true.
   - Mặc định hoặc `OR`: chỉ cần một strategy true.
5. Nhiều config trùng tên flag trong cache được gộp bằng `anyMatch`: một config true làm flag đó true.

### Strategy implementations

| Class | ID hỗ trợ | Điều kiện |
|---|---|---|
| `UsernameStrategy` | `username`, `users_by_name` | Username context khớp một phần tử trong `users`/`value`. |
| `UserRoleStrategy` | `user-role`, `user_role`, `role` | Authority khớp `roles`/`role`/`value`, có hỗ trợ prefix `ROLE_`. |
| `ClientIpStrategy` | `remote-client-ip`, `ip_whitelist`, `ip` | IP context khớp danh sách `ips`/`value` sau chuẩn hóa. |
| `ReleaseDateStrategy` | `release-date`, `release_date` | Thời gian hiện tại sau ngày `date`/`releaseDate`/`value`. |
| `GradualRolloutStrategy` | `gradual-rollout`, `gradual_rollout`, `gradual_rollout_user_id`, `rollout` | Hash username, fallback IP, chia bucket `% 100` so với phần trăm. |

`EvaluationStrategy.getSupportedStrategyIds()` khai báo alias; `evaluate(params,context)` thực hiện điều kiện. Spring component scan tìm các implementation và constructor của service đăng ký chúng.

## 7. Batch evaluation và cấu hình

`FeatureFlagExecutorProperties` bind từ prefix `feature-flag.executor`:

| Property | Default trong thư viện | Vai trò |
|---|---:|---|
| `core-pool-size` | 4 | Thread pool giữ tối thiểu. |
| `max-pool-size` | 8 | Mức pool có thể tăng đến khi queue đầy. |
| `queue-capacity` | 16 | Task chờ trong queue trước khi tăng pool đến max. |
| `evaluation-batch-size` | 100 | Số tên flag trong một task. |
| `max-pending-batches` | 64 | Future tối đa mỗi cửa sổ của một lần `evaluateAll`. |
| `thread-name-prefix` | `ff-eval-` | Prefix thread trong log/diagnostic. |
| `wait-for-tasks-to-complete-on-shutdown` | true | Chờ task đang chạy lúc shutdown. |
| `await-termination-seconds` | 10 | Giới hạn chờ shutdown. |

Với 5.000 tên flag và batch 100: khoảng 50 task. Các task vẫn chạy song song; bên trong từng task các flag chạy tuần tự. `CompletableFuture.allOf(...).join()` chặn thread request cho đến khi đủ map trả login; worker được trả pool sau khi xong batch. Pool đầy thì producer chạy batch trên thread submit (backpressure), không thả task; vì thế latency request có thể tăng dưới tải.

## 8. DTO/entity/repository: dữ liệu mang qua các tầng

- `FeatureFlagSyncRequest`: version, exported time và list features.
- `FeatureFlagSyncItem`: id, flagName, enabled, parentId, strategyLogic, version, list strategy.
- `StrategyItemSync`: strategyId và params dạng map string/string.
- `FeatureFlagConfig`: entity của bảng `feature_flag_configs`; lưu config sync local.
- `BaseEntity`: created/update timestamp, actor, deleted.
- `FeatureFlagConfigRepo.findAll()`: nạp toàn bộ config cho cache/sync.
- `findByFlagNameIgnoreCase(name)`: cache miss của `isEnabled`.
- `findDistinctFlagNames()`: query có trong repository nhưng hiện service `evaluateAll()` duyệt key của `flagCache`, không gọi query này.

## 9. Checklist đọc log khi demo

1. Sau startup: log số flag đã được nạp RAM từ DB tracking-order.
2. Khi Apply: response `/sync-file` phải báo `status=SUCCESS`, `syncedRows`, version.
3. Sau Apply: đối chiếu `feature_flag_configs` trong DB tracking-order với snapshot.
4. Login/refresh: `AuthServiceImpl` log số cờ đã evaluate; kiểm tra số key trong `AuthRes.features`.
5. Request có `@RequireFeature`: nếu false, AOP log method bị chặn và exception handler chuyển thành HTTP error.

## 10. Các điểm cần nhớ khi trình bày

- Config cache RAM là cache định nghĩa/rule; map trong `AuthRes.features` là kết quả của một lần đánh giá.
- Login và refresh gọi `evaluateAll()` lại; đổi config cần tới tracking-order qua snapshot trước để cache config của instance được làm mới.
- Nếu có nhiều replica tracking-order, mỗi replica cần nhận snapshot; nếu không, các instance có thể trả trạng thái khác nhau.
- Source `feature-flag-lib` có strategy implementation động theo `strategyId`; enum của `feature-flag-service` là vấn đề riêng và không phải enum trong thư viện này.
- `FeatureFlagConfig.parsedStrategies` được khai báo transient nhưng luồng evaluation hiện parse từ JSON qua `parseStrategies`; đừng trình bày nó như cache parse đang được sử dụng.
