# Thiết kế đánh giá Feature Flag với tập dữ liệu lớn

## 1. Mục tiêu

Bài benchmark 5.000 flag dùng để đo độ trễ và kiểm tra khả năng chịu tải. Mỗi task xử lý đúng một flag để worker quay lại pool ngay sau khi đánh giá xong. Số task đang chạy/chờ từ một lần gọi được giới hạn, tránh submit 5.000 task cùng lúc.

Không thể cam kết xử lý số flag vô hạn: cache, map kết quả và response login đều cần RAM; thời gian đánh giá và kích thước response cũng tăng theo số flag. Thiết kế này giới hạn phần công việc song song đang chờ. Nếu số flag vượt tài nguyên JVM hoặc giới hạn response của ứng dụng, cần chuyển sang đánh giá theo danh sách flag cần thiết hoặc API bất đồng bộ/paging.

## 2. Hai thread pool khác nhau

`spring.task.execution.pool.*` là cấu hình executor mặc định của Spring Boot. Thư viện tạo bean riêng tên `featureFlagExecutor`; các thuộc tính của bean này bind từ `feature-flag.executor.*`.

Ví dụ cấu hình khởi đầu trong `tracking-order/src/main/resources/application.yaml`:

```yaml
feature-flag:
  sync-token: change-me
  executor:
    core-pool-size: 4
    max-pool-size: 8
    queue-capacity: 16
    max-pending-tasks: 24
    thread-name-prefix: ff-eval-
    wait-for-tasks-to-complete-on-shutdown: true
    await-termination-seconds: 10
```

Đây là điểm bắt đầu để benchmark, không phải giá trị tối ưu cho mọi phần cứng. `max-pending-tasks: 24` đủ để lấp 4 worker lõi, queue 16 chỗ và cho pool cơ hội mở rộng đến 8 worker. Nếu queue capacity rất lớn, pool có thể tiếp tục xếp task vào queue và chưa tăng quá core size.

## 3. Cách đánh giá từng flag

Với 5.000 tên flag, có 5.000 lượt đánh giá/task theo thời gian, nhưng tối đa 24 task đang chạy hoặc chờ do một lần gọi tại một thời điểm. Khi một flag xong, `CompletionService` thu kết quả và submit flag tiếp theo. Worker vừa hoàn tất task được executor tái sử dụng cho task khác.

```text
submit tối đa 24 task
        ↓
một flag hoàn tất → thu kết quả → submit flag kế tiếp
        ↓
lặp tới khi đủ 5.000 kết quả
```

Mỗi task bắt lỗi của flag đó và trả `false` nếu cần. Kết quả được gom vào map trên request thread khi future hoàn tất; map không cần ghi đồng thời từ các worker.

## 4. Giới hạn task đang chờ và backpressure

`max-pending-tasks: 24` giới hạn số future một lần gọi `evaluateAll()` giữ đang chạy/chờ. Khi có task hoàn tất, request thu kết quả và submit một flag mới. Do đó số future đang được theo dõi không tăng theo toàn bộ số flag.

Queue của `ThreadPoolTaskExecutor` có dung lượng hữu hạn. Nếu pool và queue đều đầy, rejection handler chạy task mới trên thread đang submit. Đây là backpressure: task không bị loại bỏ và queue không tăng vô hạn. Đổi lại, request thread có thể phải xử lý một flag khi hệ thống quá tải.

Nếu ứng dụng import thư viện rồi tự khai báo bean `featureFlagExecutor`, bean tự khai báo thay thế executor mặc định. Khi đó ứng dụng tiêu thụ tự chịu trách nhiệm cấu hình queue và rejection handler có backpressure.

## 5. Vì sao request vẫn phải đợi đủ kết quả

Login hiện trả một response JSON có `Map<String, Boolean> features`. Server phải đợi tất cả flag xong trước khi tuần tự hóa và gửi map đầy đủ. `CompletionService.take()` chờ ở thread request nếu chưa có task hoàn tất; worker chỉ chạy công việc rồi kết thúc task, không chờ các worker khác.

