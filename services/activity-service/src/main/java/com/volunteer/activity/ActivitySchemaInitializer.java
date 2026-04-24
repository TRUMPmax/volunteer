package com.volunteer.activity;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class ActivitySchemaInitializer {

    private static final List<String> DEFAULT_CATEGORIES = List.of(
            "社区服务",
            "助老帮扶",
            "环保行动",
            "文明宣传",
            "应急保障",
            "青少年关爱",
            "医疗协助",
            "文化活动");

    private final JdbcClient jdbcClient;

    ActivitySchemaInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void initialize() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS activity_proposals (
                    id BIGINT PRIMARY KEY,
                    organization_user_id BIGINT NOT NULL,
                    organization_name VARCHAR(100) NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    category_name VARCHAR(100) NOT NULL,
                    location_name VARCHAR(255) NOT NULL,
                    start_at DATETIME NOT NULL,
                    end_at DATETIME NOT NULL,
                    capacity_count INT NOT NULL,
                    contact_mobile VARCHAR(32) NOT NULL,
                    cover_image LONGTEXT NULL,
                    description_text TEXT NOT NULL,
                    status_code VARCHAR(32) NOT NULL,
                    review_note VARCHAR(500) NULL,
                    published_activity_id BIGINT NULL,
                    reviewed_by_admin_user_id BIGINT NULL,
                    reviewed_at DATETIME NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS platform_activities (
                    id BIGINT PRIMARY KEY,
                    creator_user_id BIGINT NOT NULL,
                    creator_name VARCHAR(100) NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    category_name VARCHAR(100) NOT NULL,
                    location_name VARCHAR(255) NOT NULL,
                    start_at DATETIME NOT NULL,
                    end_at DATETIME NOT NULL,
                    capacity_count INT NOT NULL,
                    enrolled_count INT NOT NULL DEFAULT 0,
                    status_code VARCHAR(32) NOT NULL,
                    contact_mobile VARCHAR(32) NOT NULL,
                    cover_image LONGTEXT NULL,
                    description_text TEXT NOT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_activities_status_start (status_code, start_at),
                    INDEX idx_activities_creator (creator_user_id)
                )
                """).update();

        // Drop and recreate activity_categories to ensure schema is always up to date
        jdbcClient.sql("DROP TABLE IF EXISTS activity_categories").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS activity_categories (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(100) NOT NULL UNIQUE,
                    source_type VARCHAR(32) NOT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """).update();

        for (String category : DEFAULT_CATEGORIES) {
            jdbcClient.sql("""
                    INSERT IGNORE INTO activity_categories (name, source_type)
                    VALUES (:name, 'SYSTEM')
                    """)
                    .param("name", category)
                    .update();
        }
    }
}
