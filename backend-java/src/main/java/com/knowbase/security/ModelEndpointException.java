package com.knowbase.security;

/**
 * Stable SSRF validation failure. The code is safe to show in model test results.
 */
public class ModelEndpointException extends RuntimeException {

    private final String code;

    public ModelEndpointException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
