# Thư Viện Feature Flag (feature-flag-lib)

> **Thư viện dùng chung (In-house Shared Library)** cung cấp giải pháp quản lý, đồng bộ và đánh giá cờ tính năng (Feature Flag) cục bộ, hiệu năng cao, an toàn cho hệ thống Microservices.

---

## Mục lục
1. [Giới thiệu tổng quan](#1-giới-thiệu-tổng-quan)
2. [Kiến trúc & Công nghệ sử dụng](#2-kiến-trúc--công-nghệ-sử-dụng)
3. [Cấu trúc thư mục (Package Structure)](#3-cấu-trúc-thư-mục-package-structure)
4. [Mô hình Database & Entity](#4-mô-hình-database--entity)
5. [Các Phương thức SDK & Annotation](#5-các-phương-thức-sdk--annotation)
6. [Hướng dẫn cài đặt & Tích hợp vào Microservice](#6-hướng-dẫn-cài-đặt--tích-hợp-vào-microservice)
7. [Hướng dẫn sử dụng thực tế](#7-hướng-dẫn-sử-dụng-thực-tế)
8. [Trạng thái phát triển & Ghi chú/TODO](#8-trạng-thái-phát-triển--ghi-chútodo)

---

## 1. Giới thiệu tổng quan

### 1.1. Tên dự án & Tọa độ Maven
* **GroupId**: `com.example`
* **ArtifactId**: `feature-flag-lib`
* **Phiên bản hiện tại**: `1.0.0`
* **Định dạng đóng gói**: `jar`

### 1.2. Mục đích & Bài toán giải quyết
Trong kiến trúc Microservices, việc bật/tắt tính năng (Feature Toggle / Canary Release / A/B Testing) thường gặp các vấn đề:
1. **Độ trễ mạng (Network Latency) & Điểm nghẽn (Single Point of Failure)**: Nếu mỗi lần kiểm tra cờ đều phải gọi HTTP sang service trung tâm thì khi service trung tâm gặp sự cố hoặc quá tải, toàn bộ microservices khác sẽ bị chậm hoặc tê liệt.
2. **Chi phí I/O Database**: Khi kiểm tra cờ ở các luồng nóng (như mua hàng, đặt đơn, login), việc query database liên tục sẽ gây nghẽn kết nối DB.
3. **Mất ngữ cảnh bảo mật trong môi trường đa luồng**: Khi đánh giá cờ song song qua ThreadPool, các thông tin trong `ThreadLocal` (`SecurityContextHolder`, `RequestContextHolder`) thường bị mất dẫn đến đánh giá sai (bị xem là anonymous user).

**`feature-flag-lib` giải quyết triệt để các bài toán trên bằng cách:**
* **Kiến trúc Local Snapshot**: Lưu cấu hình cờ trực tiếp vào Database của từng microservice, tự động đồng bộ khi có thay đổi từ Admin.
* **In-Memory Cache (RAM)**: Lưu trữ cờ trực tiếp trên bộ nhớ RAM, phục vụ các lệnh kiểm tra với tốc độ $O(1)$ (< 0.001ms), hoàn toàn không query Database trên luồng nóng.
* **Mô hình Concurrency & Context Snapshotting**: Tận dụng Spring `ThreadPoolTaskExecutor` để đánh giá song song toàn bộ cờ cho user khi login, đồng thời chụp ảnh ngữ cảnh (Snapshot) từ luồng cha sang luồng con an toàn 100%.
* **Strategy Pattern chuẩn OCP**: Tách độc lập từng loại chiến lược đánh giá (User, Role, IP, Release Date, Rollout) giúp dễ dàng mở rộng không giới hạn.
* **Lập trình phòng vệ (Fail-safe Fallback)**: Cổng `FeatureFlagClient` tự động bắt mọi lỗi ngoại lệ và fallback về `false`, đảm bảo nghiệp vụ chính của microservice không bao giờ bị gián đoạn.

### 1.3. Các thành phần thực tế có trong thư viện
* **Annotation**: `@EnableFeatureFlag` (kích hoạt lib), `@RequireFeature` (chặn AOP method).
* **Aspect**: `FeatureFlagAspect` (AOP interceptor kiểm tra cờ trước khi chạy method).
* **Client / Facade**: `FeatureFlagClient` (điểm tiếp xúc an toàn cho lập trình viên ứng dụng).
* **Auto-Configuration**: `FeatureFlagAutoConfiguration` (cung cấp ThreadPool và tự động quét Bean).
* **Core Service**: `FeatureFlagConfigService` & `FeatureFlagConfigServiceImpl`.
* **Strategy Engine**: Interface `EvaluationStrategy`, record `EvaluationContext`, và 5 strategy cụ thể: `UsernameStrategy`, `UserRoleStrategy`, `ClientIpStrategy`, `ReleaseDateStrategy`, `GradualRolloutStrategy`.
* **Data Access**: `FeatureFlagConfigRepo` và Entity `FeatureFlagConfig`, `BaseEntity`.
* **DTO**: `FeatureFlagSyncRequest`, `FeatureFlagSyncItem`, `StrategyItemSync`.
* **Exception**: `FeatureFlagDisabledException`.

---

## 2. Kiến trúc & Công nghệ sử dụng

### 2.1. Ngăn xếp công nghệ (Technology Stack)
* **Java**: `21` (sử dụng các tính năng hiện đại: `record`, Pattern Matching for `switch`, Virtual Thread ready).
* **Spring Boot Framework**: `3.4.0`
  * `spring-boot-starter-aop`: Hỗ trợ AspectJ interceptor cho `@RequireFeature`.
  * `spring-boot-starter-data-jpa`: Tương tác Hibernate/JPA lưu snapshot vào DB.
  * `spring-boot-starter-web`: Cung cấp `RequestContextHolder` và `HttpServletRequest`.
  * `spring-boot-autoconfigure`: Cơ chế Spring Boot Starter nạp tự động.
* **Spring Security Core**: `6.4.0` (Trích xuất `Authentication`, `Principal`, `GrantedAuthority` từ `SecurityContextHolder`).
* **Jackson Databind**: `2.18.1` (Serialize / Deserialize JSON cấu hình các chiến lược).
* **Lombok**: `1.18.30` (giảm boilerplate code).

### 2.2. Kiến trúc phân tầng (Layered Architecture)

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        TẦNG GIAO TIẾP CÔNG KHAI                        │
│   @RequireFeature (AOP Aspect)    │       FeatureFlagClient (Facade)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        TẦNG DỊCH VỤ CỐT LÕI                            │
│                    FeatureFlagConfigServiceImpl                        │
│   ├── In-Memory Cache (ConcurrentHashMap - Tốc độ O(1))                │
│   ├── Context Snapshot Engine (Capture Username, Roles, Client IP)     │
│   └── ThreadPoolTaskExecutor (ff-eval-1, ff-eval-2: Đánh giá song song)│
└──────────────┬──────────────────────────────────────────┬──────────────┘
               │                                          │
               ▼                                          ▼
┌───────────────────────────────┐          ┌─────────────────────────────┐
│    STRATEGY PATTERN ENGINE    │          │     DATABASE & PERSISTENCE  │
│  ├── UsernameStrategy         │          │  ├── FeatureFlagConfigRepo  │
│  ├── UserRoleStrategy         │          │  ├── FeatureFlagConfig (JPA)│
│  ├── ClientIpStrategy         │          │  └── BaseEntity (Auditing)  │
│  ├── ReleaseDateStrategy      │          └─────────────────────────────┘
│  └── GradualRolloutStrategy   │
└───────────────────────────────┘
```

### 2.3. Cơ chế trích xuất ngữ cảnh (Context Extraction)
Thư viện tự động phân tích ngữ cảnh người dùng đang thực hiện request:
1. **User Identity & Roles**: 
   - Đọc từ `SecurityContextHolder.getContext().getAuthentication()`.
   - Nhận diện `auth.getName()` và danh sách `GrantedAuthority`.
   - Tự động bỏ qua nếu là `anonymousUser` hoặc chưa đăng nhập.
2. **Client IP Address**: 
   - Đọc từ `RequestContextHolder.getRequestAttributes()`.
   - Ưu tiên header `X-Forwarded-For` (nếu hệ thống nằm sau Nginx / API Gateway / Cloudflare).
   - Tự động fallback về `request.getRemoteAddr()`.
   - **Chuẩn hóa Network qua `InetAddress`**: Tự động chuyển đổi các địa chỉ loopback IPv6 (`0:0:0:0:0:0:0:1` hoặc `::1`) về chuẩn `127.0.0.1`, bóc tách tiền tố IPv4-mapped IPv6 (`::ffff:x.x.x.x`).

---

## 3. Cấu trúc thư mục (Package Structure)

```text
com.example.featureflag
├── annotation
│   ├── EnableFeatureFlag.java          # Annotation kích hoạt thư viện thủ công
│   └── RequireFeature.java             # Annotation gắn trên Method để kiểm tra cờ
├── aspect
│   └── FeatureFlagAspect.java          # Interceptor AOP chặn trước method @RequireFeature
├── client
│   └── FeatureFlagClient.java          # Facade công khai, xử lý Fail-safe fallback
├── config
│   └── FeatureFlagAutoConfiguration.java # Tự động cấu hình Spring Boot & ThreadPoolTaskExecutor
├── dto
│   ├── FeatureFlagSyncItem.java        # DTO thông tin 1 cờ trong snapshot
│   ├── FeatureFlagSyncRequest.java     # DTO gói snapshot đồng bộ từ server trung tâm
│   └── StrategyItemSync.java           # DTO cấu hình từng chiến lược con
├── entity
│   ├── BaseEntity.java                 # MappedSuperclass chứa audit (created_at, updated_at, deleted)
│   └── FeatureFlagConfig.java          # Entity JPA bảng feature_flag_configs
├── exception
│   └── FeatureFlagDisabledException.java # Ngoại lệ ném ra khi cờ bị tắt (HTTP 400)
├── repository
│   └── FeatureFlagConfigRepo.java      # JpaRepository truy vấn DB cục bộ
├── service
│   ├── FeatureFlagConfigService.java   # Interface nghiệp vụ cấu hình cờ
│   └── impl
│       └── FeatureFlagConfigServiceImpl.java # Triển khai Cache RAM, ThreadPool và Snapshot Context
└── strategy
    ├── EvaluationContext.java          # Record chứa snapshot: username, authorities, clientIp
    ├── EvaluationStrategy.java         # Interface chuẩn Strategy Pattern
    └── impl
        ├── ClientIpStrategy.java       # Chiến lược IP Whitelist
        ├── GradualRolloutStrategy.java # Chiến lược Gradual Rollout theo %
        ├── ReleaseDateStrategy.java    # Chiến lược mở cờ theo ngày giờ
        ├── UserRoleStrategy.java       # Chiến lược theo vai trò người dùng (Role)
        └── UsernameStrategy.java       # Chiến lược theo tài khoản người dùng (User Whitelist)
```

---

## 4. Mô hình Database & Entity

Thư viện sử dụng cơ chế JPA Entity để lưu snapshot cấu hình cờ vào Database của service tiêu thụ.

### Bảng: `feature_flag_configs`

| Tên Cột | Kiểu Dữ Liệu | Khóa | Ràng buộc | Mô Tả |
| :--- | :--- | :---: | :---: | :--- |
| `id` | `VARCHAR(36)` | **PK** | NOT NULL | UUID định danh ngẫu nhiên (sinh tự động qua `@UuidGenerator`) |
| `flag_name` | `VARCHAR(100)` | Index | NOT NULL | Tên định danh của cờ (viết hoa, ví dụ: `BUY_NOW`, `ORDER_DETAIL`) |
| `enabled` | `BOOLEAN` | | NOT NULL | Công tắc tổng (Master Switch): `true` = BẬT, `false` = TẮT |
| `strategies` | `JSON` | | NULL | Chuỗi JSON chứa mảng các chiến lược đánh giá chi tiết |
| `strategy_logic` | `VARCHAR(3)` | | NULL | Logic kết hợp các chiến lược: `"AND"` hoặc `"OR"` (mặc định `"OR"`) |
| `applied_version` | `VARCHAR(100)` | | NOT NULL | Phiên bản snapshot được áp dụng (ví dụ: `v-1727251234567`) |
| `create_at` | `DATETIME` | | NULL | Thời điểm tạo bản ghi (Audit từ `BaseEntity`) |
| `update_at` | `DATETIME` | | NULL | Thời điểm cập nhật bản ghi (Audit từ `BaseEntity`) |
| `created_by` | `VARCHAR(255)` | | NULL | Người tạo bản ghi |
| `updated_by` | `VARCHAR(255)` | | NULL | Người cập nhật bản ghi |
| `deleted` | `BOOLEAN` | | NOT NULL | Cờ xóa mềm (Soft-delete): `false` = hoạt động, `true` = đã xóa |

* **Index**: `@Index(name = "idx_feature_flag_name", columnList = "flag_name")` giúp tăng tốc truy vấn khi khởi động nạp cache.

---

## 5. Các Phương thức SDK & Annotation

Vì đây là thư viện dùng chung (Shared Library), thư viện cung cấp các phương thức SDK thông qua `FeatureFlagClient` và Annotation `@RequireFeature`:

### 5.1. Annotation `@RequireFeature`
Dùng để bảo vệ trực tiếp method (Controller endpoint hoặc Service method):

| Thuộc tính | Kiểu | Mặc định | Ý nghĩa |
| :--- | :--- | :--- | :--- |
| `features` | `String[]` | `{}` | Mảng tên các cờ cần kiểm tra. Tất cả cờ trong mảng phải BẬT thì method mới được chạy. |
| `message` | `String` | `"Tính năng đang bảo trì"` | Thông báo lỗi trả về cho client nếu cờ bị tắt. |

* **Hành vi khi cờ TẮT**: `FeatureFlagAspect` lập tức ném ra ngoại lệ `FeatureFlagDisabledException` (HTTP Status: `400 BAD REQUEST`) chứa `message` cấu hình.

### 5.2. Public SDK `FeatureFlagClient`

| Phương thức | Tham số | Giá trị trả về | Hành vi phòng vệ (Fail-safe) |
| :--- | :--- | :--- | :--- |
| `isEnabled(String flagName)` | `flagName`: Tên cờ cần kiểm tra | `boolean` (`true`/`false`) | Nếu có lỗi DB/Runtime, tự động bắt lỗi và trả về `false` (an toàn). Đọc trực tiếp từ In-Memory Cache. |
| `evaluateAll()` | Không có | `Map<String, Boolean>` | Đánh giá toàn bộ cờ cho user session hiện tại qua ThreadPool. Nếu lỗi, trả về `Collections.emptyMap()`. |
| `syncSnapshot(FeatureFlagSyncRequest request)` | DTO snapshot từ Admin | `int` (số lượng cờ đã đồng bộ) | Ghi đè snapshot mới vào DB và làm mới In-Memory Cache ngay lập tức. |

---

## 6. Hướng dẫn cài đặt & Tích hợp vào Microservice

### Cấu hình thread pool đánh giá cờ

Ứng dụng import thư viện có thể ghi đè executor dùng bởi `evaluateAll()` trong `application.yml`:

```yaml
feature-flag:
  executor:
    core-pool-size: 4
    max-pool-size: 16
    queue-capacity: 16
    max-pending-tasks: 24
    thread-name-prefix: ff-eval-
    wait-for-tasks-to-complete-on-shutdown: true
    await-termination-seconds: 10
```

Các giá trị mặc định lần lượt là `4`, `8`, `16`, `24`, `ff-eval-`, `true`, `10`. Mỗi task đánh giá một flag rồi hoàn tất để worker quay lại pool nhận task tiếp theo. `max-pending-tasks` giới hạn số task đang chạy/chờ được submit bởi một lần `evaluateAll()`; các task tiếp theo chỉ được submit khi có task hoàn tất. Với 5.000 flag, vẫn có 5.000 lượt đánh giá nhưng không giữ 5.000 future đang chờ cùng lúc. Queue hữu hạn cho pool cơ hội mở rộng từ core lên max; khi pool và queue đầy, backpressure chạy task trên thread submit thay vì từ chối. Ứng dụng cũng có thể khai báo bean tên `featureFlagExecutor` để thay thế hoàn toàn executor mặc định.

Lưu ý: `spring.task.execution.pool.*` cấu hình executor mặc định của Spring Boot. Thư viện này dùng executor bean riêng tên `featureFlagExecutor`, nên cấu hình của nó nằm dưới `feature-flag.executor.*` như ví dụ trên.

### 6.1. Yêu cầu môi trường
* **JDK**: `21` trở lên.
* **Build Tool**: Apache Maven `3.8+` (hoặc Maven Wrapper đi kèm dự án).
* **Spring Boot**: `3.x` (đã kiểm thử tương thích tốt trên `3.4.0`).
* **Database**: MySQL `8.0+` (hoặc các hệ quản trị CSDL hỗ trợ kiểu cột `JSON`).

### 6.2. Các bước đóng gói & Cài đặt vào kho Maven Local
Mở terminal tại thư mục chứa mã nguồn `feature-flag-lib`:
```bash
mvn clean install -DskipTests
```
Lệnh này sẽ biên dịch, đóng gói file `feature-flag-lib-1.0.0.jar` và cài đặt vào thư mục `~/.m2/repository/com/example/feature-flag-lib/1.0.0/`.

### 6.3. Khai báo Dependency trong Microservice tiêu thụ
Thêm khối dependency sau vào file `pom.xml` của microservice (ví dụ `tracking-order`):
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>feature-flag-lib</artifactId>
    <version>1.0.0</version>
</dependency>
```

*(Tùy chọn: Nếu microservice build qua Docker không dùng chung cache `.m2`, có thể copy file `feature-flag-lib-1.0.0.jar` vào thư mục `libs/` của project và chạy `install:install-file` trong Dockerfile).*

### 6.4. Kích hoạt thư viện
Thư viện hỗ trợ **Spring Boot Auto-configuration**. Bạn chỉ cần import dependency là thư viện sẽ tự động kích hoạt.
Hoặc bạn có thể gắn `@EnableFeatureFlag` trên Application class chính để tường minh:
```java
@SpringBootApplication
@EnableFeatureFlag
public class TrackingOrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrackingOrderApplication.class, args);
    }
}
```

---

## 7. Hướng dẫn sử dụng thực tế

### 7.1. Chặn API / Method bằng Annotation `@RequireFeature`
Gắn annotation trực tiếp trên API Controller hoặc Service Method:
```java
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    // Chặn nếu cờ BUY_NOW bị TẮT
    @PostMapping("/buy-now")
    @RequireFeature(features = "BUY_NOW", message = "Tính năng Mua Ngay đang bảo trì!")
    public ResponseEntity<OrderRes> buyNow(@RequestBody OrderReq req) {
        return ResponseEntity.ok(orderService.buyNow(req));
    }

    // Yêu cầu ĐỒNG THỜI cả 2 cờ ORDER_DETAIL và EXPORT_INVOICE phải BẬT
    @GetMapping("/{id}")
    @RequireFeature(features = {"ORDER_DETAIL", "EXPORT_INVOICE"})
    public ResponseEntity<OrderDetailRes> getOrderDetail(@PathVariable String id) {
        return ResponseEntity.ok(orderService.getDetail(id));
    }
}
```

### 7.2. Kiểm tra cờ thủ công trong mã nguồn Java
Tiêm (Inject) `FeatureFlagClient` vào Service nghiệp vụ:
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final FeatureFlagClient featureFlagClient;

    public void processPayment(Order order) {
        // Kiểm tra cờ tăng giá
        if (featureFlagClient.isEnabled("PRICE_INCREASE")) {
            log.info("Áp dụng biểu phí mới theo chính sách Feature Flag");
            applyNewPricing(order);
        } else {
            applyStandardPricing(order);
        }
    }
}
```

### 7.3. Trả về toàn bộ cờ cho Frontend khi Đăng nhập (Auth Service)
Khi người dùng đăng nhập thành công hoặc refresh token, gọi `evaluateAll()` để lấy trạng thái tất cả cờ trả về cho giao diện:
```java
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final FeatureFlagClient featureFlagClient;

    public AuthRes login(LoginReq req) {
        // ... Xác thực tài khoản ...

        // Đánh giá song song toàn bộ cờ cho User này (Tốc độ RAM, không nghẽn DB)
        Map<String, Boolean> features = featureFlagClient.evaluateAll();

        return AuthRes.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .features(features) // Gửi kèm Map cờ để Frontend lưu vào LocalStorage/Redux
                .build();
    }
}
```

### 7.4. Tạo Endpoint nhận đồng bộ Snapshot từ Central Admin
Trong microservice tiêu thụ, tạo Controller nội bộ để nhận snapshot đẩy từ `admin-feature-flag-service`:
```java
@RestController
@RequestMapping("/api/v1/feature-flags")
@RequiredArgsConstructor
public class InternalFeatureFlagController {

    private final FeatureFlagClient featureFlagClient;

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncSnapshot(
            @RequestBody FeatureFlagSyncRequest request
    ) {
        int count = featureFlagClient.syncSnapshot(request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "syncedFeatures", count,
                "message", "Đã cập nhật snapshot và làm mới In-Memory Cache thành công"
        ));
    }
}
```

### 7.5. Xử lý Exception tập trung (`RestExceptionHandler`)
Đăng ký handler để format lỗi trả về đẹp mắt khi dính `@RequireFeature`:
```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FeatureFlagDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleFeatureDisabled(FeatureFlagDisabledException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "status", ex.getStatus().value(),
                "error", "FEATURE_DISABLED",
                "message", ex.getMessage()
        ));
    }
}
```

---

### 7.6. Định dạng Snapshot JSON và 5 Loại Chiến lược hỗ trợ

Ví dụ payload snapshot được gửi từ Server Admin:
```json
{
  "version": "v-20260925-1",
  "exportedAt": "2026-09-25T15:00:00Z",
  "features": [
    {
      "flagName": "BUY_NOW",
      "enabled": true,
      "strategyLogic": "OR",
      "strategies": [
        {
          "strategyId": "username",
          "params": {
            "users": "admin, tester_01, vip_buyer"
          }
        },
        {
          "strategyId": "user_role",
          "params": {
            "roles": "BUYER, MANAGER"
          }
        }
      ]
    },
    {
      "flagName": "NEW_CHECKOUT_FLOW",
      "enabled": true,
      "strategyLogic": "AND",
      "strategies": [
        {
          "strategyId": "remote-client-ip",
          "params": {
            "ips": "127.0.0.1, 192.168.1.100, 10.0.0.1"
          }
        },
        {
          "strategyId": "release-date",
          "params": {
            "date": "2026-09-01 00:00:00"
          }
        },
        {
          "strategyId": "gradual-rollout",
          "params": {
            "percentage": "30"
          }
        }
      ]
    }
  ]
}
```

#### Bảng tóm tắt 5 loại chiến lược tích hợp sẵn:
| STT | Tên Chiến Lược | Strategy IDs hỗ trợ | Tham số cấu hình (`params`) | Cơ chế hoạt động |
| :-: | :--- | :--- | :--- | :--- |
| **1** | **User Whitelist** | `username`, `users_by_name` | `users` hoặc `value` (phân tách dấu phẩy) | Bật nếu `SecurityContext.username` khớp với danh sách. |
| **2** | **Role Based** | `user-role`, `user_role`, `role` | `roles` hoặc `role` hoặc `value` | Bật nếu User có quyền khớp (hỗ trợ cả prefix `ROLE_`). |
| **3** | **IP Whitelist** | `remote-client-ip`, `ip_whitelist`, `ip` | `ips` hoặc `value` | Đọc IP từ `X-Forwarded-For` / remoteAddr, chuẩn hóa qua `InetAddress`. |
| **4** | **Release Date** | `release-date`, `release_date` | `date` hoặc `releaseDate` | Bật tự động nếu `LocalDateTime.now()` đã vượt mốc thời gian cấu hình. |
| **5** | **Gradual Rollout** | `gradual-rollout`, `gradual_rollout`, `rollout` | `percentage` hoặc `value` (0 - 100) | Hash định danh `username` (hoặc `clientIp`) `% 100` để chia tỉ lệ nhất quán. |

---

## 8. Trạng thái phát triển & Ghi chú/TODO

### 8.1. Các tính năng đã hoàn thiện 100%
- [x] Đóng gói chuẩn thư viện Spring Boot Auto-configuration.
- [x] Hỗ trợ AOP `@RequireFeature` chặn method khai báo.
- [x] Cung cấp Facade an toàn `FeatureFlagClient` với cơ chế Fallback phòng vệ.
- [x] Tối ưu hóa In-Memory Cache (RAM) với `ConcurrentHashMap` đạt tốc độ $O(1)$.
- [x] Tách 5 loại chiến lược theo **Strategy Pattern** độc lập, tuân thủ nguyên lý **OCP**.
- [x] Cơ chế **Context Snapshotting** loại trừ hoàn toàn rủi ro mất `ThreadLocal` / `SecurityContext` trong luồng con.
- [x] Song song hóa việc đánh giá toàn bộ cờ qua Spring `ThreadPoolTaskExecutor` (tiền tố `ff-eval-`).
- [x] Chuẩn hóa Network IP bằng `InetAddress`, hỗ trợ xử lý Header `X-Forwarded-For` từ Load Balancer/Proxy.

### 8.2. Ghi chú & Đề xuất nâng cấp (TODO)
* **Xác thực bảo mật endpoint `/sync`**: Thư viện không áp đặt cơ chế bảo mật cho endpoint đồng bộ snapshot. Khi cài đặt Controller tiếp nhận tại các microservice, khuyến nghị lập trình viên thêm header secret token (ví dụ `X-Internal-Token`) hoặc tích hợp OAuth2/mTLS để ngăn chặn các request giả mạo.
* **Hỗ trợ Multi-Replica Pods**: Hiện tại In-Memory Cache được cập nhật khi pod nhận request `/sync`. Nếu một microservice chạy nhiều replica pods nằm sau Load Balancer, cần đảm bảo request `/sync` được broadcast tới tất cả các pod (qua WebSocket / Redis Pub-Sub / Kafka) hoặc áp dụng TTL định kỳ gọi `refreshCache()`.
