package com.volunteer.community;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
