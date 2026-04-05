USE user;
SELECT id, mobile, role_code, status_code, password_hash FROM platform_users WHERE role_code='admin';
