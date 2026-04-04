package com.volunteer.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class UserSchemaInitializer {

    private final JdbcClient jdbcClient;

    UserSchemaInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void initialize() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS platform_users (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    mobile VARCHAR(32) NOT NULL UNIQUE,
                    password_hash VARCHAR(128) NOT NULL,
                    role_code VARCHAR(32) NOT NULL,
                    community_name VARCHAR(200) NOT NULL,
                    status_code VARCHAR(32) NOT NULL,
                    bio TEXT NULL,
                    last_login_at DATETIME NULL,
                    age_years INT NULL,
                    gender_code VARCHAR(16) NULL,
                    contact_phone VARCHAR(32) NULL,
                    verification_materials TEXT NULL,
                    review_note VARCHAR(500) NULL,
                    approved_at DATETIME NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """).update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS user_sessions (
                    token VARCHAR(128) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    expires_at DATETIME NOT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_sessions_user_id (user_id),
                    INDEX idx_sessions_expires_at (expires_at)
                )
                """).update();

        // Backward-compatible migration for older local schemas.
        ensureUserColumn("bio", "TEXT NULL");
        ensureUserColumn("last_login_at", "DATETIME NULL");
        ensureUserColumn("age_years", "INT NULL");
        ensureUserColumn("gender_code", "VARCHAR(16) NULL");
        ensureUserColumn("contact_phone", "VARCHAR(32) NULL");
        ensureUserColumn("verification_materials", "TEXT NULL");
        ensureUserColumn("review_note", "VARCHAR(500) NULL");
        ensureUserColumn("approved_at", "DATETIME NULL");
        ensureUserColumn("created_at", "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureUserColumn("updated_at", "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");

        final String adminMobile = "19900000000";
        final String adminPasswordHash = hash("admin123456");

        Long adminByMobile = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM platform_users
                WHERE mobile = :mobile
                """)
                .param("mobile", adminMobile)
                .query(Long.class)
                .single();

        if (adminByMobile == null || adminByMobile == 0L) {
            Long adminId = jdbcClient.sql("""
                    SELECT COALESCE(MAX(id), 100) + 1
                    FROM platform_users
                    """).query(Long.class).single();

            jdbcClient.sql("""
                    INSERT INTO platform_users (
                        id,
                        name,
                        mobile,
                        password_hash,
                        role_code,
                        community_name,
                        status_code,
                        bio,
                        review_note,
                        approved_at,
                        created_at
                    ) VALUES (
                        :id,
                        :name,
                        :mobile,
                        :passwordHash,
                        'admin',
                        '平台运营中心',
                        'ACTIVE',
                        '默认管理员账号',
                        'system seeded',
                        NOW(),
                        NOW()
                    )
                    """)
                    .param("id", adminId)
                    .param("name", "平台管理员")
                    .param("mobile", adminMobile)
                    .param("passwordHash", adminPasswordHash)
                    .update();
            return;
        }

        jdbcClient.sql("""
                UPDATE platform_users
                SET
                    name = '平台管理员',
                    password_hash = :passwordHash,
                    role_code = 'admin',
                    status_code = 'ACTIVE',
                    community_name = '平台运营中心',
                    review_note = 'system seeded',
                    approved_at = COALESCE(approved_at, NOW()),
                    updated_at = NOW()
                WHERE mobile = :mobile
                """)
                .param("mobile", adminMobile)
                .param("passwordHash", adminPasswordHash)
                .update();
    }

    private void ensureUserColumn(String columnName, String ddl) {
        Boolean exists = jdbcClient.sql("""
                SELECT COUNT(*) > 0
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'platform_users'
                  AND column_name = :columnName
                """)
                .param("columnName", columnName)
                .query(Boolean.class)
                .single();

        if (Boolean.TRUE.equals(exists)) {
            return;
        }

        jdbcClient.sql("ALTER TABLE platform_users ADD COLUMN " + columnName + " " + ddl).update();
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
