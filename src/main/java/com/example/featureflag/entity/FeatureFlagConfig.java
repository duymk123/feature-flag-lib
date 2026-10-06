package com.example.featureflag.entity;

import com.example.featureflag.dto.StrategyItemSync;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.util.List;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Table(
        name = "feature_flag_configs",
        indexes = {
                @Index(name = "idx_feature_flag_name", columnList = "flag_name"),
                @Index(name = "idx_feature_flag_parent_id", columnList = "parent_id")
        }
)
public class FeatureFlagConfig extends BaseEntity implements Persistable<String> {
    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "flag_name", nullable = false, length = 100)
    private String flagName;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "parent_id", length = 36)
    private String parentId;

    @Column(name = "strategies", columnDefinition = "json")
    private String strategies;

    @Column(name = "strategy_logic", length = 3)
    private String strategyLogic;

    @Column(name = "applied_version", nullable = false, length = 100)
    private String appliedVersion;

    /**
     * Pre-parsed strategies cache (RAM only, not persisted to DB).
     * Avoids calling ObjectMapper.readValue() on every evaluate call.
     */
    @Transient
    private List<StrategyItemSync> parsedStrategies;

    @Transient
    private boolean isNewEntity = false;

    @Override
    public boolean isNew() {
        return isNewEntity || getId() == null;
    }

    public void setNewEntity(boolean isNew) {
        this.isNewEntity = isNew;
    }
}
