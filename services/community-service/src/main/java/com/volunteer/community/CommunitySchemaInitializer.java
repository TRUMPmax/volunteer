package com.volunteer.community;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class CommunitySchemaInitializer {

    private final JdbcClient jdbcClient;

    CommunitySchemaInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void initialize() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS platform_notices (
                    id BIGINT PRIMARY KEY,
                    title VARCHAR(200) NOT NULL,
                    audience_role VARCHAR(32) NOT NULL,
                    level_code VARCHAR(32) NOT NULL,
                    body_text TEXT NOT NULL,
                    published_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """).update();

        // Ensure created_at column exists for backward compatibility
        ensureColumn("created_at", "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");

        Long count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM platform_notices
                """).query(Long.class).single();

        if (count == null || count == 0L) {
            jdbcClient.sql("""
                    INSERT INTO platform_notices (id, title, audience_role, level_code, body_text, published_at, created_at)
                    VALUES
                    (1, '平台服务已上线', 'ALL', 'NOTICE', '欢迎使用社区志愿服务平台。', NOW(), NOW()),
                    (2, '组织者审核规则', 'organization', 'IMPORTANT', '组织者账号需通过管理员审核后才可发布活动。', NOW(), NOW()),
                    (3, '报名提醒', 'volunteer', 'NOTICE', '请完善个人资料后再报名活动。', NOW(), NOW())
                    """).update();
        }
    }

    private void ensureColumn(String columnName, String ddl) {
        Boolean exists = jdbcClient.sql("""
                SELECT COUNT(*) > 0
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'platform_notices'
                  AND column_name = :columnName
                """)
                .param("columnName", columnName)
                .query(Boolean.class)
                .single();

        if (Boolean.TRUE.equals(exists)) {
            return;
        }

        jdbcClient.sql("ALTER TABLE platform_notices ADD COLUMN " + columnName + " " + ddl).update();
    }
}
