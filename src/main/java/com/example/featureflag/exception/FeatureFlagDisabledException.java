package com.example.featureflag.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;


@Getter
public class FeatureFlagDisabledException extends RuntimeException {

    private final HttpStatus status;

    public FeatureFlagDisabledException(String message) {
        super(message);
        this.status = HttpStatus.BAD_REQUEST;
    }

    public FeatureFlagDisabledException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
