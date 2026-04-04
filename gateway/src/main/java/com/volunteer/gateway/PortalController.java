package com.volunteer.gateway;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping
class PortalController {

    private static final String SESSION_HEADER = "X-Session-Token";

    private final RestClient restClient;
    private final String userBaseUrl;
    private final String volunteerBaseUrl;
    private final String activityBaseUrl;
    private final String communityBaseUrl;

    PortalController(
            RestClient.Builder restClientBuilder,
            @Value("${services.user.base-url}") String userBaseUrl,
            @Value("${services.volunteer.base-url}") String volunteerBaseUrl,
            @Value("${services.activity.base-url}") String activityBaseUrl,
            @Value("${services.community.base-url}") String communityBaseUrl) {
        this.restClient = restClientBuilder.build();
        this.userBaseUrl = userBaseUrl;
        this.volunteerBaseUrl = volunteerBaseUrl;
        this.activityBaseUrl = activityBaseUrl;
        this.communityBaseUrl = communityBaseUrl;
    }

    @GetMapping("/")
    GatewayInfo root() {
        return new GatewayInfo(
                "volunteer-gateway",
                "platform gateway is running",
                List.of(userBaseUrl, volunteerBaseUrl, activityBaseUrl, communityBaseUrl));
    }

    @GetMapping("/api/home")
    HomeResponse home(@RequestHeader(value = SESSION_HEADER, required = false) String token) {
        SessionUser sessionUser = requireSession(token);
        UserOverview userOverview = get(userBaseUrl + "/internal/users/overview", UserOverview.class);
        ActivityOverview activityOverview = get(activityBaseUrl + "/internal/activities/overview", ActivityOverview.class);
        NoticeListResponse noticeList = get(
                UriComponentsBuilder.fromHttpUrl(communityBaseUrl + "/internal/community/notices")
                        .queryParam("role", sessionUser.role())
                        .queryParam("limit", 3)
                        .toUriString(),
                NoticeListResponse.class);

        return new HomeResponse(
                List.of(
                        new StatItem("注册志愿者", userOverview.volunteerUsers(), "当前活跃志愿者规模"),
                        new StatItem("合作组织", userOverview.organizationUsers(), "可发起活动主体"),
                        new StatItem("开放活动", activityOverview.openActivities(), "当前可报名"),
                        new StatItem("累计报名", activityOverview.enrolledCount(), "平台累计报名量")),
                activityOverview.featuredActivities(),
                noticeList.notices());
    }

    @GetMapping("/api/portal")
    HomeResponse portal(@RequestHeader(value = SESSION_HEADER, required = false) String token) {
        return home(token);
    }

    @PostMapping("/api/auth/register")
    AuthResponse register(@RequestBody RegisterRequest request) {
        return post(userBaseUrl + "/internal/users/register", request, AuthResponse.class);
    }

    @PostMapping("/api/auth/login")
    AuthResponse login(@RequestBody LoginRequest request) {
        return post(userBaseUrl + "/internal/users/login", request, AuthResponse.class);
    }

    @PostMapping("/api/admin/auth/login")
    AuthResponse adminLogin(@RequestBody AdminLoginRequest request) {
        AuthResponse response = post(
                userBaseUrl + "/internal/users/login",
                new LoginRequest("admin", request.mobile(), request.password()),
                AuthResponse.class);
        if (response.user() == null || !"admin".equals(response.user().role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin login is required");
        }
        return response;
    }

    @GetMapping("/api/session")
    SessionUser session(@RequestHeader(value = SESSION_HEADER, required = false) String token) {
        return requireSession(token);
    }

    @GetMapping("/api/profile")
    UserProfile profile(@RequestHeader(value = SESSION_HEADER, required = false) String token) {
        SessionUser user = requireSession(token);
        return get(userBaseUrl + "/internal/users/" + user.id(), UserProfile.class);
    }

    @PutMapping("/api/profile")
    UserProfile updateProfile(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @RequestBody ProfileUpdateRequest request) {
        SessionUser user = requireSession(token);
        return put(userBaseUrl + "/internal/users/" + user.id() + "/profile", request, UserProfile.class);
    }

    @GetMapping("/api/activities")
    ActivityPageResponse activities(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "6") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        SessionUser user = requireSession(token);

        String url = UriComponentsBuilder.fromHttpUrl(activityBaseUrl + "/internal/activities/public")
                .queryParam("page", page)
                .queryParam("pageSize", pageSize)
                .queryParamIfPresent("keyword", optionalValue(keyword))
                .queryParamIfPresent("category", optionalValue(category))
                .toUriString();

        PublicActivityPage activityPage = get(url, PublicActivityPage.class);
        Set<Long> joinedActivityIds = loadJoinedActivityIds(user);

        List<ActivityCard> items = activityPage.items().stream()
                .map(item -> new ActivityCard(
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
                        item.coverImage(),
                        joinedActivityIds.contains(item.id())))
                .toList();

        return new ActivityPageResponse(
                activityPage.page(),
                activityPage.pageSize(),
                activityPage.total(),
                activityPage.categories(),
                items);
    }

