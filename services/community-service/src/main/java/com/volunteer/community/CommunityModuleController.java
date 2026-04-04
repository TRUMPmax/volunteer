package com.volunteer.community;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/community")
class CommunityModuleController {

    private final JdbcClient jdbcClient;

    CommunityModuleController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/overview")
    CommunityOverview overview() {
        Integer noticeCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM platform_notices
                WHERE audience_role <> 'admin'
                """).query(Integer.class).single();

        List<NoticeItem> notices = latestNotices("volunteer", 3);
        return new CommunityOverview(noticeCount, notices);
    }

    @GetMapping("/notices")
    NoticeListResponse notices(
            @RequestParam(defaultValue = "volunteer") String role,
            @RequestParam(defaultValue = "4") Integer limit) {
        int safeLimit = Math.max(Math.min(limit, 10), 1);
        return new NoticeListResponse(latestNotices(role, safeLimit));
    }

    @GetMapping("/notices/all")
    NoticeListResponse allNotices(
            @RequestParam(defaultValue = "20") Integer limit) {
        int safeLimit = Math.max(Math.min(limit, 100), 1);
        List<NoticeItem> items = jdbcClient.sql("""
                SELECT
                    id,
                    title,
                    CASE audience_role
                        WHEN 'organization' THEN '组织方'
                        WHEN 'admin' THEN '平台方'
                        WHEN 'volunteer' THEN '志愿者'
                        ELSE '全体'
                    END AS audience,
                    CASE level_code
                        WHEN 'IMPORTANT' THEN '重要'
                        ELSE '通知'
                    END AS level,
                    body_text AS content,
                    DATE_FORMAT(published_at, '%m-%d %H:%i') AS publishedAt
                FROM platform_notices
                ORDER BY published_at DESC
                LIMIT :limit
                """)
                .param("limit", safeLimit)
                .query(NoticeItem.class)
                .list();
        return new NoticeListResponse(items);
    }

    @PostMapping("/notices")
    NoticeCreateResult createNotice(@RequestBody NoticeCreateRequest request) {
        String title = requiredText(request.title(), "title is required");
        String content = requiredText(request.content(), "content is required");
        String audience = normalizeAudience(request.audience());
        String level = normalizeLevel(request.level());

        long nextId = jdbcClient.sql("""
                SELECT COALESCE(MAX(id), 0) + 1 FROM platform_notices
                """).query(Long.class).single();

        jdbcClient.sql("""
                INSERT INTO platform_notices (id, title, audience_role, level_code, body_text, published_at, created_at)
                VALUES (:id, :title, :audience, :level, :content, NOW(), NOW())
                """)
                .param("id", nextId)
                .param("title", title)
                .param("audience", audience)
                .param("level", level)
                .param("content", content)
                .update();

        return new NoticeCreateResult(nextId, "PUBLISHED");
    }

    private List<NoticeItem> latestNotices(String role, int limit) {
        String normalizedRole = role == null ? "volunteer" : role.trim().toLowerCase();

        return jdbcClient.sql("""
                SELECT
                    id,
                    title,
                    CASE audience_role
                        WHEN 'organization' THEN '组织方'
                        WHEN 'admin' THEN '平台方'
                        WHEN 'volunteer' THEN '志愿者'
                        ELSE '全体'
                    END AS audience,
                    CASE level_code
                        WHEN 'IMPORTANT' THEN '重要'
                        ELSE '通知'
                    END AS level,
                    body_text AS content,
                    DATE_FORMAT(published_at, '%m-%d %H:%i') AS publishedAt
                FROM platform_notices
                WHERE audience_role = 'ALL' OR audience_role = :role
                ORDER BY published_at DESC
                LIMIT :limit
                """)
                .param("role", normalizedRole)
                .param("limit", limit)
                .query(NoticeItem.class)
                .list();
    }

    private String normalizeAudience(String audience) {
        if (audience == null || audience.isBlank()) return "ALL";
        return switch (audience.trim().toLowerCase()) {
            case "volunteer" -> "volunteer";
            case "organization" -> "organization";
            case "admin" -> "admin";
            default -> "ALL";
        };
    }

    private String normalizeLevel(String level) {
        if (level == null || level.isBlank()) return "NOTICE";
        return "IMPORTANT".equalsIgnoreCase(level.trim()) ? "IMPORTANT" : "NOTICE";
    }

    private String requiredText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }
}

record CommunityOverview(
        Integer noticeCount,
        List<NoticeItem> notices) {
}

record NoticeListResponse(List<NoticeItem> notices) {
}

record NoticeItem(
        Long id,
        String title,
        String audience,
        String level,
        String content,
        String publishedAt) {
}

record NoticeCreateRequest(
        String title,
        String content,
        String audience,
        String level) {
}

record NoticeCreateResult(
        Long noticeId,
        String status) {
}
