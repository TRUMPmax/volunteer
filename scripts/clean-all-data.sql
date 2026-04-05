-- 清空志愿者平台数据（保留表结构和管理员账号）

USE volunteer_volunteer_db;

-- 删除报名记录
DELETE FROM volunteer_enrollments;

-- 删除通知
DELETE FROM volunteer_activity_notifications;

USE volunteer_activity_db;

-- 删除活动
DELETE FROM platform_activities;

-- 删除活动申请
DELETE FROM activity_proposals;

USE volunteer_user_db;

-- 删除所有用户（保留管理员）
DELETE FROM platform_users WHERE role_code != 'admin';

-- 提交
COMMIT;

SELECT '数据已清空完成，只保留表结构和管理员账号' AS message;