    @GetMapping("/api/activities/{activityId}")
    ActivityDetailResponse activityDetail(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @PathVariable Long activityId) {
        SessionUser user = requireSession(token);
        ActivityDetail detail = get(activityBaseUrl + "/internal/activities/" + activityId, ActivityDetail.class);
        boolean joined = "volunteer".equals(user.role()) && loadJoinedActivityIds(user).contains(activityId);
        boolean editable = "organization".equals(user.role()) && user.id().equals(detail.creatorUserId());
        return toActivityDetail(detail, joined, editable);
    }

    @PostMapping("/api/activities/{activityId}/enroll")
    EnrollmentAction enroll(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @PathVariable Long activityId) {
        SessionUser user = requireSession(token);
        if (!"volunteer".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only volunteers can enroll");
        }
        if (!Boolean.TRUE.equals(user.profileCompleted())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "complete your profile before enrollment");
        }

        Set<Long> joinedIds = loadJoinedActivityIds(user);
        if (joinedIds.contains(activityId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already enrolled");
        }

        ActivityDetail activity = get(activityBaseUrl + "/internal/activities/" + activityId, ActivityDetail.class);
        if (!"PUBLISHED".equals(activity.statusCode())
                || activity.capacity() == null
                || activity.enrolled() == null
                || activity.enrolled() >= activity.capacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "activity is not open");
        }

        postWithoutBody(activityBaseUrl + "/internal/activities/" + activityId + "/occupancy/increase", ActivityOccupancy.class);

        EnrollmentSubmission submission = post(
                volunteerBaseUrl + "/internal/volunteers/enrollments",
                new EnrollmentRequest(
                        user.id(),
                        user.name(),
                        activity.id(),
                        activity.title(),
                        activity.schedule(),
                        activity.location()),
                EnrollmentSubmission.class);

