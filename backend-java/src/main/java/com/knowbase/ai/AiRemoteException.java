package com.knowbase.ai;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;

/**
 * Python 返回的可分类错误。HTTP 层仍是 503，worker 用 remoteCode 保留
 * NO_TEXT_LAYER / UNSUPPORTED_FORMAT 等可展示原因。
 */
public class AiRemoteException extends BusinessException {

    private final String remoteCode;

    public AiRemoteException(String remoteCode, String message) {
        super(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
        this.remoteCode = remoteCode == null || remoteCode.isBlank()
                ? "DEPENDENCY_UNAVAILABLE" : remoteCode;
    }

    public String remoteCode() {
        return remoteCode;
    }
}