Kết quả được gom vào map ngay khi mỗi flag đánh giá xong, nhưng client HTTP chỉ nhận response sau khi `evaluateAll()` trả về. Muốn gửi từng phần kết quả cho client cần đổi contract API, ví dụ SSE hoặc job bất đồng bộ có endpoint polling; điều đó không phù hợp với response login hiện tại nếu frontend cần map đầy đủ ngay.

## 6. Nạp DB vào cache

Khi `FeatureFlagConfigServiceImpl` khởi tạo, `@PostConstruct initCache()` gọi `refreshCache()`. Hàm này đọc `feature_flag_configs` từ **DB của tracking-order**, không đọc trực tiếp bảng `feature_flags` của `feature-flag-service`.

Từ các dòng DB local, thư viện tạo:

- `flagCache`: tên flag viết hoa → danh sách cấu hình/rule cho flag đó.
- `idCache`: ID cấu hình → entity, phục vụ tra cứu parent flag.

Sau bước nạp này, `isEnabled()` và `evaluateAll()` dùng cache RAM. Khi sync snapshot, thư viện lưu cấu hình vào DB local rồi gọi `refreshCache()` để cập nhật RAM. `feature-flag-service` chỉ gửi các flag đang được cấp quyền và chưa xóa; vì vậy 5.000 dòng ở DB trung tâm không đảm bảo tracking-order đã nhận đủ 5.000 flag. Hãy xác nhận số dòng trong `feature_flag_configs` của DB tracking-order sau apply.

Cache, snapshot map và response map đều có kích thước O(số flag). Thiết kế giới hạn future/queue, không loại bỏ chi phí tuyến tính bắt buộc này.

## 7. Kế hoạch benchmark

Chạy cùng một môi trường và cùng cấu hình rule, tăng dần số flag, ví dụ 100, 1.000, 5.000, 10.000. Mỗi mức nên đo riêng:

1. **Khởi động tracking-order:** thời gian `refreshCache()` và heap sau khi cache được nạp.
2. **Đồng bộ snapshot:** thời gian `/apply`, kích thước JSON, số dòng được ghi vào `feature_flag_configs`.
3. **Đánh giá một flag:** latency của API nghiệp vụ gọi `isEnabled()` sau warm-up.
4. **Đánh giá toàn bộ:** latency login/refresh token, số key trong `features`, CPU, heap, GC, queue depth, active threads, lỗi HTTP và log rejection.
5. **Tải đồng thời:** tăng số request login đồng thời; backpressure có thể làm tăng latency nhưng không được làm mất flag trong response hoặc làm service chết.

So sánh p50/p95/p99 thay vì chỉ lấy một request đơn lẻ. Để kết luận, kiểm tra rằng số key trong login response bằng số flag hợp lệ đã sync, các request hoàn tất, heap/GC ổn định và không có lỗi rejection.

## 8. Đưa code mới vào tracking-order

`tracking-order` hiện nạp thư viện từ `libs/feature-flag-lib-1.0.0.jar` trong Dockerfile. Sau khi build thư viện, phải thay JAR đó bằng JAR mới rồi build lại image tracking-order. Chỉ sửa `application.yaml` mà vẫn chạy JAR cũ sẽ không kích hoạt đánh giá song song theo từng flag, properties binding hoặc backpressure mới.

## 9. Các file code chính

- `src/main/java/com/example/featureflag/config/FeatureFlagExecutorProperties.java`: giá trị mặc định và properties bind từ YAML.
- `src/main/java/com/example/featureflag/config/FeatureFlagAutoConfiguration.java`: tạo pool và rejection handler backpressure.
- `src/main/java/com/example/featureflag/service/impl/FeatureFlagConfigServiceImpl.java`: cache RAM, đánh giá một flag mỗi task, giới hạn future đang chạy/chờ và thu kết quả hoàn tất.
