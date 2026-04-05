-- 查看所有管理员账号
USE user;

SELECT id, mobile, role_code, status_code, SUBSTRING(password_hash, 1, 20) AS password_hash_preview 
FROM platform_users 
WHERE role_code = 'admin';