        return new EnrollmentAction(submission.enrollmentId(), submission.status());
    }

    @PostMapping("/api/activities/proposals")
    ProposalResult submitProposal(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @RequestBody ProposalForm request) {
        SessionUser user = requireSession(token);
        requireApprovedOrganization(user);

        ProposalSubmission result = post(
                activityBaseUrl + "/internal/activities/proposals",
                new ProposalRequest(
                        user.id(),
                        user.name(),
                        request.title(),
                        request.category(),
                        request.location(),
                        request.startAt(),
                        request.endAt(),
                        request.capacity(),
                        request.contactMobile(),
                        request.description(),
                        request.coverImage()),
                ProposalSubmission.class);

        return new ProposalResult(result.proposalId(), result.status());
    }

    @GetMapping("/api/activities/manage")
    List<ActivityListItem> manageActivities(@RequestHeader(value = SESSION_HEADER, required = false) String token) {
        SessionUser user = requireSession(token);
        requireApprovedOrganization(user);

        OrganizationWorkspace workspace = get(
                activityBaseUrl + "/internal/activities/organizations/" + user.id() + "/workspace",
                OrganizationWorkspace.class);
        return workspace.activities();
    }

    @PutMapping("/api/activities/{activityId}")
    ActivityDetailResponse updateActivity(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @PathVariable Long activityId,
            @RequestBody ActivityUpdateForm request) {
        SessionUser user = requireSession(token);
        requireApprovedOrganization(user);

        ActivityDetail updated = put(
                activityBaseUrl + "/internal/activities/" + activityId,
                new ActivityUpdateRequest(
                        user.id(),
                        request.title(),
                        request.category(),
                        request.location(),
                        request.startAt(),
                        request.endAt(),
                        request.capacity(),
                        request.contactMobile(),
                        request.description(),
                        request.coverImage()),
                ActivityDetail.class);

        return toActivityDetail(updated, false, true);
    }

    @PostMapping("/api/activities/{activityId}/notifications")
    ActivityNotificationDispatchResult notifyParticipants(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @PathVariable Long activityId,
            @RequestBody ActivityNotificationForm request) {
        SessionUser user = requireSession(token);
        requireApprovedOrganization(user);

        ActivityDetail detail = get(activityBaseUrl + "/internal/activities/" + activityId, ActivityDetail.class);
        if (!user.id().equals(detail.creatorUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only activity owner can send notifications");
        }

        ActivityNotificationDispatch dispatch = post(
                volunteerBaseUrl + "/internal/volunteers/activities/" + activityId + "/notifications",
                new ActivityNotificationRequest(
                        user.id(),
                        user.name(),
                        request.title(),
                        request.content()),
                ActivityNotificationDispatch.class);

        return new ActivityNotificationDispatchResult(dispatch.activityId(), dispatch.deliveredCount(), dispatch.message());
    }

    @GetMapping("/api/notifications")
    List<ActivityNotificationItem> notifications(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @RequestParam(defaultValue = "20") Integer limit) {
        SessionUser user = requireSession(token);
        if (!"volunteer".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only volunteers can read notifications");
        }
        return get(
                UriComponentsBuilder.fromHttpUrl(volunteerBaseUrl + "/internal/volunteers/users/" + user.id() + "/notifications")
                        .queryParam("limit", limit)
                        .toUriString(),
                ActivityNotificationList.class).items();
    }

    @GetMapping("/api/admin/reviews/organizations")
    List<OrganizationReviewItem> organizationReviews(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @RequestParam(required = false) String status) {
        SessionUser user = requireSession(token);
        requireAdmin(user);
        return get(
                UriComponentsBuilder.fromHttpUrl(userBaseUrl + "/internal/users/reviews/organizations")
                        .queryParamIfPresent("status", optionalValue(status))
                        .toUriString(),
                OrganizationReviewList.class).items();
    }

    @PostMapping("/api/admin/reviews/organizations/{organizationUserId}")
    OrganizationReviewResult reviewOrganization(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @PathVariable Long organizationUserId,
            @RequestBody OrganizationReviewAction action) {
        SessionUser user = requireSession(token);
        requireAdmin(user);
        return post(
                userBaseUrl + "/internal/users/reviews/organizations/" + organizationUserId,
                new OrganizationReviewAction(action.approved(), action.reviewNote()),
                OrganizationReviewResult.class);
    }

    @GetMapping("/api/admin/reviews/proposals")
    List<ActivityProposalReviewItem> proposalReviews(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @RequestParam(required = false) String status) {
        SessionUser user = requireSession(token);
        requireAdmin(user);
        return get(
                UriComponentsBuilder.fromHttpUrl(activityBaseUrl + "/internal/activities/proposals/review")
                        .queryParamIfPresent("status", optionalValue(status))
                        .toUriString(),
                ActivityProposalReviewList.class).items();
    }

    @PostMapping("/api/admin/reviews/proposals/{proposalId}")
    ProposalReviewResult reviewProposal(
            @RequestHeader(value = SESSION_HEADER, required = false) String token,
            @PathVariable Long proposalId,
            @RequestBody ProposalReviewAction action) {
        SessionUser user = requireSession(token);
        requireAdmin(user);
        return post(
                activityBaseUrl + "/internal/activities/proposals/" + proposalId + "/review",
                new ProposalReviewRequest(user.id(), action.approved(), action.reviewNote()),
                ProposalReviewResult.class);
    }

    @GetMapping("/api/dashboard")
    DashboardResponse dashboard(@RequestHeader(value = SESSION_HEADER, required = false) String token) {
        SessionUser user = requireSession(token);
        UserProfile profile = get(userBaseUrl + "/internal/users/" + user.id(), UserProfile.class);
        NoticeListResponse noticeResponse = get(
                UriComponentsBuilder.fromHttpUrl(communityBaseUrl + "/internal/community/notices")
                        .queryParam("role", user.role())
                        .queryParam("limit", 4)
                        .toUriString(),
                NoticeListResponse.class);

        if ("organization".equals(user.role())) {
            OrganizationWorkspace workspace = get(
                    activityBaseUrl + "/internal/activities/organizations/" + user.id() + "/workspace",
                    OrganizationWorkspace.class);

            int proposalCount = workspace.proposals().size();
            int managedCount = workspace.activities().size();
            int totalCapacity = workspace.activities().stream().mapToInt(item -> item.capacity() == null ? 0 : item.capacity()).sum();
            int enrolledCount = workspace.activities().stream().mapToInt(item -> item.enrolled() == null ? 0 : item.enrolled()).sum();

            return new DashboardResponse(
                    user,
                    profile,
                    List.of(
                            new StatItem("我发布的活动", managedCount, "组织方活动规模"),
                            new StatItem("审核中的申请", proposalCount, "等待平台处理"),
                            new StatItem("活动总名额", totalCapacity, "已发布活动容量"),
                            new StatItem("已报名人数", enrolledCount, "当前活动参与规模")),
                    List.of(
                            new DashboardSection(
                                    "最新活动",
                                    "/manage-activities.html",
                                    "维护活动",
                                    workspace.activities().stream()
                                            .limit(4)
                                            .map(item -> new DashboardItem(
                                                    item.title(),
                                                    item.category() + " 路 " + item.location(),
                                                    item.schedule(),
                                                    item.status()))
                                            .toList()),
                            new DashboardSection(
                                    "申请进度",
                                    "/publish.html",
                                    "提交新申请",
                                    workspace.proposals().stream()
                                            .limit(4)
                                            .map(item -> new DashboardItem(
                                                    item.title(),
                                                    item.category() + " 路 " + item.location(),
                                                    item.submittedAt(),
                                                    item.status()))
                                            .toList())),
                    noticeResponse.notices());
        }

        if ("volunteer".equals(user.role())) {
            VolunteerDashboard volunteerDashboard = get(
                    volunteerBaseUrl + "/internal/volunteers/users/" + user.id() + "/dashboard",
                    VolunteerDashboard.class);
            ActivityNotificationList notificationList = get(
                    UriComponentsBuilder.fromHttpUrl(volunteerBaseUrl + "/internal/volunteers/users/" + user.id() + "/notifications")
                            .queryParam("limit", 4)
                            .toUriString(),
                    ActivityNotificationList.class);

            return new DashboardResponse(
                    user,
                    profile,
                    List.of(
                            new StatItem("我的报名", volunteerDashboard.summary().totalEnrollments(), "累计报名活动"),
                            new StatItem("完成服务", volunteerDashboard.summary().completedServices(), "已完成服务记录"),
                            new StatItem("累计积分", volunteerDashboard.summary().totalPoints(), "当前积分总量"),
                            new StatItem("服务时长", volunteerDashboard.summary().totalServiceHours(), "累计服务时长")),
                    List.of(
                            new DashboardSection(
                                    "近期活动",
                                    "/activities.html",
                                    "继续报名",
                                    volunteerDashboard.enrollments().stream()
                                            .limit(4)
                                            .map(item -> new DashboardItem(
                                                    item.activityTitle(),
                                                    item.schedule() + " 路 " + item.location(),
                                                    "报名时间 " + item.enrolledAt(),
                                                    item.status()))
                                            .toList()),
                            new DashboardSection(
                                    "活动通知",
                                    "/dashboard.html",
                                    "查看最新",
                                    notificationList.items().stream()
                                            .limit(4)
                                            .map(item -> new DashboardItem(
                                                    item.title(),
                                                    item.content(),
                                                    item.createdAt(),
                                                    "通知"))
                                            .toList())),
                    noticeResponse.notices());
        }

        UserOverview userOverview = get(userBaseUrl + "/internal/users/overview", UserOverview.class);
        ActivityOverview activityOverview = get(activityBaseUrl + "/internal/activities/overview", ActivityOverview.class);
        VolunteerOverview volunteerOverview = get(volunteerBaseUrl + "/internal/volunteers/overview", VolunteerOverview.class);

        return new DashboardResponse(
                user,
                profile,
                List.of(
                        new StatItem("注册用户", userOverview.totalUsers(), "不含平台管理员"),
                        new StatItem("开放活动", activityOverview.openActivities(), "当前开放报名"),
                        new StatItem("累计报名", volunteerOverview.totalEnrollments(), "平台报名总量"),
                        new StatItem("完成服务", volunteerOverview.completedServices(), "已完成服务记录")),
                List.of(
                        new DashboardSection("平台概览", "/activities.html", "查看活动", List.of()),
                        new DashboardSection("平台通知", "/dashboard.html", "留在当前页", List.of())),
                noticeResponse.notices());
    }

    private SessionUser requireSession(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "please login first");
        }
        return get(userBaseUrl + "/internal/users/session/" + token, SessionUser.class);
    }

    private void requireAdmin(SessionUser user) {
        if (!"admin".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin access is required");
        }
    }

    private void requireApprovedOrganization(SessionUser user) {
        if (!"organization".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "organization access is required");
        }
        if (!"ACTIVE".equals(user.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "organization account is not approved yet");
        }
    }

    private Set<Long> loadJoinedActivityIds(SessionUser user) {
        if (!"volunteer".equals(user.role())) {
            return Set.of();
        }

        UserActivityIds ids = get(
                volunteerBaseUrl + "/internal/volunteers/users/" + user.id() + "/activity-ids",
                UserActivityIds.class);
        return ids.activityIds().stream().collect(Collectors.toSet());
    }

    private ActivityDetailResponse toActivityDetail(ActivityDetail detail, boolean joined, boolean editable) {
        return new ActivityDetailResponse(
                detail.id(),
                detail.title(),
                detail.category(),
                detail.location(),
                detail.startAt(),
                detail.endAt(),
                detail.schedule(),
                detail.capacity(),
                detail.enrolled(),
                detail.status(),
                detail.statusCode(),
                detail.organizerName(),
                detail.contactMobile(),
                detail.description(),
                detail.coverImage(),
                joined,
                editable);
    }

    private Optional<String> optionalValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    private <T> T get(String url, Class<T> responseType) {
        try {
            return restClient.get().uri(url).retrieve().body(responseType);
        } catch (RestClientResponseException exception) {
            throw toGatewayException(exception);
        }
    }

    private <T> T post(String url, Object body, Class<T> responseType) {
        try {
            return restClient.post().uri(url).body(body).retrieve().body(responseType);
        } catch (RestClientResponseException exception) {
            throw toGatewayException(exception);
        }
    }

    private <T> T put(String url, Object body, Class<T> responseType) {
        try {
            return restClient.put().uri(url).body(body).retrieve().body(responseType);
        } catch (RestClientResponseException exception) {
            throw toGatewayException(exception);
        }
    }

    private <T> T postWithoutBody(String url, Class<T> responseType) {
        try {
            return restClient.post().uri(url).retrieve().body(responseType);
        } catch (RestClientResponseException exception) {
            throw toGatewayException(exception);
        }
    }

    private ResponseStatusException toGatewayException(RestClientResponseException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = exception.getResponseBodyAsString();
        if (message == null || message.isBlank()) {
            message = "downstream service error";
        }
        return new ResponseStatusException(status, message, exception);
    }
}

