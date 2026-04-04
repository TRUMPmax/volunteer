package com.volunteer.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;

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
@RequestMapping("/internal/users")
class UserModuleController {

    private static final int MIN_AGE = 1;
    private static final int MAX_AGE = 120;

    private final JdbcClient jdbcClient;

    UserModuleController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/overview")
    UserOverview overview() {
        return jdbcClient.sql("""
                SELECT
                    COUNT(*) AS totalUsers,
                    SUM(CASE WHEN role_code = 'volunteer' AND status_code = 'ACTIVE' THEN 1 ELSE 0 END) AS volunteerUsers,
                    SUM(CASE WHEN role_code = 'organization' AND status_code = 'ACTIVE' THEN 1 ELSE 0 END) AS organizationUsers
                FROM platform_users
                WHERE role_code <> 'admin'
                """).query(UserOverview.class).single();
    }

    @PostMapping("/register")
    AuthResponse register(@RequestBody RegisterRequest request) {
        String role = normalizeRole(request.role(), false);
        String name = requiredText(request.name(), "name is required");
        String mobile = requiredText(request.mobile(), "mobile is required");
        String password = requiredText(request.password(), "password is required");
        String community = requiredText(request.community(), "community is required");
        String bio = trimToEmpty(request.bio());
        Integer age = normalizeAge(request.age(), false);
        String gender = normalizeGender(request.gender(), false);
        String contactPhone = trimToEmpty(request.contactPhone());
        String verificationMaterials = trimToEmpty(request.verificationMaterials());

        boolean exists = jdbcClient.sql("""
                SELECT COUNT(*) > 0
                FROM platform_users
                WHERE mobile = :mobile
                """).param("mobile", mobile).query(Boolean.class).single();
        if (exists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "mobile is already registered");
        }

        String status = "ACTIVE";
        if ("organization".equals(role)) {
            if (verificationMaterials.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organization verification materials are required");
            }
            status = "PENDING_REVIEW";
        }

        long userId = jdbcClient.sql("""
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
                    age_years,
                    gender_code,
                    contact_phone,
                    verification_materials,
                    review_note,
                    approved_at,
                    last_login_at,
                    created_at,
                    updated_at
                ) VALUES (
                    :id,
                    :name,
                    :mobile,
                    :passwordHash,
                    :role,
                    :community,
                    :status,
                    :bio,
                    :age,
                    :gender,
                    :contactPhone,
                    :verificationMaterials,
                    :reviewNote,
                    :approvedAt,
                    NULL,
                    NOW(),
                    NOW()
                )
                """)
                .param("id", userId)
                .param("name", name)
                .param("mobile", mobile)
                .param("passwordHash", hash(password))
                .param("role", role)
                .param("community", community)
                .param("status", status)
                .param("bio", bio)
                .param("age", age)
                .param("gender", gender)
                .param("contactPhone", contactPhone)
                .param("verificationMaterials", verificationMaterials.isBlank() ? null : verificationMaterials)
                .param("reviewNote", "organization".equals(role) ? "等待管理员审核组织者资质" : null)
                .param("approvedAt", "organization".equals(role) ? null : Timestamp.valueOf(LocalDateTime.now()))
                .update();

