package com.volunteer.volunteer;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class VolunteerSchemaInitializer {

    private final JdbcClient jdbcClient;

    VolunteerSchemaInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void initialize() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS volunteer_enrollments (
                    id BIGINT PRIMARY KEY,
                    activity_id BIGINT NOT NULL,
                    volunteer_user_id BIGINT NOT NULL,
                    volunteer_name VARCHAR(100) NOT NULL,
                    activity_title VARCHAR(255) NOT NULL,
                    schedule_text VARCHAR(255) NOT NULL,
                    location_name VARCHAR(255) NOT NULL,
                    status_code VARCHAR(32) NOT NULL,
                    enrolled_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    service_hours DOUBLE NOT NULL DEFAULT 0,
                    points_earned INT NOT NULL DEFAULT 0,
                    INDEX idx_enroll_user (volunteer_user_id),
                    INDEX idx_enroll_activity (activity_id)
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS volunteer_activity_notifications (
                    id BIGINT PRIMARY KEY,
                    activity_id BIGINT NOT NULL,
                    volunteer_user_id BIGINT NOT NULL,
                    organizer_user_id BIGINT NOT NULL,
                    organizer_name VARCHAR(100) NOT NULL,
                    title VARCHAR(200) NOT NULL,
                    content_text TEXT NOT NULL,
                    read_flag TINYINT NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_notice_user_created (volunteer_user_id, created_at),
                    INDEX idx_notice_activity (activity_id)
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS reward_products_v2 (
                    id BIGINT PRIMARY KEY,
                    product_name VARCHAR(200) NOT NULL,
                    required_points INT NOT NULL,
                    stock_count INT NOT NULL,
                    status_code VARCHAR(32) NOT NULL
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS reward_orders_v2 (
                    id BIGINT PRIMARY KEY,
                    volunteer_user_id BIGINT NOT NULL,
                    product_name VARCHAR(200) NOT NULL,
                    quantity_count INT NOT NULL,
                    points_cost INT NOT NULL,
                    status_code VARCHAR(32) NOT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_reward_orders_user (volunteer_user_id, created_at)
                )
                """).update();

        Long rewardCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM reward_products_v2
                """).query(Long.class).single();
        if (rewardCount == null || rewardCount == 0L) {
            jdbcClient.sql("""
                    INSERT INTO reward_products_v2 (id, product_name, required_points, stock_count, status_code)
                    VALUES
                    (1, '志愿者纪念徽章', 80, 200, 'POPULAR'),
                    (2, '保温杯', 160, 120, 'AVAILABLE'),
                    (3, '急救包', 220, 60, 'AVAILABLE')
                    """).update();
        }
    }
}
