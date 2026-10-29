-- ============================================================================
--  R__seed.sql — 初始化数据（可重复执行，Flyway repeatable migration）
--
--  幂等策略：
--    - 角色：按唯一键 code 覆盖名称与状态
--    - 管理员：只在不存在时创建，**重复执行不会重置密码**
--    - 用户角色关联：INSERT IGNORE，天然幂等
--
--  默认管理员：admin / Admin@123456
--  ⚠ 该密码仅用于本地开发与演示，部署到任何可被访问的环境前必须修改。
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 角色
-- ---------------------------------------------------------------------------
INSERT INTO sys_role (code, name, description, status, created_at, updated_at)
VALUES ('SYS_ADMIN', '系统管理员', '用户、角色、模型服务、系统参数、审计日志', 'ACTIVE', NOW(3), NOW(3)) AS new
ON DUPLICATE KEY UPDATE name = new.name, description = new.description, status = new.status, updated_at = new.updated_at;

INSERT INTO sys_role (code, name, description, status, created_at, updated_at)
VALUES ('KB_ADMIN', '知识库管理员', '自有或获授权知识库的文档与成员管理', 'ACTIVE', NOW(3), NOW(3)) AS new
ON DUPLICATE KEY UPDATE name = new.name, description = new.description, status = new.status, updated_at = new.updated_at;

INSERT INTO sys_role (code, name, description, status, created_at, updated_at)
VALUES ('USER', '普通用户', '访问已授权知识库并进行 AI 问答', 'ACTIVE', NOW(3), NOW(3)) AS new
ON DUPLICATE KEY UPDATE name = new.name, description = new.description, status = new.status, updated_at = new.updated_at;

-- ---------------------------------------------------------------------------
-- 默认管理员
--   password_hash 为 BCrypt(cost=10) of "Admin@123456"
--   重复执行时只更新昵称，绝不覆盖 password_hash——
--   否则运维改过密码后，下一次部署会把密码重置回默认值。
-- ---------------------------------------------------------------------------
INSERT INTO sys_user (username, password_hash, nickname, email, status, token_version, created_at, updated_at)
VALUES ('admin',
        '$2a$10$2mbwAM8B7KEl/5K2jYyAIegLlCYDFbMQdDKTzB.jbyICbV3Dvgyta',
        '系统管理员',
        'admin@knowbase.local',
        'ACTIVE', 0, NOW(3), NOW(3)) AS new
ON DUPLICATE KEY UPDATE nickname = new.nickname, email = new.email;

-- ---------------------------------------------------------------------------
-- 管理员 → SYS_ADMIN
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_user_role (user_id, role_id, granted_by, created_at)
SELECT u.id, r.id, u.id, NOW(3)
FROM sys_user u
         JOIN sys_role r ON r.code = 'SYS_ADMIN'
WHERE u.username = 'admin';
