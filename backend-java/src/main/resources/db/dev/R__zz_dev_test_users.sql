-- Development-only test accounts. This location is loaded exclusively by application-dev.yml.
-- Password for all three accounts: Test@123456

SET NAMES utf8mb4;

INSERT INTO sys_user
    (username, password_hash, nickname, email, status, token_version, created_at, updated_at)
VALUES
    ('kbadmin', '$2a$10$o2hUXltngg5W5OEDyF7M9edcfVSLPwNYn7Z67bei6iJaLEsosPEEO',
     '知识库管理员', 'kbadmin@knowbase.local', 'ACTIVE', 0, NOW(3), NOW(3)),
    ('editor', '$2a$10$QamWKYlvJ7Bt7wB/FQ02jOJ9GHomt.iNVfW7wa4jKFd1GR8oWx92m',
     '编辑测试用户', 'editor@knowbase.local', 'ACTIVE', 0, NOW(3), NOW(3)),
    ('viewer', '$2a$10$Lg4xxK7lHsVFSVDV16Orce.h7RNI8O/mGbIVJX3QjcF6A9p7hbOh2',
     '只读测试用户', 'viewer@knowbase.local', 'ACTIVE', 0, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    nickname = VALUES(nickname),
    email = VALUES(email),
    status = 'ACTIVE';

INSERT IGNORE INTO sys_user_role (user_id, role_id, granted_by, created_at)
SELECT u.id, r.id, u.id, NOW(3)
FROM sys_user u
JOIN sys_role r ON r.code IN ('KB_ADMIN', 'USER')
WHERE u.username = 'kbadmin';

INSERT IGNORE INTO sys_user_role (user_id, role_id, granted_by, created_at)
SELECT u.id, r.id, u.id, NOW(3)
FROM sys_user u
JOIN sys_role r ON r.code = 'USER'
WHERE u.username IN ('editor', 'viewer');