record GatewayInfo(String name, String message, List<String> services) {
}

record HomeResponse(
        List<StatItem> stats,
        List<ActivitySummaryCard> activities,
        List<NoticeItem> notices) {
}

record StatItem(String label, Number value, String hint) {
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

record AdminLoginRequest(
        String mobile,
        String password) {
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

record ProfileUpdateRequest(
        String name,
        String community,
        String bio,
        Integer age,
        String gender,
        String contactPhone,
        String verificationMaterials) {
}

record UserOverview(
        Integer totalUsers,
        Integer volunteerUsers,
        Integer organizationUsers) {
}

record ActivityOverview(
        Integer openActivities,
        Integer totalCapacity,
        Integer enrolledCount,
        Integer categoryCount,
        List<ActivitySummaryCard> featuredActivities) {
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

record ActivityCard(
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
        String coverImage,
        Boolean joined) {
}

record ActivityPageResponse(
        Integer page,
        Integer pageSize,
        Long total,
        List<String> categories,
        List<ActivityCard> items) {
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

record ActivityDetailResponse(
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
        String organizerName,
        String contactMobile,
        String description,
        String coverImage,
        Boolean joined,
        Boolean editable) {
}

record ActivityOccupancy(
        Long activityId,
        Integer enrolled,
        Integer capacity) {
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

record EnrollmentAction(
        Long enrollmentId,
        String status) {
}

record UserActivityIds(List<Long> activityIds) {
}

record ActivityNotificationForm(
        String title,
        String content) {
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

record ActivityNotificationDispatchResult(
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

record ProposalForm(
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

record ActivityUpdateForm(
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

record ProposalSubmission(
        Long proposalId,
        String status) {
}

record ProposalResult(
        Long proposalId,
        String status) {
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

record OrganizationReviewAction(
        Boolean approved,
        String reviewNote) {
}

record OrganizationReviewResult(
        Long organizationUserId,
        String status,
        String reviewNote) {
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

record ProposalReviewAction(
        Boolean approved,
        String reviewNote) {
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

record VolunteerOverview(
        Integer totalEnrollments,
        Integer completedServices,
        Double totalServiceHours,
        Integer totalPoints) {
}

record VolunteerDashboard(
        VolunteerSummary summary,
        List<EnrollmentItem> enrollments,
        List<RewardProductItem> rewards,
        List<RewardOrderItem> orders) {
}

record VolunteerSummary(
        Integer totalEnrollments,
        Integer completedServices,
        Integer totalPoints,
        Double totalServiceHours) {
}

record EnrollmentItem(
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

record DashboardResponse(
        SessionUser user,
        UserProfile profile,
        List<StatItem> stats,
        List<DashboardSection> sections,
        List<NoticeItem> notices) {
}

record DashboardSection(
        String title,
        String actionPath,
        String actionLabel,
        List<DashboardItem> items) {
}

record DashboardItem(
        String title,
        String detail,
        String meta,
        String status) {
}

