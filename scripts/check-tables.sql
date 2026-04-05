-- 检查各表数据量
USE volunteer; SELECT 'volunteer_enrollments' AS table_name, COUNT(*) AS row_count FROM volunteer_enrollments UNION ALL SELECT 'volunteer_activity_notifications', COUNT(*) FROM volunteer_activity_notifications;
USE activity; SELECT 'activity_proposals' AS table_name, COUNT(*) AS row_count FROM activity_proposals UNION ALL SELECT 'platform_activities', COUNT(*) FROM platform_activities;
USE user; SELECT 'platform_users' AS table_name, COUNT(*) AS row_count FROM platform_users;
