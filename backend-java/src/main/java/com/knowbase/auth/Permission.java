package com.knowbase.auth;

/**
 * 系统级权限点。知识库读/写/管理属于资源级权限，必须走 AuthzService，
 * 不能仅凭这里的角色判断。
 */
public enum Permission {
    USER_MANAGE,
    MODEL_MANAGE,
    SYSTEM_MONITOR
}
