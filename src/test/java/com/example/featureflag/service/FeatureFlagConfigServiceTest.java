package com.example.featureflag.service;

import com.example.featureflag.entity.FeatureFlagConfig;
import com.example.featureflag.repository.FeatureFlagConfigRepo;
import com.example.featureflag.service.impl.FeatureFlagConfigServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureFlagConfigServiceTest {

    @Mock
    private FeatureFlagConfigRepo repo;

    private ObjectMapper objectMapper;
    private FeatureFlagConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Vấn đề 1: Nếu 1 cờ ném Exception trong async evaluateAll(), cờ đó false, các cờ khác không bị ảnh hưởng")
    void testEvaluateAll_WhenOneFlagFails_OtherFlagsRemainWorking() {
        FeatureFlagConfig flag1 = new FeatureFlagConfig();
        flag1.setId("1");
        flag1.setFlagName("BUY");
        flag1.setEnabled(true);

        FeatureFlagConfig flag2Corrupt = new FeatureFlagConfig();
        flag2Corrupt.setId("2");
        flag2Corrupt.setFlagName("ORDER_DETAIL");
        flag2Corrupt.setEnabled(true);
        // Cố tình tạo strategies JSON lỗi syntax để ném ngoại lệ khi parse
        flag2Corrupt.setStrategies("{invalid_json: true");

        FeatureFlagConfig flag3 = new FeatureFlagConfig();
        flag3.setId("3");
        flag3.setFlagName("PRICE_INCREASE");
        flag3.setEnabled(true);

        when(repo.findAll()).thenReturn(List.of(flag1, flag2Corrupt, flag3));

        service = new FeatureFlagConfigServiceImpl(
                repo,
                objectMapper,
                Executors.newFixedThreadPool(4),
                List.of()
        );
        service.initCache();

        Map<String, Boolean> result = service.evaluateAll();

        assertNotNull(result);
        assertEquals(3, result.size());
        assertTrue(result.get("BUY"), "BUY cờ bình thường phải là TRUE");
        assertFalse(result.get("ORDER_DETAIL"), "ORDER_DETAIL bị lỗi phải là FALSE");
        assertTrue(result.get("PRICE_INCREASE"), "PRICE_INCREASE cờ bình thường phải là TRUE");
    }

    @Test
    @DisplayName("Vấn đề 2: Cha OFF -> Con OFF ngay lập tức dù con cấu hình enabled = true")
    void testParentChild_WhenParentIsOff_ChildIsOff() {
        FeatureFlagConfig parentBuy = new FeatureFlagConfig();
        parentBuy.setId("parent-1");
        parentBuy.setFlagName("BUY");
        parentBuy.setEnabled(false); // Cờ cha TẮT
        parentBuy.setParentId(null);

        FeatureFlagConfig childBuyNow = new FeatureFlagConfig();
        childBuyNow.setId("child-1.1");
        childBuyNow.setFlagName("BUY_NOW");
        childBuyNow.setEnabled(true); // Cờ con BẬT
        childBuyNow.setParentId("parent-1"); // Trỏ tới cha

        when(repo.findAll()).thenReturn(List.of(parentBuy, childBuyNow));

        service = new FeatureFlagConfigServiceImpl(
                repo,
                objectMapper,
                Executors.newSingleThreadExecutor(),
                List.of()
        );
        service.initCache();

        // Kiểm tra cờ con BUY_NOW
        boolean isBuyNowEnabled = service.isEnabled("BUY_NOW");
        assertFalse(isBuyNowEnabled, "Cờ con BUY_NOW bắt buộc phải là FALSE vì cờ cha BUY đang OFF");
    }

    @Test
    @DisplayName("Vấn đề 2: Cha ON -> Con mới được đánh giá (enabled = true)")
    void testParentChild_WhenParentIsOn_ChildIsOn() {
        FeatureFlagConfig parentBuy = new FeatureFlagConfig();
        parentBuy.setId("parent-1");
        parentBuy.setFlagName("BUY");
        parentBuy.setEnabled(true); // Cờ cha BẬT
        parentBuy.setParentId(null);

        FeatureFlagConfig childBuyNow = new FeatureFlagConfig();
        childBuyNow.setId("child-1.1");
        childBuyNow.setFlagName("BUY_NOW");
        childBuyNow.setEnabled(true); // Cờ con BẬT
        childBuyNow.setParentId("parent-1"); // Trỏ tới cha

        when(repo.findAll()).thenReturn(List.of(parentBuy, childBuyNow));

        service = new FeatureFlagConfigServiceImpl(
                repo,
                objectMapper,
                Executors.newSingleThreadExecutor(),
                List.of()
        );
        service.initCache();

        // Kiểm tra cờ con BUY_NOW
        boolean isBuyNowEnabled = service.isEnabled("BUY_NOW");
        assertTrue(isBuyNowEnabled, "Cờ con BUY_NOW phải là TRUE vì cờ cha BUY đang ON và con cũng ON");
    }

    @Test
    @DisplayName("Vấn đề 2: Phát hiện vòng lặp vô tận (A -> B -> A) và trả về false an toàn")
    void testParentChild_CircularDependency() {
        FeatureFlagConfig flagA = new FeatureFlagConfig();
        flagA.setId("flag-A");
        flagA.setFlagName("FEATURE_A");
        flagA.setEnabled(true);
        flagA.setParentId("flag-B");

        FeatureFlagConfig flagB = new FeatureFlagConfig();
        flagB.setId("flag-B");
        flagB.setFlagName("FEATURE_B");
        flagB.setEnabled(true);
        flagB.setParentId("flag-A");

        when(repo.findAll()).thenReturn(List.of(flagA, flagB));

        service = new FeatureFlagConfigServiceImpl(
                repo,
                objectMapper,
                Executors.newSingleThreadExecutor(),
                List.of()
        );
        service.initCache();

        // Đảm bảo không bị StackOverflowError và trả về false
        assertDoesNotThrow(() -> {
            boolean enabled = service.isEnabled("FEATURE_A");
            assertFalse(enabled, "Vòng lặp phải được ngắt và trả về false an toàn");
        });
    }
}