package com.example.featureflag.annotation;

import com.example.featureflag.config.FeatureFlagAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(FeatureFlagAutoConfiguration.class)
@ComponentScan(basePackages = "com.example.featureflag")
@AutoConfigurationPackage(basePackages = "com.example.featureflag")
public @interface EnableFeatureFlag {
}