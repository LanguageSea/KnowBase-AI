package com.knowbase.model;

import java.util.Locale;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;

public enum ModelType {
    CHAT,
    EMBEDDING,
    RERANK;

    public static ModelType parse(String value) {
        try {
            return ModelType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "模型类型只能是 CHAT、EMBEDDING 或 RERANK");
        }
    }
}
