package com.volunteer.activity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/activities")
class ActivityModuleController {

    private static final int COVER_VARIANT_COUNT = 4;

    private final JdbcClient jdbcClient;

    ActivityModuleController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/overview")
    ActivityOverview overview() {
        ActivityOverviewNumbers numbers = jdbcClient.sql("""
                SELECT
                    COUNT(*) AS openActivities,
                    COALESCE(SUM(capacity_count), 0) AS totalCapacity,
                    COALESCE(SUM(enrolled_count), 0) AS enrolledCount,
                    COUNT(DISTINCT category_name) AS categoryCount
                FROM platform_activities
                WHERE status_code = 'PUBLISHED'
                """).query(ActivityOverviewNumbers.class).single();

        List<ActivitySummaryCard> featuredActivities = jdbcClient.sql("""
                SELECT
                    id,
                    title,
                    category_name AS category,
                    location_name AS location,
                    CONCAT(DATE_FORMAT(start_at, '%m-%d %H:%i'), ' - ', DATE_FORMAT(end_at, '%H:%i')) AS schedule,
                    creator_name AS organizerName,
                    CASE
                        WHEN enrolled_count >= capacity_count THEN 'FULL'
                        ELSE 'OPEN'
                    END AS status,
                    cover_image AS coverImage
                FROM platform_activities
                WHERE status_code = 'PUBLISHED'
                ORDER BY start_at
                LIMIT 6
                """).query(ActivitySummaryCard.class).list().stream()
                .map(this::withCoverFallback)
                .toList();

        return new ActivityOverview(
                numbers.openActivities(),
                numbers.totalCapacity(),
                numbers.enrolledCount(),
                numbers.categoryCount(),
                featuredActivities);
    }

    @GetMapping("/public")
    PublicActivityPage publicActivities(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "6") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(Math.min(pageSize, 20), 1);
        int offset = (safePage - 1) * safePageSize;

        StringBuilder where = new StringBuilder(" WHERE status_code = 'PUBLISHED' ");
        Map<String, Object> params = new LinkedHashMap<>();

        if (keyword != null && !keyword.trim().isEmpty()) {
            String trimmed = keyword.trim();
            where.append("""
                     AND (
                        title LIKE :keyword
                        OR category_name LIKE :keyword
                        OR location_name LIKE :keyword
                        OR description_text LIKE :keyword
                    )
                    """);
            params.put("keyword", "%" + trimmed + "%");
        }

        if (category != null && !category.trim().isEmpty()) {
            where.append(" AND category_name = :category ");
            params.put("category", category.trim());
        }

        Long total = applyParams(jdbcClient.sql("SELECT COUNT(*) FROM platform_activities " + where), params)
                .query(Long.class)
                .single();

        List<ActivityListItem> items = applyParams(jdbcClient.sql("""
                SELECT
                    id,
                    title,
                    category_name AS category,
                    location_name AS location,
                    CONCAT(DATE_FORMAT(start_at, '%m-%d %H:%i'), ' - ', DATE_FORMAT(end_at, '%H:%i')) AS schedule,
                    capacity_count AS capacity,
                    enrolled_count AS enrolled,
                    CASE
                        WHEN enrolled_count >= capacity_count THEN 'FULL'
                        ELSE 'OPEN'
                    END AS status,
                    creator_name AS organizerName,
                    description_text AS description,
                    cover_image AS coverImage
                FROM platform_activities
                """ + where + """
                ORDER BY (
                    0.6 * GREATEST(0, 1 - TIMESTAMPDIFF(HOUR, NOW(), start_at) / 72.0)
                  + 0.4 * CASE WHEN capacity_count > 0
                               THEN (capacity_count - enrolled_count) / capacity_count
                               ELSE 0 END
                ) DESC, start_at ASC
                LIMIT :limit OFFSET :offset
                """), params)
                .param("limit", safePageSize)
                .param("offset", offset)
                .query(ActivityListItem.class)
                .list().stream()
                .map(this::withCoverFallback)
                .toList();

