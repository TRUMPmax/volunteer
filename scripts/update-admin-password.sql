-- 修改管理员账号密码
USE user;

UPDATE platform_users
SET mobile = '110',
    password_hash = '9bdb2af6799204a299c603994b8e400e4b1fd625efdb74066cc869fee42c9df3'
WHERE role_code = 'admin';

SELECT '管理员账号已更新：手机号 110，密码 110' AS message;
SELECT id, mobile, role_code, status_code FROM platform_users WHERE role_code = 'admin';