        PlatformUserEntity user = loadUserById(userId);
        if (!"ACTIVE".equals(user.status())) {
            return new AuthResponse("", toSessionUser(user));
        }
        return createSession(user);
    }

    @PostMapping("/login")
    AuthResponse login(@RequestBody LoginRequest request) {
        String role = normalizeRole(request.role(), true);
        String mobile = requiredText(request.mobile(), "mobile is required");
        String password = requiredText(request.password(), "password is required");

        PlatformUserEntity user = jdbcClient.sql("""
                SELECT
                    id,
                    name,
                    mobile,
                    password_hash AS passwordHash,
                    role_code AS role,
                    community_name AS community,
                    status_code AS status,
                    bio,
                    age_years AS age,
                    gender_code AS gender,
                    contact_phone AS contactPhone,
                    verification_materials AS verificationMaterials,
                    review_note AS reviewNote
                FROM platform_users
                WHERE mobile = :mobile
                """).param("mobile", mobile).query(PlatformUserEntity.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials"));

        if (!user.role().equals(role)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "role does not match");
        }

        if (!user.passwordHash().equals(hash(password))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }

        if (!"ACTIVE".equals(user.status())) {
            String message = switch (user.status()) {
                case "PENDING_REVIEW" -> "account is pending admin review";
                case "REJECTED" -> "account review was rejected";
                default -> "account is unavailable";
            };
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }

        return createSession(user);
    }

    @GetMapping("/session/{token}")
    SessionUser session(@PathVariable String token) {
        return jdbcClient.sql("""
                SELECT
                    u.id,
                    u.name,
                    u.role_code AS role,
                    u.community_name AS community,
                    u.status_code AS status,
                    CASE
                        WHEN u.role_code <> 'volunteer' THEN TRUE
                        WHEN u.age_years IS NOT NULL
                             AND u.gender_code IS NOT NULL
                             AND TRIM(u.gender_code) <> ''
                             AND u.contact_phone IS NOT NULL
                             AND TRIM(u.contact_phone) <> ''
                            THEN TRUE
                        ELSE FALSE
                    END AS profileCompleted
                FROM user_sessions s
                JOIN platform_users u ON u.id = s.user_id
                WHERE s.token = :token
                  AND s.expires_at > NOW()
                  AND u.status_code = 'ACTIVE'
                """).param("token", token).query(SessionUser.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session is invalid"));
    }

    @GetMapping("/{userId}")
    UserProfile profile(@PathVariable Long userId) {
        return jdbcClient.sql("""
                SELECT
                    id,
                    name,
                    mobile,
                    role_code AS role,
                    community_name AS community,
                    status_code AS status,
                    bio,
                    age_years AS age,
                    gender_code AS gender,
                    contact_phone AS contactPhone,
                    verification_materials AS verificationMaterials,
                    review_note AS reviewNote,
                    COALESCE(DATE_FORMAT(last_login_at, '%Y-%m-%d %H:%i'), '-') AS lastLogin,
                    CASE
                        WHEN role_code <> 'volunteer' THEN TRUE
                        WHEN age_years IS NOT NULL
                             AND gender_code IS NOT NULL
                             AND TRIM(gender_code) <> ''
                             AND contact_phone IS NOT NULL
                             AND TRIM(contact_phone) <> ''
                            THEN TRUE
                        ELSE FALSE
                    END AS profileCompleted
                FROM platform_users
                WHERE id = :userId
                """).param("userId", userId).query(UserProfile.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    @PutMapping("/{userId}/profile")
    UserProfile updateProfile(@PathVariable Long userId, @RequestBody ProfileUpdateRequest request) {
        PlatformUserEntity existing = loadUserById(userId);

        String name = coalesceText(request.name(), existing.name());
        String community = coalesceText(request.community(), existing.community());
        String bio = request.bio() == null ? trimToEmpty(existing.bio()) : trimToEmpty(request.bio());
        Integer age = request.age() == null ? existing.age() : normalizeAge(request.age(), true);
        String gender = request.gender() == null ? normalizeGender(existing.gender(), false) : normalizeGender(request.gender(), true);
        String contactPhone = request.contactPhone() == null
                ? trimToEmpty(existing.contactPhone())
                : trimToEmpty(request.contactPhone());
        String verificationMaterials = request.verificationMaterials() == null
                ? trimToEmpty(existing.verificationMaterials())
                : trimToEmpty(request.verificationMaterials());

        if ("organization".equals(existing.role())
                && "REJECTED".equals(existing.status())
                && !verificationMaterials.isBlank()) {
            // Allow rejected organizations to resubmit proofs.
            jdbcClient.sql("""
                    UPDATE platform_users
                    SET
                        status_code = 'PENDING_REVIEW',
                        review_note = '组织者重新提交资质，等待审核',
                        approved_at = NULL
                    WHERE id = :userId
                    """).param("userId", userId).update();
        }

        jdbcClient.sql("""
                UPDATE platform_users
                SET
                    name = :name,
                    community_name = :community,
                    bio = :bio,
                    age_years = :age,
                    gender_code = :gender,
                    contact_phone = :contactPhone,
                    verification_materials = :verificationMaterials,
                    updated_at = NOW()
                WHERE id = :userId
                """)
                .param("userId", userId)
                .param("name", name)
                .param("community", community)
                .param("bio", bio)
                .param("age", age)
                .param("gender", gender)
                .param("contactPhone", contactPhone)
                .param("verificationMaterials", verificationMaterials.isBlank() ? null : verificationMaterials)
                .update();

        return profile(userId);
    }

    @GetMapping("/reviews/organizations")
    OrganizationReviewList organizationReviews(@RequestParam(required = false) String status) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(" WHERE role_code = 'organization' ");

        if (status != null && !status.trim().isEmpty()) {
            where.append(" AND status_code = :status ");
            params.put("status", status.trim());
        }

        List<OrganizationReviewItem> items = applyParams(jdbcClient.sql("""
                SELECT
                    id,
                    name,
                    mobile,
                    community_name AS community,
                    status_code AS status,
                    verification_materials AS verificationMaterials,
                    review_note AS reviewNote,
                    COALESCE(DATE_FORMAT(created_at, '%Y-%m-%d %H:%i'), '-') AS submittedAt,
                    COALESCE(DATE_FORMAT(approved_at, '%Y-%m-%d %H:%i'), '-') AS reviewedAt
                FROM platform_users
                """ + where + """
                ORDER BY created_at DESC
                """), params).query(OrganizationReviewItem.class).list();
        return new OrganizationReviewList(items);
    }

    @PostMapping("/reviews/organizations/{organizationUserId}")
    OrganizationReviewResult reviewOrganization(
            @PathVariable Long organizationUserId,
            @RequestBody OrganizationReviewActionRequest request) {
        PlatformUserEntity organization = loadUserById(organizationUserId);
        if (!"organization".equals(organization.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target user is not organization");
        }

        boolean approved = Boolean.TRUE.equals(request.approved());
        String reviewNote = trimToEmpty(request.reviewNote());
        String nextStatus = approved ? "ACTIVE" : "REJECTED";
        String note = reviewNote.isBlank() ? (approved ? "组织者审核通过" : "组织者审核未通过") : reviewNote;

        jdbcClient.sql("""
                UPDATE platform_users
                SET
                    status_code = :status,
                    review_note = :reviewNote,
                    approved_at = :approvedAt,
                    updated_at = NOW()
                WHERE id = :userId
                """)
                .param("status", nextStatus)
                .param("reviewNote", note)
                .param("approvedAt", approved ? Timestamp.valueOf(LocalDateTime.now()) : null)
                .param("userId", organizationUserId)
                .update();

        if (!approved) {
            jdbcClient.sql("""
                    DELETE FROM user_sessions
                    WHERE user_id = :userId
                    """).param("userId", organizationUserId).update();
        }

        return new OrganizationReviewResult(organizationUserId, nextStatus, note);
    }

    @GetMapping("/admin/list")
    UserListResponse userList(
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "50") Integer limit) {
        int safeLimit = Math.max(Math.min(limit, 200), 1);
        StringBuilder where = new StringBuilder(" WHERE role_code <> 'admin' ");
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("limit", safeLimit);

        if (role != null && !role.trim().isEmpty()) {
            where.append(" AND role_code = :role ");
            params.put("role", role.trim());
        }

        List<UserListItem> items = applyParams(jdbcClient.sql("""
                SELECT
                    id,
                    name,
                    mobile,
                    role_code AS role,
                    community_name AS community,
                    status_code AS status,
                    COALESCE(DATE_FORMAT(created_at, '%Y-%m-%d %H:%i'), '-') AS createdAt
                FROM platform_users
                """ + where + """
                ORDER BY created_at DESC
                LIMIT :limit
                """), params).query(UserListItem.class).list();
        return new UserListResponse(items);
    }

    @DeleteMapping("/{userId}")
    UserDeleteResult deleteUser(@PathVariable Long userId) {
        PlatformUserEntity user = loadUserById(userId);
        if ("admin".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cannot delete admin account");
        }
        jdbcClient.sql("""
                DELETE FROM user_sessions WHERE user_id = :userId
                """).param("userId", userId).update();
        jdbcClient.sql("""
                DELETE FROM platform_users WHERE id = :userId
                """).param("userId", userId).update();
        return new UserDeleteResult(userId, "DELETED");
    }

    private PlatformUserEntity loadUserById(Long userId) {
        return jdbcClient.sql("""
                SELECT
                    id,
                    name,
                    mobile,
                    password_hash AS passwordHash,
                    role_code AS role,
                    community_name AS community,
                    status_code AS status,
                    bio,
                    age_years AS age,
                    gender_code AS gender,
                    contact_phone AS contactPhone,
                    verification_materials AS verificationMaterials,
                    review_note AS reviewNote
                FROM platform_users
                WHERE id = :userId
                """).param("userId", userId).query(PlatformUserEntity.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    private AuthResponse createSession(PlatformUserEntity user) {
        String token = UUID.randomUUID().toString();
        Timestamp expiresAt = Timestamp.valueOf(LocalDateTime.now().plusDays(7));

        jdbcClient.sql("""
                INSERT INTO user_sessions (token, user_id, expires_at)
                VALUES (:token, :userId, :expiresAt)
                """)
                .param("token", token)
                .param("userId", user.id())
                .param("expiresAt", expiresAt)
                .update();

        jdbcClient.sql("""
                UPDATE platform_users
                SET last_login_at = NOW()
                WHERE id = :userId
                """).param("userId", user.id()).update();

        return new AuthResponse(token, toSessionUser(user));
    }

    private SessionUser toSessionUser(PlatformUserEntity user) {
        boolean profileCompleted = !"volunteer".equals(user.role())
                || (user.age() != null
                        && user.gender() != null
                        && !user.gender().trim().isEmpty()
                        && user.contactPhone() != null
                        && !user.contactPhone().trim().isEmpty());
        return new SessionUser(
                user.id(),
                user.name(),
                user.role(),
                user.community(),
                user.status(),
                profileCompleted);
    }

    private JdbcClient.StatementSpec applyParams(JdbcClient.StatementSpec statementSpec, Map<String, Object> params) {
        JdbcClient.StatementSpec spec = statementSpec;
        for (Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec;
    }

    private String normalizeRole(String role, boolean allowAdmin) {
        String normalized = requiredText(role, "role is required").toLowerCase();
        if ("volunteer".equals(normalized) || "organization".equals(normalized)) {
            return normalized;
        }
        if (allowAdmin && "admin".equals(normalized)) {
            return normalized;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported role type");
    }

    private Integer normalizeAge(Integer age, boolean allowNull) {
        if (age == null) {
            if (allowNull) {
                return null;
            }
            return null;
        }
        if (age < MIN_AGE || age > MAX_AGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "age is invalid");
        }
        return age;
    }

    private String normalizeGender(String gender, boolean allowNull) {
        if (gender == null || gender.trim().isEmpty()) {
            return allowNull ? null : trimToEmpty(gender);
        }
        String normalized = gender.trim().toUpperCase();
        if (!"MALE".equals(normalized) && !"FEMALE".equals(normalized) && !"OTHER".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gender must be MALE/FEMALE/OTHER");
        }
        return normalized;
    }

    private String coalesceText(String value, String defaultValue) {
        if (value == null) {
            return requiredText(defaultValue, "field is required");
        }
        return requiredText(value, "field is required");
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String requiredText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
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

record UserOverview(
        Integer totalUsers,
        Integer volunteerUsers,
        Integer organizationUsers) {
}

record RegisterRequest(
        String role,
        String name,
        String mobile,
        String password,
        String community,
        String bio,
        Integer age,
        String gender,
        String contactPhone,
        String verificationMaterials) {
}

record LoginRequest(
        String role,
        String mobile,
        String password) {
}

record ProfileUpdateRequest(
        String name,
        String community,
        String bio,
        Integer age,
        String gender,
        String contactPhone,
        String verificationMaterials) {
}

record AuthResponse(String token, SessionUser user) {
}

record SessionUser(
        Long id,
        String name,
        String role,
        String community,
        String status,
        Boolean profileCompleted) {
}

record UserProfile(
        Long id,
        String name,
        String mobile,
        String role,
        String community,
        String status,
        String bio,
        Integer age,
        String gender,
        String contactPhone,
        String verificationMaterials,
        String reviewNote,
        String lastLogin,
        Boolean profileCompleted) {
}

record OrganizationReviewItem(
        Long id,
        String name,
        String mobile,
        String community,
        String status,
        String verificationMaterials,
        String reviewNote,
        String submittedAt,
        String reviewedAt) {
}

record OrganizationReviewList(
        List<OrganizationReviewItem> items) {
}

record OrganizationReviewActionRequest(
        Boolean approved,
        String reviewNote) {
}

record OrganizationReviewResult(
        Long organizationUserId,
        String status,
        String reviewNote) {
}

record UserListItem(
        Long id,
        String name,
        String mobile,
        String role,
        String community,
        String status,
        String createdAt) {
}

record UserListResponse(List<UserListItem> items) {
}

record UserDeleteResult(Long userId, String status) {
}

record PlatformUserEntity(
        Long id,
        String name,
        String mobile,
        String passwordHash,
        String role,
        String community,
        String status,
        String bio,
        Integer age,
        String gender,
        String contactPhone,
        String verificationMaterials,
        String reviewNote) {
}
