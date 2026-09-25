package com.example.featureflag.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * @RequireFeature(features = {"ORDER_DETAIL", "ORDER_DETAIL"}, message = "This feature is currently under maintenance")
 * @RequireFeature(features = {"ORDER_DETAIL", "ORDER_DETAIL"})
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireFeature {

    String[] features() default {};

    String message() default "Tính năng đang bảo trì";
}
