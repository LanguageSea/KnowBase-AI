-- ============================================================================
--  KnowBase AI — V1 初始化脚本
--  MySQL 8.4 / InnoDB / utf8mb4
--
--  本文件实现 DEVELOPMENT_PLAN.md §7 的 17 张表。
--  每张表都显式声明了 §7.2 要求的六项：
--    1. 主键策略   2. 唯一约束   3. 查询索引
--    4. 外键策略   5. 时间戳     6. 删除策略
--
--  删除策略约定（§7.3）：
--    - 用户      → 禁用（status=DISABLED），不物理删
--    - 知识库    → 归档（status=ARCHIVED），空库才允许物理删
--    - 文档      → 软删（status: ACTIVE → DELETING → DELETED），行保留
--    - 会话      → 物理删，应用层级联清理 message / citation
--    - 分片      → 随文档清理，物理删
--  外键统一 ON DELETE RESTRICT（ai_call_log 除外，它要保留历史），
--  级联动作全部由应用层按上述顺序执行，不用 CASCADE。
--
--  时间戳统一 DATETIME(3)，由应用层写入（便于测试可控），不使用数据库默认值。
--  布尔列统一 TINYINT（不带显示宽度，MySQL 8.4 已废弃 TINYINT(1) 写法）。
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. sys_role — 角色定义
-- ---------------------------------------------------------------------------
CREATE TABLE sys_role (
    id          INT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    code        VARCHAR(64)  NOT NULL COMMENT '角色编码，如 SYS_ADMIN / KB_ADMIN / USER',
    name        VARCHAR(64)  NOT NULL COMMENT '显示名称',
    description VARCHAR(255)     NULL COMMENT '说明',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '角色';

-- ---------------------------------------------------------------------------
-- 2. sys_user — 账户
--    token_version：JWT 内携带，登出/改密/禁用时自增即可让已签发 token 失效（D9）
-- ---------------------------------------------------------------------------
CREATE TABLE sys_user (
    id             INT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    username       VARCHAR(64)  NOT NULL COMMENT '登录名',
    password_hash  VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，禁止明文',
    nickname       VARCHAR(64)  NOT NULL COMMENT '显示名',
    email          VARCHAR(128)     NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    token_version  INT          NOT NULL DEFAULT 0 COMMENT '自增即失效全部已签发 token',
    last_login_at  DATETIME(3)      NULL,
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    KEY idx_user_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户账户';

-- ---------------------------------------------------------------------------
-- 3. sys_user_role — 用户与角色
-- ---------------------------------------------------------------------------
CREATE TABLE sys_user_role (
    user_id    INT         NOT NULL,
    role_id    INT         NOT NULL,
    granted_by INT             NULL COMMENT '授权操作人',
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_user_role_role (role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户角色关联';

-- ---------------------------------------------------------------------------
-- 4. sys_user_login_log — 登录记录（失败锁定的证据链）
--    user_id 可空：用户名不存在时也要留痕，但不能因此暴露用户是否存在
-- ---------------------------------------------------------------------------
CREATE TABLE sys_user_login_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     INT             NULL COMMENT '登录失败且用户不存在时为 NULL',
    username    VARCHAR(64) NOT NULL COMMENT '本次尝试的用户名，保留原样便于审计',
    ip          VARCHAR(64)     NULL,
    user_agent  VARCHAR(512)    NULL,
    success     TINYINT     NOT NULL,
    fail_reason VARCHAR(128)    NULL,
    created_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_login_user_time (user_id, created_at),
    KEY idx_login_username_time (username, created_at),
    KEY idx_login_time (created_at),
    CONSTRAINT fk_login_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '登录日志';

-- ---------------------------------------------------------------------------
-- 5. sys_operation_log — 关键操作审计
-- ---------------------------------------------------------------------------
CREATE TABLE sys_operation_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    operator_id   INT              NULL COMMENT '系统自动操作时为空',
    action        VARCHAR(64)  NOT NULL COMMENT '如 KB_CREATE / DOC_DELETE / MODEL_UPDATE',
    resource_type VARCHAR(64)  NOT NULL COMMENT '如 KB / DOCUMENT / MODEL / USER',
    resource_id   VARCHAR(64)      NULL,
    result        VARCHAR(16)  NOT NULL COMMENT 'SUCCESS / FAILURE',
    detail        TEXT             NULL COMMENT '结构化详情，禁止写入密钥',
    ip            VARCHAR(64)      NULL,
    trace_id      VARCHAR(64)      NULL,
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_oplog_operator_time (operator_id, created_at),
    KEY idx_oplog_resource (resource_type, resource_id),
    KEY idx_oplog_time (created_at),
    CONSTRAINT fk_oplog_operator FOREIGN KEY (operator_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '操作审计';

-- ---------------------------------------------------------------------------
-- 6. kb_knowledge_base — 知识库
-- ---------------------------------------------------------------------------
CREATE TABLE kb_knowledge_base (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(1024)    NULL,
    category    VARCHAR(64)      NULL,
    owner_id    INT          NOT NULL COMMENT '创建人/负责人',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_owner_name (owner_id, name) COMMENT '同一人下不允许重名',
    KEY idx_kb_status (status),
    KEY idx_kb_owner (owner_id),
    CONSTRAINT fk_kb_owner FOREIGN KEY (owner_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '知识库';

-- ---------------------------------------------------------------------------
-- 7. kb_member — 知识库成员与权限
-- ---------------------------------------------------------------------------
CREATE TABLE kb_member (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    kb_id      BIGINT      NOT NULL,
    user_id    INT         NOT NULL,
    role       VARCHAR(16) NOT NULL COMMENT 'VIEWER / EDITOR / MANAGER',
    invited_by INT             NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_kb_user (kb_id, user_id) COMMENT '防止重复授权',
    KEY idx_member_user (user_id),
    CONSTRAINT fk_member_kb FOREIGN KEY (kb_id) REFERENCES kb_knowledge_base (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_role CHECK (role IN ('VIEWER', 'EDITOR', 'MANAGER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '知识库成员';

-- ---------------------------------------------------------------------------
-- 8. kb_audit_grant — 授权变更留痕（只追加，不更新不删除）
-- ---------------------------------------------------------------------------
CREATE TABLE kb_audit_grant (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    kb_id          BIGINT      NOT NULL,
    target_user_id INT         NOT NULL,
    action         VARCHAR(32) NOT NULL COMMENT 'GRANT / REVOKE / ROLE_CHANGE',
    old_role       VARCHAR(16)     NULL,
    new_role       VARCHAR(16)     NULL,
    operator_id    INT         NOT NULL,
    created_at     DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_grant_kb_time (kb_id, created_at),
    KEY idx_grant_target (target_user_id),
    CONSTRAINT fk_grant_kb FOREIGN KEY (kb_id) REFERENCES kb_knowledge_base (id) ON DELETE RESTRICT,
    CONSTRAINT fk_grant_target FOREIGN KEY (target_user_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_grant_operator FOREIGN KEY (operator_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '授权变更记录';

-- ---------------------------------------------------------------------------
-- 9. kb_document — 文档元数据与状态
--    dedup_key：MySQL 没有部分唯一索引，无法约束「仅 ACTIVE 文档的 sha256 唯一」。
--    因此用 dedup_key 承载唯一性：文档存活时 = sha256，进入 DELETED 时置 NULL。
--    MySQL 唯一索引允许多个 NULL，从而允许同一文件在删除后重新上传。
--    sha256 列本身始终保留，供审计与幂等校验使用。
-- ---------------------------------------------------------------------------
CREATE TABLE kb_document (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    kb_id        BIGINT        NOT NULL,
    name         VARCHAR(255)  NOT NULL COMMENT '原始文件名，仅展示用，不参与路径拼接',
    ext          VARCHAR(16)   NOT NULL COMMENT 'pdf / docx / txt / md',
    mime_type    VARCHAR(128)      NULL,
    size_bytes   BIGINT        NOT NULL,
    sha256       CHAR(64)      NOT NULL COMMENT '始终保留',
    dedup_key    CHAR(64)          NULL COMMENT 'ACTIVE 时 = sha256；DELETED 时置 NULL',
    storage_key  VARCHAR(255)  NOT NULL COMMENT 'UUID 相对路径，如 2026/10/<uuid>.pdf',
    status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
                 COMMENT 'PENDING / PROCESSING / ACTIVE / FAILED / DELETING / DELETED / DELETE_FAILED',
    chunk_count  INT           NOT NULL DEFAULT 0,
    error_code   VARCHAR(64)       NULL,
    error_msg    VARCHAR(2000)     NULL,
    uploaded_by  INT           NOT NULL,
    indexed_at   DATETIME(3)       NULL,
    created_at   DATETIME(3)   NOT NULL,
    updated_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_dedup (kb_id, dedup_key) COMMENT '同库内 ACTIVE 文档按内容去重',
    KEY idx_doc_kb_status (kb_id, status),
    KEY idx_doc_status (status),
    KEY idx_doc_uploader (uploaded_by),
    CONSTRAINT fk_doc_kb FOREIGN KEY (kb_id) REFERENCES kb_knowledge_base (id) ON DELETE RESTRICT,
    CONSTRAINT fk_doc_uploader FOREIGN KEY (uploaded_by) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_doc_status CHECK (status IN ('PENDING','PROCESSING','ACTIVE','FAILED',
                                               'DELETING','DELETED','DELETE_FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '文档';

-- ---------------------------------------------------------------------------
-- 10. kb_document_chunk — 文本分片与向量 ID 关联
--     uk_chunk_id_doc (id, doc_id)：不是冗余，是给 ai_message_citation 的复合外键
--     做参照目标，从数据库层保证「引用里的分片确实属于该文档」。
-- ---------------------------------------------------------------------------
CREATE TABLE kb_document_chunk (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id      BIGINT       NOT NULL,
    seq         INT          NOT NULL COMMENT '文档内顺序，从 0 开始',
    content     MEDIUMTEXT   NOT NULL,
    page_no     INT              NULL COMMENT '页码，TXT 等无页码格式为空',
    heading     VARCHAR(512)     NULL COMMENT '所属标题路径，如 "第三章 > 3.2 权限"',
    token_count INT              NULL,
    vector_id   VARCHAR(64)      NULL COMMENT 'Qdrant point id',
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chunk_doc_seq (doc_id, seq),
    UNIQUE KEY uk_chunk_id_doc (id, doc_id) COMMENT '供引用表复合外键使用',
    CONSTRAINT fk_chunk_doc FOREIGN KEY (doc_id) REFERENCES kb_document (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '文档分片';

-- ---------------------------------------------------------------------------
-- 11. kb_ingestion_task — 文档处理任务
--     并发控制不靠唯一索引（MySQL 无部分唯一索引），靠条件 UPDATE 原子认领，见 STATE.md
-- ---------------------------------------------------------------------------
CREATE TABLE kb_ingestion_task (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id       BIGINT       NOT NULL,
    type         VARCHAR(32)  NOT NULL DEFAULT 'INGEST' COMMENT 'INGEST / DELETE / RECONCILE',
    status       VARCHAR(16)  NOT NULL DEFAULT 'QUEUED'
                 COMMENT 'QUEUED / RUNNING / SUCCESS / FAILED / DEAD',
    attempt      INT          NOT NULL DEFAULT 0 COMMENT '已执行次数',
    max_attempt  INT          NOT NULL DEFAULT 3,
    locked_by    VARCHAR(64)      NULL COMMENT '实例标识，用于僵尸回收',
    locked_at    DATETIME(3)      NULL,
    error_code   VARCHAR(64)      NULL,
    error_msg    VARCHAR(2000)    NULL,
    started_at   DATETIME(3)      NULL,
    finished_at  DATETIME(3)      NULL,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_task_status_created (status, created_at) COMMENT '队列扫描',
    KEY idx_task_status_locked (status, locked_at) COMMENT '超时回收',
    KEY idx_task_doc (doc_id, status),
    CONSTRAINT fk_task_doc FOREIGN KEY (doc_id) REFERENCES kb_document (id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_status CHECK (status IN ('QUEUED','RUNNING','SUCCESS','FAILED','DEAD'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '文档处理任务';

-- ---------------------------------------------------------------------------
-- 12. ai_model_config — 模型服务配置
--     api_key_cipher 为 AES-GCM 密文（主密钥 APP_MASTER_KEY），永不返回明文
-- ---------------------------------------------------------------------------
CREATE TABLE ai_model_config (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    provider        VARCHAR(64)  NOT NULL COMMENT '如 deepseek / openai / ollama',
    model_type      VARCHAR(16)  NOT NULL COMMENT 'CHAT / EMBEDDING / RERANK',
    model_name      VARCHAR(128) NOT NULL,
    base_url        VARCHAR(512) NOT NULL,
    api_key_cipher  VARCHAR(1024)    NULL COMMENT 'AES-GCM 密文，禁止明文',
    params          JSON             NULL COMMENT '温度、top_p、维度等兼容参数',
    enabled         TINYINT      NOT NULL DEFAULT 1,
    last_test_at    DATETIME(3)      NULL,
    last_test_ok    TINYINT          NULL,
    last_test_error VARCHAR(512)     NULL,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_identity (provider, model_name, model_type),
    KEY idx_model_type_enabled (model_type, enabled),
    CONSTRAINT ck_model_type CHECK (model_type IN ('CHAT','EMBEDDING','RERANK'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '模型服务配置';

-- ---------------------------------------------------------------------------
-- 13. ai_conversation — 会话
-- ---------------------------------------------------------------------------
CREATE TABLE ai_conversation (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    INT          NOT NULL,
    title      VARCHAR(255) NOT NULL DEFAULT '新对话',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
    created_at DATETIME(3)  NOT NULL,
    updated_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conv_user_time (user_id, updated_at),
    CONSTRAINT fk_conv_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '问答会话';

-- ---------------------------------------------------------------------------
-- 14. ai_conversation_kb — 会话 × 知识库
--     替代 JSON 字段：多库会话需要在检索前按库校验权限，JSON 无法建索引也无法外键
-- ---------------------------------------------------------------------------
CREATE TABLE ai_conversation_kb (
    conversation_id BIGINT      NOT NULL,
    kb_id           BIGINT      NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (conversation_id, kb_id),
    KEY idx_convkb_kb (kb_id),
    CONSTRAINT fk_convkb_conv FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_convkb_kb FOREIGN KEY (kb_id) REFERENCES kb_knowledge_base (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '会话关联知识库';

-- ---------------------------------------------------------------------------
-- 15. ai_message — 消息
-- ---------------------------------------------------------------------------
CREATE TABLE ai_message (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id   BIGINT      NOT NULL,
    role              VARCHAR(16) NOT NULL COMMENT 'USER / ASSISTANT / SYSTEM',
    content           MEDIUMTEXT  NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'OK'
                      COMMENT 'OK / STREAMING / CANCELLED / FAILED / EMPTY_COMPLETION',
    model_name        VARCHAR(128)    NULL,
    latency_ms        INT             NULL,
    prompt_tokens     INT             NULL,
    completion_tokens INT             NULL,
    error_code        VARCHAR(64)     NULL,
    created_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_msg_conv (conversation_id, id),
    CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id) ON DELETE RESTRICT,
    CONSTRAINT ck_msg_role CHECK (role IN ('USER','ASSISTANT','SYSTEM'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '问答消息';

-- ---------------------------------------------------------------------------
-- 16. ai_message_citation — 引用
--     复合外键 (chunk_id, doc_id) → kb_document_chunk(id, doc_id)
--     保证「引用里的分片确实属于对应文档」，防止模型或代码张冠李戴
-- ---------------------------------------------------------------------------
CREATE TABLE ai_message_citation (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    message_id BIGINT        NOT NULL,
    chunk_id   BIGINT        NOT NULL,
    doc_id     BIGINT        NOT NULL,
    rank_no    INT           NOT NULL COMMENT '在本次回答中的引用序号，从 1 开始',
    score      DOUBLE            NULL COMMENT '检索相似度分数',
    quote      VARCHAR(1000)     NULL COMMENT '引用原文片段快照',
    created_at DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_citation_msg_chunk (message_id, chunk_id),
    KEY idx_citation_chunk (chunk_id),
    KEY idx_citation_doc (doc_id),
    CONSTRAINT fk_citation_msg FOREIGN KEY (message_id) REFERENCES ai_message (id) ON DELETE RESTRICT,
    CONSTRAINT fk_citation_chunk_doc FOREIGN KEY (chunk_id, doc_id)
        REFERENCES kb_document_chunk (id, doc_id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '回答引用';

-- ---------------------------------------------------------------------------
-- 17. ai_call_log — 模型调用记录
--     model_config_id 使用 SET NULL：配置被删后统计历史仍须保留
-- ---------------------------------------------------------------------------
CREATE TABLE ai_call_log (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    model_config_id   BIGINT          NULL,
    scene             VARCHAR(32) NOT NULL COMMENT 'CHAT / EMBED / SEARCH / TEST',
    success           TINYINT     NOT NULL,
    latency_ms        INT             NULL,
    prompt_tokens     INT             NULL,
    completion_tokens INT             NULL,
    error_type        VARCHAR(32)     NULL COMMENT 'TIMEOUT / AUTH / RATE_LIMIT / NETWORK / EMPTY',
    error_msg         VARCHAR(512)    NULL,
    trace_id          VARCHAR(64)     NULL,
    created_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_call_time (created_at),
    KEY idx_call_model_time (model_config_id, created_at),
    KEY idx_call_scene_time (scene, created_at),
    CONSTRAINT fk_call_model FOREIGN KEY (model_config_id) REFERENCES ai_model_config (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '模型调用日志';
