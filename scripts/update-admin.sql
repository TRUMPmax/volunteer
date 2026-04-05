-- 修改管理员账号
USE user;

UPDATE platform_users
SET mobile = '110',
    password_hash = SHA2('110', 256)
WHERE role_code = 'admin';

SELECT '管理员账号已更新：手机号 110，密码 110' AS message;