        List<String> categories = jdbcClient.sql("""
                SELECT DISTINCT category_name
                FROM platform_activities
                WHERE status_code = 'PUBLISHED'
                ORDER BY category_name
                """).query(String.class).list();

        return new PublicActivityPage(safePage, safePageSize, total, categories, items);
    }

    @GetMapping("/{activityId}")
    ActivityDetail activity(@PathVariable Long activityId) {
        ActivityDetail detail = jdbcClient.sql("""
                SELECT
                    id,
                    title,
                    category_name AS category,
                    location_name AS location,
                    DATE_FORMAT(start_at, '%Y-%m-%dT%H:%i') AS startAt,
                    DATE_FORMAT(end_at, '%Y-%m-%dT%H:%i') AS endAt,
                    CONCAT(DATE_FORMAT(start_at, '%m-%d %H:%i'), ' - ', DATE_FORMAT(end_at, '%H:%i')) AS schedule,
                    capacity_count AS capacity,
                    enrolled_count AS enrolled,
                    CASE
                        WHEN status_code = 'PUBLISHED' AND enrolled_count < capacity_count THEN 'OPEN'
                        WHEN status_code = 'PUBLISHED' THEN 'FULL'
                        ELSE 'CLOSED'
                    END AS status,
                    status_code AS statusCode,
                    creator_user_id AS creatorUserId,
                    creator_name AS organizerName,
                    contact_mobile AS contactMobile,
                    description_text AS description,
                    cover_image AS coverImage
                FROM platform_activities
                WHERE id = :activityId
                """).param("activityId", activityId).query(ActivityDetail.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "activity not found"));
        return withCoverFallback(detail);
    }

    @GetMapping("/organizations/{userId}/workspace")
    OrganizationWorkspace workspace(@PathVariable Long userId) {
        List<ActivityProposalItem> proposals = jdbcClient.sql("""
                SELECT
                    id,
                    title,
                    category_name AS category,
                    location_name AS location,
                    CONCAT(DATE_FORMAT(start_at, '%m-%d %H:%i'), ' - ', DATE_FORMAT(end_at, '%H:%i')) AS schedule,
                    capacity_count AS capacity,
                    CASE status_code
                        WHEN 'APPROVED' THEN 'APPROVED'
                        WHEN 'REJECTED' THEN 'REJECTED'
                        ELSE 'REVIEWING'
                    END AS status,
                    review_note AS reviewNote,
                    COALESCE(DATE_FORMAT(created_at, '%m-%d %H:%i'), '-') AS submittedAt,
                    cover_image AS coverImage
                FROM activity_proposals
                WHERE organization_user_id = :userId
                ORDER BY created_at DESC
                """).param("userId", userId).query(ActivityProposalItem.class).list().stream()
                .map(this::withCoverFallback)
                .toList();

        List<ActivityListItem> managedActivities = jdbcClient.sql("""
                SELECT
                    id,
                    title,
                    category_name AS category,
                    location_name AS location,
                    CONCAT(DATE_FORMAT(start_at, '%m-%d %H:%i'), ' - ', DATE_FORMAT(end_at, '%H:%i')) AS schedule,
                    capacity_count AS capacity,
                    enrolled_count AS enrolled,
                    CASE
                        WHEN enrolled_count >= capacity_count THEN 'FULL'
                        ELSE 'OPEN'
                    END AS status,
                    creator_name AS organizerName,
                    description_text AS description,
                    cover_image AS coverImage
                FROM platform_activities
                WHERE creator_user_id = :userId
                ORDER BY start_at DESC
                """).param("userId", userId).query(ActivityListItem.class).list().stream()
                .map(this::withCoverFallback)
                .toList();

        return new OrganizationWorkspace(proposals, managedActivities);
    }

    @GetMapping("/proposals/review")
    ActivityProposalReviewList reviewableProposals(@RequestParam(required = false) String status) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        Map<String, Object> params = new LinkedHashMap<>();

        if (status != null && !status.trim().isEmpty()) {
            where.append(" AND status_code = :status ");
            params.put("status", status.trim());
        }

        List<ActivityProposalReviewItem> items = applyParams(jdbcClient.sql("""
                SELECT
                    id,
                    organization_user_id AS organizationUserId,
                    organization_name AS organizationName,
                    title,
                    category_name AS category,
                    location_name AS location,
                    DATE_FORMAT(start_at, '%Y-%m-%d %H:%i') AS startAt,
                    DATE_FORMAT(end_at, '%Y-%m-%d %H:%i') AS endAt,
                    capacity_count AS capacity,
                    contact_mobile AS contactMobile,
                    description_text AS description,
                    status_code AS status,
                    review_note AS reviewNote,
                    cover_image AS coverImage,
                    COALESCE(DATE_FORMAT(created_at, '%Y-%m-%d %H:%i'), '-') AS submittedAt,
                    COALESCE(DATE_FORMAT(reviewed_at, '%Y-%m-%d %H:%i'), '-') AS reviewedAt
                FROM activity_proposals
                """ + where + """
                ORDER BY created_at DESC
                """), params).query(ActivityProposalReviewItem.class).list().stream()
                .map(this::withCoverFallback)
                .toList();
        return new ActivityProposalReviewList(items);
    }

    @PostMapping("/proposals")
    ProposalSubmission submitProposal(@RequestBody ProposalRequest request) {
        if (request.organizationUserId() == null || request.organizationUserId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organization user id is required");
        }

        LocalDateTime startAt = parseDateTime(request.startAt(), "invalid start time");
        LocalDateTime endAt = parseDateTime(request.endAt(), "invalid end time");
        if (!endAt.isAfter(startAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "end time must be after start time");
        }

        long proposalId = jdbcClient.sql("""
                SELECT COALESCE(MAX(id), 0) + 1
                FROM activity_proposals
                """).query(Long.class).single();

        jdbcClient.sql("""
                INSERT INTO activity_proposals (
                    id,
                    organization_user_id,
                    organization_name,
                    title,
                    category_name,
                    location_name,
                    start_at,
                    end_at,
                    capacity_count,
                    contact_mobile,
                    cover_image,
                    description_text,
                    status_code,
                    review_note,
                    created_at,
                    updated_at
                ) VALUES (
                    :id,
                    :organizationUserId,
                    :organizationName,
                    :title,
                    :category,
                    :location,
                    :startAt,
                    :endAt,
                    :capacity,
                    :contactMobile,
                    :coverImage,
                    :description,
                    'REVIEWING',
                    '等待平台管理员审核',
                    NOW(),
                    NOW()
                )
                """)
                .param("id", proposalId)
                .param("organizationUserId", request.organizationUserId())
                .param("organizationName", requiredText(request.organizationName(), "organization name is required"))
                .param("title", requiredText(request.title(), "title is required"))
                .param("category", requiredText(request.category(), "category is required"))
                .param("location", requiredText(request.location(), "location is required"))
                .param("startAt", startAt)
                .param("endAt", endAt)
                .param("capacity", positiveNumber(request.capacity(), "capacity must be positive"))
                .param("contactMobile", requiredText(request.contactMobile(), "contact mobile is required"))
                .param("coverImage", sanitizeCoverImage(request.coverImage()))
                .param("description", requiredText(request.description(), "description is required"))
                .update();

        return new ProposalSubmission(proposalId, "REVIEWING");
    }

    @PostMapping("/proposals/{proposalId}/review")
    ProposalReviewResult reviewProposal(@PathVariable Long proposalId, @RequestBody ProposalReviewRequest request) {
        ActivityProposalForReview proposal = jdbcClient.sql("""
                SELECT
                    id,
                    organization_user_id AS organizationUserId,
                    organization_name AS organizationName,
                    title,
                    category_name AS category,
                    location_name AS location,
                    DATE_FORMAT(start_at, '%Y-%m-%dT%H:%i') AS startAt,
                    DATE_FORMAT(end_at, '%Y-%m-%dT%H:%i') AS endAt,
                    capacity_count AS capacity,
                    contact_mobile AS contactMobile,
                    description_text AS description,
                    cover_image AS coverImage,
                    status_code AS status,
                    published_activity_id AS publishedActivityId
                FROM activity_proposals
                WHERE id = :proposalId
                """).param("proposalId", proposalId).query(ActivityProposalForReview.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "proposal not found"));

        if (!"REVIEWING".equals(proposal.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "proposal has already been reviewed");
        }

        boolean approved = Boolean.TRUE.equals(request.approved());
        String nextStatus = approved ? "APPROVED" : "REJECTED";
        String reviewNote = request.reviewNote() == null || request.reviewNote().trim().isEmpty()
                ? (approved ? "活动申请审核通过" : "活动申请审核未通过")
                : request.reviewNote().trim();

        Long publishedActivityId = null;
        if (approved) {
            publishedActivityId = publishActivityFromProposal(proposal);
        }

        jdbcClient.sql("""
                UPDATE activity_proposals
                SET
                    status_code = :status,
                    review_note = :reviewNote,
                    reviewed_by_admin_user_id = :adminUserId,
                    reviewed_at = NOW(),
                    published_activity_id = :publishedActivityId,
                    updated_at = NOW()
                WHERE id = :proposalId
                """)
                .param("status", nextStatus)
                .param("reviewNote", reviewNote)
                .param("adminUserId", request.adminUserId())
                .param("publishedActivityId", publishedActivityId)
                .param("proposalId", proposalId)
                .update();

        return new ProposalReviewResult(proposalId, nextStatus, reviewNote, publishedActivityId);
    }

    @PutMapping("/{activityId}")
    ActivityDetail updateActivity(@PathVariable Long activityId, @RequestBody ActivityUpdateRequest request) {
        if (request.organizationUserId() == null || request.organizationUserId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organization user id is required");
        }

        ActivityOwnership ownership = jdbcClient.sql("""
                SELECT
                    creator_user_id AS creatorUserId,
                    enrolled_count AS enrolledCount
                FROM platform_activities
                WHERE id = :activityId
                """).param("activityId", activityId)
                .query(ActivityOwnership.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "activity not found"));

        if (!Objects.equals(ownership.creatorUserId(), request.organizationUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only owner organization can update this activity");
        }

        LocalDateTime startAt = parseDateTime(request.startAt(), "invalid start time");
        LocalDateTime endAt = parseDateTime(request.endAt(), "invalid end time");
        if (!endAt.isAfter(startAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "end time must be after start time");
        }

        Integer capacity = positiveNumber(request.capacity(), "capacity must be positive");
        int currentEnrolled = ownership.enrolledCount() == null ? 0 : ownership.enrolledCount();
        if (capacity < currentEnrolled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "capacity cannot be less than enrolled count");
        }

        jdbcClient.sql("""
                UPDATE platform_activities
                SET
                    title = :title,
                    category_name = :category,
                    location_name = :location,
                    start_at = :startAt,
                    end_at = :endAt,
                    capacity_count = :capacity,
                    contact_mobile = :contactMobile,
                    cover_image = :coverImage,
                    description_text = :description,
                    updated_at = NOW()
                WHERE id = :activityId
                """)
                .param("activityId", activityId)
                .param("title", requiredText(request.title(), "title is required"))
                .param("category", requiredText(request.category(), "category is required"))
                .param("location", requiredText(request.location(), "location is required"))
                .param("startAt", startAt)
                .param("endAt", endAt)
                .param("capacity", capacity)
                .param("contactMobile", requiredText(request.contactMobile(), "contact mobile is required"))
                .param("coverImage", sanitizeCoverImage(request.coverImage()))
                .param("description", requiredText(request.description(), "description is required"))
                .update();

        return activity(activityId);
    }

    @DeleteMapping("/{activityId}")
    ActivityDeleteResult deleteActivity(@PathVariable Long activityId) {
        Long existing = jdbcClient.sql("""
                SELECT COUNT(*) FROM platform_activities WHERE id = :id
                """).param("id", activityId).query(Long.class).single();
        if (existing == null || existing == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "activity not found");
        }
        jdbcClient.sql("""
                UPDATE platform_activities SET status_code = 'CLOSED', updated_at = NOW()
                WHERE id = :id
                """).param("id", activityId).update();
        return new ActivityDeleteResult(activityId, "CLOSED", "活动已被管理员关闭");
    }

    @PostMapping("/{activityId}/occupancy/increase")
    ActivityOccupancy increaseOccupancy(@PathVariable Long activityId) {
        int updated = jdbcClient.sql("""
                UPDATE platform_activities
                SET enrolled_count = enrolled_count + 1
                WHERE id = :activityId
                  AND status_code = 'PUBLISHED'
                  AND enrolled_count < capacity_count
                """).param("activityId", activityId).update();

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "activity is full or unavailable");
        }

        return jdbcClient.sql("""
                SELECT
                    id AS activityId,
                    enrolled_count AS enrolled,
                    capacity_count AS capacity
                FROM platform_activities
                WHERE id = :activityId
                """).param("activityId", activityId).query(ActivityOccupancy.class).single();
    }

    private Long publishActivityFromProposal(ActivityProposalForReview proposal) {
        Long targetId = proposal.publishedActivityId();
        if (targetId == null || targetId <= 0) {
            targetId = jdbcClient.sql("""
                    SELECT COALESCE(MAX(id), 0) + 1
                    FROM platform_activities
                    """).query(Long.class).single();
        }

        Long existing = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM platform_activities
                WHERE id = :id
                """).param("id", targetId).query(Long.class).single();

        LocalDateTime startAt = parseDateTime(proposal.startAt(), "invalid start time");
        LocalDateTime endAt = parseDateTime(proposal.endAt(), "invalid end time");

        if (existing != null && existing > 0) {
            jdbcClient.sql("""
                    UPDATE platform_activities
                    SET
                        creator_user_id = :creatorUserId,
                        creator_name = :creatorName,
                        title = :title,
                        category_name = :category,
                        location_name = :location,
                        start_at = :startAt,
                        end_at = :endAt,
                        capacity_count = :capacity,
                        contact_mobile = :contactMobile,
                        cover_image = :coverImage,
                        description_text = :description,
                        status_code = 'PUBLISHED',
                        updated_at = NOW()
                    WHERE id = :id
                    """)
                    .param("id", targetId)
                    .param("creatorUserId", proposal.organizationUserId())
                    .param("creatorName", proposal.organizationName())
                    .param("title", proposal.title())
                    .param("category", proposal.category())
                    .param("location", proposal.location())
                    .param("startAt", startAt)
                    .param("endAt", endAt)
                    .param("capacity", proposal.capacity())
                    .param("contactMobile", proposal.contactMobile())
                    .param("coverImage", sanitizeCoverImage(proposal.coverImage()))
                    .param("description", proposal.description())
                    .update();
            return targetId;
        }

        jdbcClient.sql("""
                INSERT INTO platform_activities (
                    id,
                    creator_user_id,
                    creator_name,
                    title,
                    category_name,
                    location_name,
                    start_at,
                    end_at,
                    capacity_count,
                    enrolled_count,
                    status_code,
                    contact_mobile,
                    cover_image,
                    description_text,
                    created_at,
                    updated_at
                ) VALUES (
                    :id,
                    :creatorUserId,
                    :creatorName,
                    :title,
                    :category,
                    :location,
                    :startAt,
                    :endAt,
                    :capacity,
                    0,
                    'PUBLISHED',
                    :contactMobile,
                    :coverImage,
                    :description,
                    NOW(),
                    NOW()
                )
                """)
                .param("id", targetId)
                .param("creatorUserId", proposal.organizationUserId())
                .param("creatorName", proposal.organizationName())
                .param("title", proposal.title())
                .param("category", proposal.category())
                .param("location", proposal.location())
                .param("startAt", startAt)
                .param("endAt", endAt)
                .param("capacity", proposal.capacity())
                .param("contactMobile", proposal.contactMobile())
                .param("coverImage", sanitizeCoverImage(proposal.coverImage()))
                .param("description", proposal.description())
                .update();
        return targetId;
    }

    private JdbcClient.StatementSpec applyParams(JdbcClient.StatementSpec statementSpec, Map<String, Object> params) {
        JdbcClient.StatementSpec spec = statementSpec;
        for (Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec;
    }

    private String requiredText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private Integer positiveNumber(Integer value, String message) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value;
    }

    private LocalDateTime parseDateTime(String value, String message) {
        String trimmed = requiredText(value, message);
        // Support both "2026-04-05T10:00" (no seconds) and "2026-04-05T10:00:00"
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .appendPattern("yyyy-MM-dd'T'HH:mm")
                .optionalStart()
                .appendPattern(":ss")
                .optionalEnd()
                .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
                .toFormatter();
        try {
            return LocalDateTime.parse(trimmed, formatter);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message + ": " + trimmed);
        }
    }

    private String sanitizeCoverImage(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String coverImage = value.trim();
        if (coverImage.length() > 4_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cover image is too large");
        }
        if (coverImage.startsWith("data:image/")
                || coverImage.startsWith("http://")
                || coverImage.startsWith("https://")
                || coverImage.startsWith("/")) {
            return coverImage;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cover image format");
    }

    private String resolveCoverImage(Long activityId, String coverImage) {
        if (coverImage != null && !coverImage.isBlank()) {
            return coverImage;
        }
        long safeId = activityId == null ? 1L : Math.max(activityId, 1L);
        long variant = ((safeId - 1L) % COVER_VARIANT_COUNT) + 1L;
        return "/assets/covers/cover-" + variant + ".svg";
    }

    private ActivitySummaryCard withCoverFallback(ActivitySummaryCard item) {
        return new ActivitySummaryCard(
                item.id(),
                item.title(),
                item.category(),
                item.location(),
                item.schedule(),
                item.organizerName(),
                item.status(),
                resolveCoverImage(item.id(), item.coverImage()));
    }

    private ActivityListItem withCoverFallback(ActivityListItem item) {
        return new ActivityListItem(
                item.id(),
                item.title(),
                item.category(),
                item.location(),
                item.schedule(),
                item.capacity(),
                item.enrolled(),
                item.status(),
                item.organizerName(),
                item.description(),
                resolveCoverImage(item.id(), item.coverImage()));
    }

    private ActivityProposalItem withCoverFallback(ActivityProposalItem item) {
        return new ActivityProposalItem(
                item.id(),
                item.title(),
                item.category(),
                item.location(),
                item.schedule(),
                item.capacity(),
                item.status(),
                item.reviewNote(),
                item.submittedAt(),
                resolveCoverImage(item.id(), item.coverImage()));
    }

    private ActivityProposalReviewItem withCoverFallback(ActivityProposalReviewItem item) {
        return new ActivityProposalReviewItem(
                item.id(),
                item.organizationUserId(),
                item.organizationName(),
                item.title(),
                item.category(),
                item.location(),
                item.startAt(),
                item.endAt(),
                item.capacity(),
                item.contactMobile(),
                item.description(),
                item.status(),
                item.reviewNote(),
                resolveCoverImage(item.id(), item.coverImage()),
                item.submittedAt(),
                item.reviewedAt());
    }

    private ActivityDetail withCoverFallback(ActivityDetail item) {
        return new ActivityDetail(
                item.id(),
                item.title(),
                item.category(),
                item.location(),
                item.startAt(),
                item.endAt(),
                item.schedule(),
                item.capacity(),
                item.enrolled(),
                item.status(),
                item.statusCode(),
                item.creatorUserId(),
                item.organizerName(),
                item.contactMobile(),
                item.description(),
                resolveCoverImage(item.id(), item.coverImage()));
    }
}

record ActivityOverview(
        Integer openActivities,
        Integer totalCapacity,
        Integer enrolledCount,
        Integer categoryCount,
        List<ActivitySummaryCard> featuredActivities) {
}

record ActivityOverviewNumbers(
        Integer openActivities,
        Integer totalCapacity,
        Integer enrolledCount,
        Integer categoryCount) {
}

record ActivitySummaryCard(
        Long id,
        String title,
        String category,
        String location,
        String schedule,
        String organizerName,
        String status,
        String coverImage) {
}

record PublicActivityPage(
        Integer page,
        Integer pageSize,
        Long total,
        List<String> categories,
        List<ActivityListItem> items) {
}

record ActivityListItem(
        Long id,
        String title,
        String category,
        String location,
        String schedule,
        Integer capacity,
        Integer enrolled,
        String status,
        String organizerName,
        String description,
        String coverImage) {
}

record ActivityDetail(
        Long id,
        String title,
        String category,
        String location,
        String startAt,
        String endAt,
        String schedule,
        Integer capacity,
        Integer enrolled,
        String status,
        String statusCode,
        Long creatorUserId,
        String organizerName,
        String contactMobile,
        String description,
        String coverImage) {
}

record OrganizationWorkspace(
        List<ActivityProposalItem> proposals,
        List<ActivityListItem> activities) {
}

record ActivityProposalItem(
        Long id,
        String title,
        String category,
        String location,
        String schedule,
        Integer capacity,
        String status,
        String reviewNote,
        String submittedAt,
        String coverImage) {
}

record ActivityProposalReviewItem(
        Long id,
        Long organizationUserId,
        String organizationName,
        String title,
        String category,
        String location,
        String startAt,
        String endAt,
        Integer capacity,
        String contactMobile,
        String description,
        String status,
        String reviewNote,
        String coverImage,
        String submittedAt,
        String reviewedAt) {
}

record ActivityProposalReviewList(
        List<ActivityProposalReviewItem> items) {
}

record ActivityProposalForReview(
        Long id,
        Long organizationUserId,
        String organizationName,
        String title,
        String category,
        String location,
        String startAt,
        String endAt,
        Integer capacity,
        String contactMobile,
        String description,
        String coverImage,
        String status,
        Long publishedActivityId) {
}

record ProposalRequest(
        Long organizationUserId,
        String organizationName,
        String title,
        String category,
        String location,
        String startAt,
        String endAt,
        Integer capacity,
        String contactMobile,
        String description,
        String coverImage) {
}

record ProposalSubmission(
        Long proposalId,
        String status) {
}

record ProposalReviewRequest(
        Long adminUserId,
        Boolean approved,
        String reviewNote) {
}

record ProposalReviewResult(
        Long proposalId,
        String status,
        String reviewNote,
        Long publishedActivityId) {
}

record ActivityUpdateRequest(
        Long organizationUserId,
        String title,
        String category,
        String location,
        String startAt,
        String endAt,
        Integer capacity,
        String contactMobile,
        String description,
        String coverImage) {
}

record ActivityOwnership(
        Long creatorUserId,
        Integer enrolledCount) {
}

record ActivityOccupancy(
        Long activityId,
        Integer enrolled,
        Integer capacity) {
}

record ActivityDeleteResult(
        Long activityId,
        String status,
        String message) {
}
