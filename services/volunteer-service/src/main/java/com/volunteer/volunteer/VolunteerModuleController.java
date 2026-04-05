package com.volunteer.volunteer;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/volunteers")
class VolunteerModuleController {

    private final JdbcClient jdbcClient;

    VolunteerModuleController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/overview")
    VolunteerOverview overview() {
        return jdbcClient.sql("""
                SELECT
                    COUNT(*) AS totalEnrollments,
                    COALESCE(SUM(CASE WHEN status_code = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completedServices,
                    COALESCE(SUM(service_hours), 0) AS totalServiceHours,
                    COALESCE(SUM(points_earned), 0) AS totalPoints
                FROM volunteer_enrollments
                """).query(VolunteerOverview.class).single();
    }

    @PostMapping("/enrollments")
    EnrollmentSubmission createEnrollment(@RequestBody EnrollmentRequest request) {
        Long volunteerUserId = requiredId(request.volunteerUserId(), "volunteer user id is required");
        Long activityId = requiredId(request.activityId(), "activity id is required");

        boolean exists = jdbcClient.sql("""
                SELECT COUNT(*) > 0
                FROM volunteer_enrollments
                WHERE volunteer_user_id = :userId AND activity_id = :activityId
                """)
                .param("userId", volunteerUserId)
                .param("activityId", activityId)
                .query(Boolean.class)
                .single();

        if (exists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already enrolled");
        }

        long enrollmentId = jdbcClient.sql("""
                SELECT COALESCE(MAX(id), 0) + 1
                FROM volunteer_enrollments
                """).query(Long.class).single();

        jdbcClient.sql("""
                INSERT INTO volunteer_enrollments (
                    id,
                    activity_id,
                    volunteer_user_id,
                    volunteer_name,
                    activity_title,
                    schedule_text,
                    location_name,
                    status_code,
                    enrolled_at,
                    service_hours,
                    points_earned
                ) VALUES (
                    :id,
                    :activityId,
                    :userId,
                    :volunteerName,
                    :activityTitle,
                    :schedule,
                    :location,
                    'ENROLLED',
                    NOW(),
                    0,
                    0
                )
                """)
                .param("id", enrollmentId)
                .param("activityId", activityId)
                .param("userId", volunteerUserId)
                .param("volunteerName", requiredText(request.volunteerName(), "volunteer name is required"))
                .param("activityTitle", requiredText(request.activityTitle(), "activity title is required"))
                .param("schedule", requiredText(request.schedule(), "schedule is required"))
                .param("location", requiredText(request.location(), "location is required"))
                .update();

        return new EnrollmentSubmission(enrollmentId, "ENROLLED");
    }

    @GetMapping("/activities/{activityId}/participants")
    List<ActivityParticipant> participants(@PathVariable Long activityId) {
        return jdbcClient.sql("""
                SELECT
                    volunteer_user_id AS volunteerUserId,
                    volunteer_name AS volunteerName,
                    COALESCE(DATE_FORMAT(enrolled_at, '%Y-%m-%d %H:%i'), '-') AS enrolledAt
                FROM volunteer_enrollments
                WHERE activity_id = :activityId
                ORDER BY enrolled_at DESC
                """).param("activityId", activityId).query(ActivityParticipant.class).list();
    }

    @PostMapping("/activities/{activityId}/notifications")
    ActivityNotificationDispatch dispatchNotification(
            @PathVariable Long activityId,
            @RequestBody ActivityNotificationRequest request) {
        Long organizerUserId = requiredId(request.organizerUserId(), "organizer user id is required");
        String organizerName = requiredText(request.organizerName(), "organizer name is required");
        String title = requiredText(request.title(), "notification title is required");
        String content = requiredText(request.content(), "notification content is required");

        List<Long> volunteerIds = jdbcClient.sql("""
                SELECT DISTINCT volunteer_user_id
                FROM volunteer_enrollments
                WHERE activity_id = :activityId
                """).param("activityId", activityId).query(Long.class).list();

        if (volunteerIds.isEmpty()) {
            return new ActivityNotificationDispatch(activityId, 0, "no participant to notify");
        }

        Long nextId = jdbcClient.sql("""
                SELECT COALESCE(MAX(id), 0) + 1
                FROM volunteer_activity_notifications
                """).query(Long.class).single();

        long currentId = nextId == null ? 1L : nextId;
        for (Long volunteerId : volunteerIds) {
            jdbcClient.sql("""
                    INSERT INTO volunteer_activity_notifications (
                        id,
                        activity_id,
                        volunteer_user_id,
                        organizer_user_id,
                        organizer_name,
                        title,
                        content_text,
                        read_flag,
                        created_at
                    ) VALUES (
                        :id,
                        :activityId,
                        :volunteerUserId,
                        :organizerUserId,
                        :organizerName,
                        :title,
                        :content,
                        0,
                        NOW()
                    )
                    """)
                    .param("id", currentId)
                    .param("activityId", activityId)
                    .param("volunteerUserId", volunteerId)
                    .param("organizerUserId", organizerUserId)
                    .param("organizerName", organizerName)
                    .param("title", title)
                    .param("content", content)
                    .update();
            currentId++;
        }

        return new ActivityNotificationDispatch(activityId, volunteerIds.size(), "notifications sent");
    }

