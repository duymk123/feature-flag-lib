package com.example.featureflag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Table(
        name = "feature_flag_configs",
        indexes = {
                @Index(name = "idx_feature_flag_name", columnList = "flag_name")
        }
)
public class FeatureFlagConfig extends BaseEntity {
    @Id
    @UuidGenerator
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "flag_name", nullable = false, length = 100)
    private String flagName;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "strategies", columnDefinition = "json")
    private String strategies;

    @Column(name = "strategy_logic", length = 3)
    private String strategyLogic;

    @Column(name = "applied_version", nullable = false, length = 100)
    private String appliedVersion;
}