-- 恢复原始管理员账号
USE user;

UPDATE platform_users
SET mobile = '15011111111',
    password_hash = '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92'
WHERE role_code = 'admin';

SELECT '管理员账号已恢复：手机号 15011111111，密码 123456' AS message;
SELECT id, mobile, role_code, status_code FROM platform_users WHERE role_code = 'admin';