    @GetMapping("/users/{userId}/notifications")
    ActivityNotificationList notifications(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "20") Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<ActivityNotificationItem> items = jdbcClient.sql("""
                SELECT
                    id,
                    activity_id AS activityId,
                    organizer_user_id AS organizerUserId,
                    organizer_name AS organizerName,
                    title,
                    content_text AS content,
                    read_flag AS read,
                    COALESCE(DATE_FORMAT(created_at, '%Y-%m-%d %H:%i'), '-') AS createdAt
                FROM volunteer_activity_notifications
                WHERE volunteer_user_id = :userId
                ORDER BY created_at DESC
                LIMIT :limit
                """)
                .param("userId", userId)
                .param("limit", safeLimit)
                .query(ActivityNotificationItem.class)
                .list();
        return new ActivityNotificationList(items);
    }

    @GetMapping("/users/{userId}/activity-ids")
    UserActivityIds activityIds(@PathVariable Long userId) {
        List<Long> ids = jdbcClient.sql("""
                SELECT activity_id
                FROM volunteer_enrollments
                WHERE volunteer_user_id = :userId
                ORDER BY enrolled_at DESC
                """).param("userId", userId).query(Long.class).list();

        return new UserActivityIds(ids);
    }

    @GetMapping("/users/{userId}/dashboard")
    VolunteerDashboard dashboard(@PathVariable Long userId) {
        VolunteerUserSummary summary = jdbcClient.sql("""
                SELECT
                    COUNT(*) AS totalEnrollments,
                    COALESCE(SUM(CASE WHEN status_code = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completedServices,
                    COALESCE(SUM(points_earned), 0) AS totalPoints,
                    COALESCE(SUM(service_hours), 0) AS totalServiceHours
                FROM volunteer_enrollments
                WHERE volunteer_user_id = :userId
                """).param("userId", userId).query(VolunteerUserSummary.class).single();

        List<UserEnrollmentItem> enrollments = jdbcClient.sql("""
                SELECT
                    id,
                    activity_id AS activityId,
                    activity_title AS activityTitle,
                    schedule_text AS schedule,
                    location_name AS location,
                    CASE status_code
                        WHEN 'COMPLETED' THEN 'COMPLETED'
                        WHEN 'ENROLLED' THEN 'ENROLLED'
                        ELSE 'PROCESSING'
                    END AS status,
                    DATE_FORMAT(enrolled_at, '%m-%d %H:%i') AS enrolledAt,
                    service_hours AS serviceHours,
                    points_earned AS pointsEarned
                FROM volunteer_enrollments
                WHERE volunteer_user_id = :userId
                ORDER BY enrolled_at DESC
                """).param("userId", userId).query(UserEnrollmentItem.class).list();

        List<RewardProductItem> rewards = jdbcClient.sql("""
                SELECT
                    id,
                    product_name AS name,
                    required_points AS requiredPoints,
                    stock_count AS stock,
                    CASE status_code
                        WHEN 'POPULAR' THEN 'POPULAR'
                        ELSE 'AVAILABLE'
                    END AS status
                FROM reward_products_v2
                ORDER BY required_points
                """).query(RewardProductItem.class).list();

        List<RewardOrderItem> orders = jdbcClient.sql("""
                SELECT
                    id,
                    product_name AS productName,
                    quantity_count AS quantity,
                    points_cost AS pointsCost,
                    CASE status_code
                        WHEN 'SHIPPED' THEN 'SHIPPED'
                        ELSE 'PROCESSING'
                    END AS status,
                    DATE_FORMAT(created_at, '%m-%d %H:%i') AS createdAt
                FROM reward_orders_v2
                WHERE volunteer_user_id = :userId
                ORDER BY created_at DESC
                """).param("userId", userId).query(RewardOrderItem.class).list();

        return new VolunteerDashboard(summary, enrollments, rewards, orders);
    }

    private Long requiredId(Long value, String message) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value;
    }

    private String requiredText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }
}

record VolunteerOverview(
        Integer totalEnrollments,
        Integer completedServices,
        Double totalServiceHours,
        Integer totalPoints) {
}

record EnrollmentRequest(
        Long volunteerUserId,
        String volunteerName,
        Long activityId,
        String activityTitle,
        String schedule,
        String location) {
}

record EnrollmentSubmission(
        Long enrollmentId,
        String status) {
}

record UserActivityIds(List<Long> activityIds) {
}

record ActivityParticipant(
        Long volunteerUserId,
        String volunteerName,
        String enrolledAt) {
}

record ActivityNotificationRequest(
        Long organizerUserId,
        String organizerName,
        String title,
        String content) {
}

record ActivityNotificationDispatch(
        Long activityId,
        Integer deliveredCount,
        String message) {
}

record ActivityNotificationItem(
        Long id,
        Long activityId,
        Long organizerUserId,
        String organizerName,
        String title,
        String content,
        Integer read,
        String createdAt) {
}

record ActivityNotificationList(
        List<ActivityNotificationItem> items) {
}

record VolunteerDashboard(
        VolunteerUserSummary summary,
        List<UserEnrollmentItem> enrollments,
        List<RewardProductItem> rewards,
        List<RewardOrderItem> orders) {
}

record VolunteerUserSummary(
        Integer totalEnrollments,
        Integer completedServices,
        Integer totalPoints,
        Double totalServiceHours) {
}

record UserEnrollmentItem(
        Long id,
        Long activityId,
        String activityTitle,
        String schedule,
        String location,
        String status,
        String enrolledAt,
        Double serviceHours,
        Integer pointsEarned) {
}

record RewardProductItem(
        Long id,
        String name,
        Integer requiredPoints,
        Integer stock,
        String status) {
}

record RewardOrderItem(
        Long id,
        String productName,
        Integer quantity,
        Integer pointsCost,
        String status,
        String createdAt) {
}
