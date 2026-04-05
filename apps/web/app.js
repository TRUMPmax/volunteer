const API_BASE = window.__API_BASE__ || "http://127.0.0.1:8000/api";
const SESSION_KEY = "volunteer-platform-session";
const MAX_COVER_SIZE_MB = 2;

function qs(selector, root = document) {
  return root.querySelector(selector);
}

function qsa(selector, root = document) {
  return Array.from(root.querySelectorAll(selector));
}

function formatNumber(value) {
  return new Intl.NumberFormat("zh-CN").format(value ?? 0);
}

function readSession() {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (error) {
    console.error(error);
    return null;
  }
}

function saveSession(session) {
  localStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

function clearSession() {
  localStorage.removeItem(SESSION_KEY);
}

function getValidSessionShape() {
  const session = readSession();
  if (!session?.token || !session?.user?.role) {
    if (session) {
      clearSession();
    }
    return null;
  }
  return session;
}

function getCoverSrc(item) {
  const provided = item?.coverImage || item?.cover_image;
  if (provided && String(provided).trim()) {
    return String(provided).trim();
  }
  const id = Math.max(Number(item?.id || 1), 1);
  const variant = ((id - 1) % 4) + 1;
  return `/assets/covers/cover-${variant}.svg`;
}

function activityDetailUrl(id) {
  return `/activity-detail.html?id=${encodeURIComponent(String(id))}`;
}

async function apiFetch(path, options = {}) {
  const session = readSession();
  const headers = new Headers(options.headers || {});

  if (!headers.has("Content-Type") && options.body) {
    headers.set("Content-Type", "application/json");
  }

  if (session?.token) {
    headers.set("X-Session-Token", session.token);
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
    body: options.body ? JSON.stringify(options.body) : undefined,
  });

  if (!response.ok) {
    let message = "请求失败";
    try {
      const errorBody = await response.json();
      message = errorBody.message || errorBody.error || message;
    } catch (error) {
      const text = await response.text();
      if (text) {
        message = text;
      }
    }
    throw new Error(message);
  }

  if (response.status === 204) {
    return null;
  }

  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function validateSession() {
  const session = getValidSessionShape();
  if (!session?.token) {
    return false;
  }

  try {
    const user = await apiFetch("/session");
    saveSession({ token: session.token, user });
    return true;
  } catch (error) {
    clearSession();
    return false;
  }
}

function createEmptyState(message) {
  const div = document.createElement("div");
  div.className = "empty-state";
  div.textContent = message;
  return div;
}

function showFeedback(selector, message, type = "info") {
  const box = qs(selector);
  if (!box) {
    return;
  }
  box.hidden = false;
  box.className = `feedback is-${type}`;
  box.textContent = message;
}

function hideFeedback(selector) {
  const box = qs(selector);
  if (!box) {
    return;
  }
  box.hidden = true;
  box.textContent = "";
}

function renderStats(container, items) {
  if (!container) {
    return;
  }

  container.innerHTML = "";
  if (!items?.length) {
    container.appendChild(createEmptyState("暂无数据"));
    return;
  }

  items.forEach((item) => {
    const card = document.createElement("article");
    card.className = "stat-card";
    card.innerHTML = `
      <span class="stat-label">${item.label}</span>
      <strong class="stat-value">${formatNumber(item.value)}</strong>
      <span class="stat-hint">${item.hint || ""}</span>
    `;
    container.appendChild(card);
  });
}

function renderList(container, items, renderer, emptyMessage) {
  if (!container) {
    return;
  }

  container.innerHTML = "";
  if (!items?.length) {
    container.appendChild(createEmptyState(emptyMessage));
    return;
  }

  items.forEach((item) => {
    const row = document.createElement("article");
    row.className = "list-item";
    row.innerHTML = renderer(item);
    container.appendChild(row);
  });
}

function renderPagination(container, page, pageSize, total, onChange) {
  if (!container) {
    return;
  }

  container.innerHTML = "";
  const totalPages = Math.max(Math.ceil((total || 0) / pageSize), 1);
  if (totalPages <= 1) {
    return;
  }

  const prev = document.createElement("button");
  prev.className = "button button-light button-small";
  prev.type = "button";
  prev.textContent = "上一页";
  prev.disabled = page <= 1;
  prev.addEventListener("click", () => onChange(page - 1));

  const next = document.createElement("button");
  next.className = "button button-light button-small";
  next.type = "button";
  next.textContent = "下一页";
  next.disabled = page >= totalPages;
  next.addEventListener("click", () => onChange(page + 1));

  const info = document.createElement("span");
  info.className = "item-meta";
  info.textContent = `${page} / ${totalPages}`;

  container.append(prev, info, next);
}

function updateNav() {
  const nav = qs("#main-nav");
  if (!nav) {
    return;
  }

  const session = getValidSessionShape();
  if (!session) {
    nav.innerHTML = `
      <a href="/index.html">首页</a>
      <a href="/activities.html">活动中心</a>
      <a href="/register.html">注册</a>
      <a href="/login.html">登录</a>
      <a href="/admin-login.html">管理员入口</a>
    `;
    return;
  }

  if (session.user.role === "admin") {
    nav.innerHTML = `
      <a href="/admin-dashboard.html">管理员后台</a>
      <a href="/activities.html">活动中心</a>
      <button class="nav-button" id="logout-nav" type="button">退出</button>
    `;
  } else {
    const orgLinks = session.user.role === "organization"
      ? `<a href="/publish.html">发起活动</a><a href="/manage-activities.html">活动维护</a>`
      : "";

    nav.innerHTML = `
      <a href="/index.html">首页</a>
      <a href="/activities.html">活动中心</a>
      <a href="/dashboard.html">工作台</a>
      <a href="/profile.html">个人资料</a>
      ${orgLinks}
      <button class="nav-button" id="logout-nav" type="button">退出</button>
    `;
  }

  const logout = qs("#logout-nav");
  if (logout) {
    logout.addEventListener("click", () => {
      clearSession();
      window.location.href = "/index.html";
    });
  }
}

function bindRoleSwitch(containerSelector, hiddenSelector, onChange = null) {
  const container = qs(containerSelector);
  const hiddenInput = qs(hiddenSelector);
  if (!container || !hiddenInput) {
    return;
  }

  const buttons = qsa("[data-role]", container);

  function activate(role) {
    hiddenInput.value = role;
    buttons.forEach((button) => {
      button.classList.toggle("is-active", button.dataset.role === role);
    });
    if (typeof onChange === "function") {
      onChange(role);
    }
  }

  buttons.forEach((button) => {
    button.addEventListener("click", () => activate(button.dataset.role));
  });

  activate(hiddenInput.value);
}

function roleLabel(role) {
  if (role === "organization") {
    return "组织者";
  }
  if (role === "volunteer") {
    return "志愿者";
  }
  return "管理员";
}

function activityStatusLabel(status) {
  if (status === "OPEN") {
    return "报名中";
  }
  if (status === "FULL") {
    return "已满员";
  }
  if (status === "CLOSED") {
    return "未开放";
  }
  if (status === "REVIEWING") {
    return "审核中";
  }
  if (status === "APPROVED") {
    return "已通过";
  }
  if (status === "REJECTED") {
    return "已驳回";
  }
  return status || "-";
}

function readImageAsDataUrl(file, maxSizeMb = MAX_COVER_SIZE_MB) {
  if (!file) {
    return Promise.reject(new Error("请选择封面图片"));
  }
  if (!file.type.startsWith("image/")) {
    return Promise.reject(new Error("仅支持图片格式"));
  }
  if (file.size > maxSizeMb * 1024 * 1024) {
    return Promise.reject(new Error(`图片不能超过 ${maxSizeMb}MB`));
  }

  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error("读取图片失败"));
    reader.readAsDataURL(file);
  });
}

function bindCoverPreview(fileInputSelector, imageSelector, feedbackSelector = null) {
  const input = qs(fileInputSelector);
  const image = qs(imageSelector);
  if (!input || !image) {
    return;
  }

  input.addEventListener("change", async () => {
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    try {
      const dataUrl = await readImageAsDataUrl(file);
      image.src = dataUrl;
      image.classList.add("is-ready");
    } catch (error) {
      if (feedbackSelector) {
        showFeedback(feedbackSelector, error.message, "error");
      }
      input.value = "";
    }
  });
}

function renderHomeCarousel(container, activities) {
  if (!container) {
    return;
  }

  container.innerHTML = "";
  if (!activities?.length) {
    container.appendChild(createEmptyState("暂无活动封面"));
    return;
  }

  const stage = document.createElement("div");
  stage.className = "carousel-stage";

  const track = document.createElement("div");
  track.className = "carousel-track";

  const dots = document.createElement("div");
  dots.className = "carousel-dots";

  let index = 0;

  activities.forEach((item, idx) => {
    const slide = document.createElement("a");
    slide.className = "carousel-slide";
    slide.href = activityDetailUrl(item.id);
    slide.innerHTML = `
      <img src="${getCoverSrc(item)}" alt="${item.title}" />
      <div class="carousel-overlay">
        <h3>${item.title}</h3>
        <p>${item.category} · ${item.location} · ${item.schedule}</p>
      </div>
    `;
    track.appendChild(slide);

    const dot = document.createElement("button");
    dot.type = "button";
    dot.className = "carousel-dot";
    dot.addEventListener("click", () => {
      index = idx;
      update();
    });
    dots.appendChild(dot);
  });

  const prev = document.createElement("button");
  prev.type = "button";
  prev.className = "carousel-nav prev";
  prev.textContent = "<";
  prev.addEventListener("click", () => {
    index = (index - 1 + activities.length) % activities.length;
    update();
  });

  const next = document.createElement("button");
  next.type = "button";
  next.className = "carousel-nav next";
  next.textContent = ">";
  next.addEventListener("click", () => {
    index = (index + 1) % activities.length;
    update();
  });

  function update() {
    track.style.transform = `translateX(-${index * 100}%)`;
    qsa(".carousel-dot", dots).forEach((dot, idx) => {
      dot.classList.toggle("is-active", idx === index);
    });
  }

  stage.append(track, prev, next);
  container.append(stage, dots);
  update();

  if (activities.length > 1) {
    window.setInterval(() => {
      index = (index + 1) % activities.length;
      update();
    }, 5000);
  }
}

async function initHomePage() {
  const data = await apiFetch("/home");
  renderStats(qs("#home-stats"), data.stats || []);
  renderHomeCarousel(qs("#home-carousel"), data.activities || []);

  renderList(
    qs("#home-activities"),
    data.activities || [],
    (item) => `
      <div class="item-main">
        <h3>${item.title}</h3>
        <p>${item.category} · ${item.location}</p>
      </div>
      <div class="item-side">
        <span class="item-meta">${item.schedule}</span>
        <a class="text-link" href="${activityDetailUrl(item.id)}">查看详情</a>
      </div>
    `,
    "暂无活动",
  );

  // 首页公告分页
  window.homeNoticesPage = 1;
  window.homeNoticesPerPage = 5;
  window.allHomeNotices = data.notices || [];

  renderHomeNotices();
}

async function loadHomeNotices(page = 1) {
  try {
    const data = await apiFetch("/home");
    window.allHomeNotices = data.notices || [];
    window.homeNoticesPage = page;
    renderHomeNotices();
  } catch (error) {
    console.error('加载公告失败:', error);
  }
}

function renderHomeNotices() {
  if (!window.allHomeNotices || window.allHomeNotices.length === 0) {
    qs("#home-notices").innerHTML = '<div class="empty-state">暂无通知</div>';
    qs("#home-notices-pagination").innerHTML = '';
    return;
  }

  const start = (window.homeNoticesPage - 1) * window.homeNoticesPerPage;
  const end = start + window.homeNoticesPerPage;
  const pageNotices = window.allHomeNotices.slice(start, end);

  renderList(
    qs("#home-notices"),
    pageNotices,
    (item) => `
      <div class="item-main">
        <h3>${item.title}</h3>
        <p>${item.content}</p>
      </div>
      <div class="item-side">
        <span class="item-meta">${item.publishedAt}</span>
        <span class="chip">${item.level}</span>
      </div>
    `,
    "暂无通知",
  );

  renderPagination(
    qs("#home-notices-pagination"),
    window.homeNoticesPage,
    window.homeNoticesPerPage,
    window.allHomeNotices.length,
    loadHomeNotices
  );
}

function initLoginPage() {
  bindRoleSwitch("#login-role-switch", "#login-role");
  const loginForm = qs("#login-form");
  if (!loginForm) {
    return;
  }

  const params = new URLSearchParams(window.location.search);
  const mobileInput = qs('input[name="mobile"]', loginForm);
  if (mobileInput && params.get("mobile")) {
    mobileInput.value = params.get("mobile");
  }
  if (params.get("from") === "register") {
    showFeedback("#login-feedback", "注册成功，请登录。", "success");
  }

  loginForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideFeedback("#login-feedback");
    const formData = new FormData(event.currentTarget);
    const submitButton = qs('button[type="submit"]', loginForm);
    if (submitButton) {
      submitButton.disabled = true;
      submitButton.textContent = "登录中...";
    }

    try {
      const session = await apiFetch("/auth/login", {
        method: "POST",
        body: {
          role: formData.get("role"),
          mobile: formData.get("mobile"),
          password: formData.get("password"),
        },
      });
      saveSession(session);
      window.location.href = "/index.html";
    } catch (error) {
      showFeedback("#login-feedback", error.message, "error");
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = "登录";
      }
    }
  });
}

function initAdminLoginPage() {
  const loginForm = qs("#admin-login-form");
  if (!loginForm) {
    return;
  }

  loginForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideFeedback("#admin-login-feedback");
    const formData = new FormData(event.currentTarget);
    const submitButton = qs('button[type="submit"]', loginForm);
    if (submitButton) {
      submitButton.disabled = true;
      submitButton.textContent = "登录中...";
    }

    try {
      const session = await apiFetch("/admin/auth/login", {
        method: "POST",
        body: {
          mobile: formData.get("mobile"),
          password: formData.get("password"),
        },
      });
      saveSession(session);
      window.location.href = "/admin-dashboard.html";
    } catch (error) {
      showFeedback("#admin-login-feedback", error.message, "error");
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = "管理员登录";
      }
    }
  });
}

function initRegisterPage() {
  // --- 角色切换：显示/隐藏对应的独立表单 ---
  const roleSwitchContainer = qs("#register-role-switch");
  const roleHint = qs("#register-role-hint");
  const volunteerForm = qs("#register-form-volunteer");
  const organizationForm = qs("#register-form-organization");

  const ROLE_HINTS = {
    volunteer: "加入平台，参与志愿服务活动。",
    organization: "组织者账号需管理员审核通过后方可发布活动。",
  };

  function activateRole(role) {
    if (!volunteerForm || !organizationForm) return;
    const isOrg = role === "organization";
    volunteerForm.hidden = isOrg;
    organizationForm.hidden = !isOrg;
    if (roleHint) roleHint.textContent = ROLE_HINTS[role] || "";
    qsa("[data-role]", roleSwitchContainer || document).forEach((btn) => {
      btn.classList.toggle("is-active", btn.dataset.role === role);
    });
  }

  if (roleSwitchContainer) {
    qsa("[data-role]", roleSwitchContainer).forEach((btn) => {
      btn.addEventListener("click", () => activateRole(btn.dataset.role));
    });
  }
  // 默认激活志愿者
  activateRole("volunteer");

  // --- 公共提交逻辑（志愿者 & 组织者表单共用）---
  async function handleRegisterSubmit(event) {
    event.preventDefault();
    hideFeedback("#register-feedback");
    const form = event.currentTarget;
    const formData = new FormData(form);
    const submitButton = qs('button[type="submit"]', form);
    const originalText = submitButton ? submitButton.textContent : "";

    if (submitButton) {
      submitButton.disabled = true;
      submitButton.textContent = "提交中...";
    }

    const ageRaw = String(formData.get("age") || "").trim();
    const age = ageRaw ? Number(ageRaw) : null;

    try {
      await apiFetch("/auth/register", {
        method: "POST",
        body: {
          role: formData.get("role"),
          name: formData.get("name"),
          mobile: formData.get("mobile"),
          password: formData.get("password"),
          community: formData.get("community"),
          bio: formData.get("bio"),
          age,
          gender: formData.get("gender") || null,
          contactPhone: formData.get("contactPhone") || null,
          verificationMaterials: formData.get("verificationMaterials") || null,
        },
      });

      clearSession();
      showFeedback("#register-feedback", "注册成功，正在跳转到登录页...", "success");
      const mobile = encodeURIComponent(String(formData.get("mobile") || ""));
      window.setTimeout(() => {
        window.location.href = `/login.html?from=register&mobile=${mobile}`;
      }, 800);
    } catch (error) {
      showFeedback("#register-feedback", error.message, "error");
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = originalText;
      }
    }
  }

  if (volunteerForm) volunteerForm.addEventListener("submit", handleRegisterSubmit);
  if (organizationForm) organizationForm.addEventListener("submit", handleRegisterSubmit);
}
function buildActivityAction(item, session) {
  const detailLink = `<a class="button button-light button-small" href="${activityDetailUrl(item.id)}">详情</a>`;
  if (!session || session.user.role !== "volunteer") {
    return detailLink;
  }

  if (item.joined) {
    return `${detailLink}<button class="button button-muted button-small" type="button" disabled>已报名</button>`;
  }

  if (!session.user.profileCompleted) {
    return `${detailLink}<button class="button button-muted button-small" type="button" disabled>完善资料后可报名</button>`;
  }

  if (item.status !== "OPEN") {
    return `${detailLink}<button class="button button-muted button-small" type="button" disabled>${activityStatusLabel(item.status)}</button>`;
  }

  return `${detailLink}<button class="button button-primary button-small" type="button" data-enroll-id="${item.id}">报名</button>`;
}

function renderActivityCards(container, items, session, emptyMessage, withManageAction = false) {
  if (!container) {
    return;
  }

  container.innerHTML = "";
  if (!items?.length) {
    container.appendChild(createEmptyState(emptyMessage));
    return;
  }

  items.forEach((item) => {
    const card = document.createElement("article");
    card.className = "activity-card";

    const manageAction = withManageAction
      ? `<button class="button button-primary button-small" type="button" data-edit-id="${item.id}">维护</button>`
      : "";

    card.innerHTML = `
      <a class="activity-cover" href="${activityDetailUrl(item.id)}">
        <img src="${getCoverSrc(item)}" alt="${item.title}" />
      </a>
      <div class="activity-content">
        <h3><a href="${activityDetailUrl(item.id)}">${item.title}</a></h3>
        <p>${item.category} · ${item.location}</p>
        <span class="item-meta">${item.schedule}</span>
        <div class="activity-meta-row">
          <span class="chip">${activityStatusLabel(item.status)}</span>
          <span class="item-meta">${item.enrolled ?? 0}/${item.capacity ?? 0} 人</span>
        </div>
        <div class="activity-actions">
          ${buildActivityAction(item, session)}
          ${manageAction}
        </div>
      </div>
    `;
    container.appendChild(card);
  });
}

function bindEnrollActions() {
  qsa("[data-enroll-id]").forEach((button) => {
    button.addEventListener("click", async () => {
      const id = button.dataset.enrollId;
      button.disabled = true;
      try {
        await apiFetch(`/activities/${id}/enroll`, { method: "POST" });
        window.location.reload();
      } catch (error) {
        button.disabled = false;
        window.alert(error.message);
      }
    });
  });
}

async function initActivitiesPage() {
  const session = getValidSessionShape();
  if (!session) {
    window.location.href = "/login.html";
    return;
  }

  const actions = qs("#activity-page-actions");
  if (actions) {
    if (session.user.role === "organization") {
      actions.innerHTML = `
        <a class="button button-primary" href="/publish.html">发起活动</a>
        <a class="button button-light" href="/manage-activities.html">活动维护</a>
      `;
    } else {
      actions.innerHTML = `<a class="button button-light" href="/dashboard.html">进入工作台</a>`;
    }
  }

  const state = { page: 1, category: "", keyword: "" };
  const categorySelect = qs("#activity-category");
  const keywordInput = qs("#activity-keyword");

  async function load() {
    const params = new URLSearchParams({
      page: String(state.page),
      pageSize: "6",
    });
    if (state.category) {
      params.set("category", state.category);
    }
    if (state.keyword) {
      params.set("keyword", state.keyword);
    }

    const data = await apiFetch(`/activities?${params.toString()}`);

    if (categorySelect && !categorySelect.dataset.loaded) {
      (data.categories || []).forEach((category) => {
        const option = document.createElement("option");
        option.value = category;
        option.textContent = category;
        categorySelect.appendChild(option);
      });
      categorySelect.dataset.loaded = "true";
    }

    renderActivityCards(qs("#activity-list"), data.items || [], session, "暂无活动");
    bindEnrollActions();
    renderPagination(qs("#activity-pagination"), data.page, data.pageSize, data.total, (nextPage) => {
      state.page = nextPage;
      load().catch((error) => showFeedback("#activity-pagination", error.message, "error"));
    });
  }

  if (categorySelect) {
    categorySelect.addEventListener("change", () => {
      state.category = categorySelect.value;
      state.page = 1;
      load().catch((error) => showFeedback("#activity-pagination", error.message, "error"));
    });
  }

  if (keywordInput) {
    keywordInput.addEventListener("input", () => {
      state.keyword = keywordInput.value.trim();
      state.page = 1;
      load().catch((error) => showFeedback("#activity-pagination", error.message, "error"));
    });
  }

  await load();
}

async function initActivityDetailPage() {
  const session = getValidSessionShape();
  if (!session) {
    window.location.href = "/login.html";
    return;
  }

  const panel = qs("#activity-detail-panel");
  if (!panel) {
    return;
  }

  const params = new URLSearchParams(window.location.search);
  const id = Number(params.get("id"));
  if (!id) {
    panel.appendChild(createEmptyState("活动参数无效"));
    return;
  }

  const detail = await apiFetch(`/activities/${id}`);

  const canEnroll = session.user.role === "volunteer"
    && !detail.joined
    && session.user.profileCompleted
    && detail.statusCode === "PUBLISHED"
    && (detail.enrolled ?? 0) < (detail.capacity ?? 0);

  const enrollAction = canEnroll
    ? `<button class="button button-primary" type="button" id="detail-enroll-btn" data-enroll-id="${detail.id}">立即报名</button>`
    : `<button class="button button-muted" type="button" disabled>${detail.joined ? "已报名" : (session.user.role === "volunteer" && !session.user.profileCompleted ? "完善资料后可报名" : activityStatusLabel(detail.status))}</button>`;

  const editAction = detail.editable
    ? `<a class="button button-light" href="/manage-activities.html?id=${detail.id}">维护活动</a>`
    : "";

  panel.innerHTML = `
    <div class="activity-detail-cover">
      <img src="${getCoverSrc(detail)}" alt="${detail.title}" />
    </div>
    <div class="activity-detail-body">
      <div class="panel-head">
        <h2>${detail.title}</h2>
        <span class="chip">${activityStatusLabel(detail.status)}</span>
      </div>
      <div class="detail-meta-grid">
        <div><span class="item-meta">分类</span><strong>${detail.category}</strong></div>
        <div><span class="item-meta">时间</span><strong>${detail.schedule}</strong></div>
        <div><span class="item-meta">地点</span><strong>${detail.location}</strong></div>
        <div><span class="item-meta">人数</span><strong>${detail.enrolled}/${detail.capacity}</strong></div>
        <div><span class="item-meta">组织方</span><strong>${detail.organizerName}</strong></div>
        <div><span class="item-meta">联系方式</span><strong>${detail.contactMobile}</strong></div>
      </div>
      <div class="detail-description">
        <h3>活动说明</h3>
        <p>${detail.description}</p>
      </div>
      <div class="hero-actions">
        ${enrollAction}
        ${editAction}
        <a class="button button-light" href="/activities.html">返回活动中心</a>
      </div>
      <div id="detail-feedback" class="feedback" hidden></div>
    </div>
  `;

  const enrollBtn = qs("#detail-enroll-btn");
  if (enrollBtn) {
    enrollBtn.addEventListener("click", async () => {
      enrollBtn.disabled = true;
      try {
        await apiFetch(`/activities/${detail.id}/enroll`, { method: "POST" });
        showFeedback("#detail-feedback", "报名成功", "success");
        window.setTimeout(() => window.location.reload(), 500);
      } catch (error) {
        enrollBtn.disabled = false;
        showFeedback("#detail-feedback", error.message, "error");
      }
    });
  }
}

async function initDashboardPage() {
  const data = await apiFetch("/dashboard");
  const actions = qs("#dashboard-actions");

  const userCard = qs("#dashboard-user");
  if (userCard) {
    userCard.innerHTML = `
      <strong>${data.user.name}</strong>
      <span>${data.user.community}</span>
      <span>${roleLabel(data.user.role)}</span>
    `;
  }

  if (actions) {
    if (data.user.role === "organization") {
      actions.innerHTML = `
        <a class="button button-primary" href="/publish.html">新建活动申请</a>
        <a class="button button-light" href="/manage-activities.html">活动维护</a>
      `;
    } else {
      actions.innerHTML = `<a class="button button-primary" href="/activities.html">查看活动</a>`;
    }
  }

  renderStats(qs("#dashboard-stats"), data.stats || []);

  const [sectionA, sectionB] = data.sections || [];

  const sectionATitle = qs("#dashboard-section-a-title");
  const sectionALink = qs("#dashboard-section-a-link");
  const sectionBTitle = qs("#dashboard-section-b-title");
  const sectionBLink = qs("#dashboard-section-b-link");

  if (sectionATitle) {
    sectionATitle.textContent = sectionA?.title || "列表";
  }
  if (sectionALink) {
    sectionALink.textContent = sectionA?.actionLabel || "更多";
    sectionALink.href = sectionA?.actionPath || "/dashboard.html";
  }
  if (sectionBTitle) {
    sectionBTitle.textContent = sectionB?.title || "列表";
  }
  if (sectionBLink) {
    sectionBLink.textContent = sectionB?.actionLabel || "更多";
    sectionBLink.href = sectionB?.actionPath || "/dashboard.html";
  }

  renderList(
    qs("#dashboard-section-a"),
    sectionA?.items || [],
    (item) => `
      <div class="item-main">
        <h3>${item.title}</h3>
        <p>${item.detail}</p>
      </div>
      <div class="item-side">
        <span class="item-meta">${item.meta}</span>
        <span class="chip">${item.status}</span>
      </div>
    `,
    "暂无数据",
  );

  renderList(
    qs("#dashboard-section-b"),
    sectionB?.items || [],
    (item) => `
      <div class="item-main">
        <h3>${item.title}</h3>
        <p>${item.detail}</p>
      </div>
      <div class="item-side">
        <span class="item-meta">${item.meta}</span>
        <span class="chip">${item.status}</span>
      </div>
    `,
    "暂无数据",
  );

  renderList(
    qs("#dashboard-notices"),
    data.notices || [],
    (item) => `
      <div class="item-main">
        <h3>${item.title}</h3>
        <p>${item.content}</p>
      </div>
      <div class="item-side">
        <span class="item-meta">${item.publishedAt}</span>
        <span class="chip">${item.level}</span>
      </div>
    `,
    "暂无通知",
  );
}

async function initProfilePage() {
  const session = getValidSessionShape();
  if (!session) {
    window.location.href = "/login.html";
    return;
  }

  const form = qs("#profile-form");
  if (!form) {
    return;
  }

  const profile = await apiFetch("/profile");
  const proofField = qs("#profile-proof-field");
  if (proofField) {
    proofField.hidden = profile.role !== "organization";
  }

  qs('input[name="name"]', form).value = profile.name || "";
  qs('input[name="community"]', form).value = profile.community || "";
  qs('textarea[name="bio"]', form).value = profile.bio || "";
  qs('input[name="age"]', form).value = profile.age ?? "";
  qs('select[name="gender"]', form).value = profile.gender || "";
  qs('input[name="contactPhone"]', form).value = profile.contactPhone || "";
  const proofInput = qs('textarea[name="verificationMaterials"]', form);
  if (proofInput) {
    proofInput.value = profile.verificationMaterials || "";
  }

  const statusBox = qs("#profile-complete-status");
  if (statusBox) {
    statusBox.textContent = profile.profileCompleted ? "资料完整，可报名活动" : "资料未完整，暂不可报名活动";
    statusBox.className = `chip ${profile.profileCompleted ? "" : "chip-warn"}`;
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideFeedback("#profile-feedback");
    const formData = new FormData(event.currentTarget);
    const submitButton = qs('button[type="submit"]', form);
    if (submitButton) {
      submitButton.disabled = true;
      submitButton.textContent = "保存中...";
    }

    const ageRaw = String(formData.get("age") || "").trim();
    const age = ageRaw ? Number(ageRaw) : null;

    try {
      const updated = await apiFetch("/profile", {
        method: "PUT",
        body: {
          name: formData.get("name"),
          community: formData.get("community"),
          bio: formData.get("bio"),
          age,
          gender: formData.get("gender"),
          contactPhone: formData.get("contactPhone"),
          verificationMaterials: formData.get("verificationMaterials"),
        },
      });

      const currentSession = getValidSessionShape();
      if (currentSession?.user) {
        currentSession.user.name = updated.name;
        currentSession.user.community = updated.community;
        currentSession.user.status = updated.status;
        currentSession.user.profileCompleted = updated.profileCompleted;
        saveSession(currentSession);
      }

      if (statusBox) {
        statusBox.textContent = updated.profileCompleted ? "资料完整，可报名活动" : "资料未完整，暂不可报名活动";
        statusBox.className = `chip ${updated.profileCompleted ? "" : "chip-warn"}`;
      }
      showFeedback("#profile-feedback", "资料已更新", "success");
    } catch (error) {
      showFeedback("#profile-feedback", error.message, "error");
    } finally {
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = "保存资料";
      }
    }
  });
}

async function initAdminDashboardPage() {
  const session = getValidSessionShape();
  if (!session || session.user.role !== "admin") {
    window.location.href = "/admin-login.html";
    return;
  }

  const orgRoot = qs("#admin-org-list");
  const proposalRoot = qs("#admin-proposal-list");
  const activityRoot = qs("#admin-activity-list");
  const userRoot = qs("#admin-user-list");
  const userRoleFilter = qs("#admin-user-role-filter");
  const noticeForm = qs("#admin-notice-form");
  const noticesListRoot = qs("#admin-notices-list");

  // ---- 组织者审核 & 活动申请审核 ----
  async function loadReviews() {
    if (orgRoot) {
      const orgs = await apiFetch("/admin/reviews/organizations?status=PENDING_REVIEW");
      renderList(
        orgRoot,
        orgs || [],
        (item) => `
          <div class="item-main">
            <h3>${item.name}</h3>
            <p>${item.community} · ${item.mobile}</p>
            <p class="item-meta">${item.verificationMaterials || "未提供资质说明"}</p>
          </div>
          <div class="item-side item-side-wide">
            <span class="item-meta">${item.submittedAt}</span>
            <div class="toolbar-actions">
              <button class="button button-primary button-small" type="button" data-org-review="${item.id}" data-approved="true">通过</button>
              <button class="button button-light button-small" type="button" data-org-review="${item.id}" data-approved="false">驳回</button>
            </div>
          </div>
        `,
        "暂无待审核组织者",
      );

      qsa("[data-org-review]").forEach((button) => {
        button.addEventListener("click", async () => {
          const userId = Number(button.dataset.orgReview);
          const approved = button.dataset.approved === "true";
          button.disabled = true;
          try {
            await apiFetch(`/admin/reviews/organizations/${userId}`, {
              method: "POST",
              body: { approved, reviewNote: approved ? "资质审核通过" : "资质审核未通过" },
            });
            await loadReviews();
          } catch (error) {
            button.disabled = false;
            window.alert(error.message);
          }
        });
      });
    }

    if (proposalRoot) {
      const proposals = await apiFetch("/admin/reviews/proposals?status=REVIEWING");
      renderList(
        proposalRoot,
        proposals || [],
        (item) => `
          <div class="item-main">
            <h3>${item.title}</h3>
            <p>${item.organizationName} · ${item.category} · ${item.location}</p>
            <p class="item-meta">${item.startAt} → ${item.endAt}</p>
          </div>
          <div class="item-side item-side-wide">
            <span class="item-meta">${item.submittedAt}</span>
            <div class="toolbar-actions">
              <button class="button button-primary button-small" type="button" data-proposal-review="${item.id}" data-approved="true">发布</button>
              <button class="button button-light button-small" type="button" data-proposal-review="${item.id}" data-approved="false">驳回</button>
            </div>
          </div>
        `,
        "暂无待审核活动申请",
      );

      qsa("[data-proposal-review]").forEach((button) => {
        button.addEventListener("click", async () => {
          const proposalId = Number(button.dataset.proposalReview);
          const approved = button.dataset.approved === "true";
          button.disabled = true;
          try {
            await apiFetch(`/admin/reviews/proposals/${proposalId}`, {
              method: "POST",
              body: { approved, reviewNote: approved ? "活动申请审核通过并发布" : "活动申请审核未通过" },
            });
            await loadReviews();
          } catch (error) {
            button.disabled = false;
            window.alert(error.message);
          }
        });
      });
    }
  }

  // ---- 活动管理（关闭不合规活动）----
  async function loadActivities() {
    if (!activityRoot) return;
    const data = await apiFetch("/activities?page=1&pageSize=20");
    const items = data?.items || [];
    renderList(
      activityRoot,
      items,
      (item) => `
        <div class="item-main">
          <h3>${item.title}</h3>
          <p>${item.category} · ${item.location} · ${item.schedule}</p>
          <p class="item-meta">已报名 ${item.enrolled ?? 0}/${item.capacity ?? 0} 人 · 状态：${activityStatusLabel(item.status)}</p>
        </div>
        <div class="item-side">
          <a class="button button-light button-small" href="${activityDetailUrl(item.id)}">详情</a>
          ${item.status !== "CLOSED" ? `<button class="button button-warn button-small" type="button" data-delete-activity="${item.id}">关闭活动</button>` : '<span class="chip chip-warn">已关闭</span>'}
        </div>
      `,
      "暂无活动",
    );

    qsa("[data-delete-activity]").forEach((button) => {
      button.addEventListener("click", async () => {
        if (!window.confirm("确定要关闭该活动吗？此操作将立即叫停报名。")) return;
        button.disabled = true;
        try {
          await apiFetch(`/admin/activities/${button.dataset.deleteActivity}`, { method: "DELETE" });
          await loadActivities();
        } catch (error) {
          button.disabled = false;
          window.alert(error.message);
        }
      });
    });
  }

  // ---- 用户管理（删除用户）----
  async function loadUsers(role = "") {
    if (!userRoot) return;
    const params = new URLSearchParams({ limit: "50" });
    if (role) params.set("role", role);
    const users = await apiFetch(`/admin/users?${params.toString()}`);
    renderList(
      userRoot,
      users || [],
      (item) => `
        <div class="item-main">
          <h3>${item.name} <span class="item-meta">${item.mobile}</span></h3>
          <p>${roleLabel(item.role)} · ${item.community} · 注册于 ${item.createdAt}</p>
          <p class="item-meta">状态：${item.status}</p>
        </div>
        <div class="item-side">
          <button class="button button-warn button-small" type="button" data-delete-user="${item.id}" data-user-name="${item.name}">删除账号</button>
        </div>
      `,
      "暂无用户",
    );

    qsa("[data-delete-user]").forEach((button) => {
      button.addEventListener("click", async () => {
        const name = button.dataset.userName;
        if (!window.confirm(`确定要删除用户「${name}」吗？此操作不可撤销。`)) return;
        button.disabled = true;
        try {
          await apiFetch(`/admin/users/${button.dataset.deleteUser}`, { method: "DELETE" });
          await loadUsers(userRoleFilter ? userRoleFilter.value : "");
        } catch (error) {
          button.disabled = false;
          window.alert(error.message);
        }
      });
    });
  }

  if (userRoleFilter) {
    userRoleFilter.addEventListener("change", () => loadUsers(userRoleFilter.value));
  }

  // ---- 发布平台公告 ----
  let currentNoticePage = 1;
  const noticesPerPage = 20;
  let allNotices = [];

  async function loadNotices(page = 1) {
    if (!noticesListRoot) return;
    currentNoticePage = page;

    const notices = await apiFetch(`/admin/notices?limit=${noticesPerPage}`);
    allNotices = notices || [];
    renderNotices();
  }

  function renderNotices() {
    if (!noticesListRoot) return;

    const start = (currentNoticePage - 1) * noticesPerPage;
    const end = start + noticesPerPage;
    const pageNotices = allNotices.slice(start, end);

    renderList(
      noticesListRoot,
      pageNotices,
      (item) => `
        <div class="item-main">
          <h3>${item.title} <span class="chip">${item.level}</span></h3>
          <p>${item.content}</p>
        </div>
        <div class="item-side">
          <span class="item-meta">${item.publishedAt}</span>
          <span class="chip">${item.audience}</span>
          <button class="button button-warn" data-notice-id="${item.id}">删除</button>
        </div>
      `,
      "暂无公告",
    );

    renderPagination("admin-notices-pagination", currentNoticePage, noticesPerPage, allNotices.length, loadNotices);
    attachDeleteNoticeButtons();
  }

  function attachDeleteNoticeButtons() {
    const deleteButtons = noticesListRoot.querySelectorAll('[data-notice-id]');
    deleteButtons.forEach(btn => {
      btn.addEventListener('click', async () => {
        const noticeId = btn.getAttribute('data-notice-id');
        if (!confirm('确定要删除这条公告吗？')) return;

        try {
          await apiFetch(`/admin/notices/${noticeId}`, { method: 'DELETE' });
          await loadNotices(currentNoticePage);
        } catch (error) {
          alert('删除失败：' + error.message);
        }
      });
    });
  }

  if (noticeForm) {
    noticeForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      hideFeedback("#admin-notice-feedback");
      const formData = new FormData(event.currentTarget);
      const submitBtn = qs('button[type="submit"]', noticeForm);
      if (submitBtn) { submitBtn.disabled = true; submitBtn.textContent = "发布中..."; }

      try {
        await apiFetch("/admin/notices", {
          method: "POST",
          body: {
            title: formData.get("title"),
            content: formData.get("content"),
            audience: formData.get("audience"),
            level: formData.get("level"),
          },
        });
        showFeedback("#admin-notice-feedback", "公告已发布，所有用户可见", "success");
        noticeForm.reset();
        await loadNotices(1);
      } catch (error) {
        showFeedback("#admin-notice-feedback", error.message, "error");
      } finally {
        if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = "发布公告"; }
      }
    });
  }

  await Promise.all([loadReviews(), loadActivities(), loadUsers(), loadNotices()]);
}

async function initPublishPage() {
  const session = getValidSessionShape();
  const panel = qs(".panel-form");
  if (!session || session.user.role !== "organization") {
    if (panel) {
      panel.innerHTML = `<div class="empty-state">仅组织方可提交活动申请。</div>`;
    }
    return;
  }

  const form = qs("#publish-form");
  const coverInput = qs("#publish-cover-file");
  const coverPreview = qs("#publish-cover-preview");
  if (!form || !coverInput || !coverPreview) {
    return;
  }

  bindCoverPreview("#publish-cover-file", "#publish-cover-preview", "#publish-feedback");

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideFeedback("#publish-feedback");
    const formData = new FormData(event.currentTarget);
    const submitButton = qs('button[type="submit"]', form);
    if (submitButton) {
      submitButton.disabled = true;
      submitButton.textContent = "提交中...";
    }

    try {
      const file = coverInput.files?.[0];
      const coverImage = await readImageAsDataUrl(file);

      await apiFetch("/activities/proposals", {
        method: "POST",
        body: {
          title: formData.get("title"),
          category: formData.get("category"),
          location: formData.get("location"),
          startAt: formData.get("startAt"),
          endAt: formData.get("endAt"),
          capacity: Number(formData.get("capacity")),
          contactMobile: formData.get("contactMobile"),
          description: formData.get("description"),
          coverImage,
        },
      });

      showFeedback("#publish-feedback", "活动申请已提交", "success");
      form.reset();
      coverPreview.removeAttribute("src");
      coverPreview.classList.remove("is-ready");
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = "提交申请";
      }
    } catch (error) {
      showFeedback("#publish-feedback", error.message, "error");
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = "提交申请";
      }
    }
  });
}

async function initManageActivitiesPage() {
  const session = getValidSessionShape();
  if (!session || session.user.role !== "organization") {
    const page = qs(".page");
    if (page) {
      page.innerHTML = `<section class="panel"><div class="empty-state">仅组织方可维护活动。</div></section>`;
    }
    return;
  }

  const listRoot = qs("#manage-activity-list");
  const form = qs("#manage-form");
  const emptyBox = qs("#manage-empty");
  const coverInput = qs("#manage-cover-file");
  const coverPreview = qs("#manage-cover-preview");
  const noticeForm = qs("#manage-notice-form");
  if (!listRoot || !form || !emptyBox || !coverInput || !coverPreview) {
    return;
  }

  let currentId = null;
  let cache = [];

  function renderListItems(items) {
    renderActivityCards(listRoot, items, session, "暂无可维护的活动", true);
    qsa("[data-edit-id]", listRoot).forEach((button) => {
      button.addEventListener("click", () => {
        const id = Number(button.dataset.editId);
        loadDetailIntoForm(id).catch((error) => showFeedback("#manage-feedback", error.message, "error"));
      });
    });

    qsa(".activity-card", listRoot).forEach((card) => {
      const editButton = qs("[data-edit-id]", card);
      const active = editButton && Number(editButton.dataset.editId) === currentId;
      card.classList.toggle("is-selected", Boolean(active));
    });
  }

  async function loadList() {
    cache = await apiFetch("/activities/manage");
    renderListItems(cache);
  }

  async function loadDetailIntoForm(id) {
    const detail = await apiFetch(`/activities/${id}`);
    currentId = detail.id;

    form.hidden = false;
    emptyBox.hidden = true;
    if (noticeForm) {
      noticeForm.hidden = false;
    }
    hideFeedback("#manage-feedback");

    qs('input[name="activityId"]', form).value = String(detail.id);
    qs('input[name="coverImageCurrent"]', form).value = detail.coverImage || "";
    qs('input[name="title"]', form).value = detail.title || "";
    qs('input[name="category"]', form).value = detail.category || "";
    qs('input[name="location"]', form).value = detail.location || "";
    qs('input[name="contactMobile"]', form).value = detail.contactMobile || "";
    qs('input[name="startAt"]', form).value = detail.startAt || "";
    qs('input[name="endAt"]', form).value = detail.endAt || "";
    qs('input[name="capacity"]', form).value = String(detail.capacity || 1);
    qs('textarea[name="description"]', form).value = detail.description || "";
    if (noticeForm) {
      qs('input[name="activityId"]', noticeForm).value = String(detail.id);
    }
    coverInput.value = "";
    coverPreview.src = getCoverSrc(detail);
    coverPreview.classList.add("is-ready");

    renderListItems(cache);
  }

  bindCoverPreview("#manage-cover-file", "#manage-cover-preview", "#manage-feedback");

  if (noticeForm) {
    noticeForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      hideFeedback("#manage-notice-feedback");
      const formData = new FormData(event.currentTarget);
      const activityId = Number(formData.get("activityId"));
      if (!activityId) {
        showFeedback("#manage-notice-feedback", "请先选择活动", "error");
        return;
      }

      const submitButton = qs('button[type="submit"]', noticeForm);
      if (submitButton) {
        submitButton.disabled = true;
        submitButton.textContent = "发送中...";
      }

      try {
        const result = await apiFetch(`/activities/${activityId}/notifications`, {
          method: "POST",
          body: {
            title: formData.get("title"),
            content: formData.get("content"),
          },
        });

        showFeedback(
          "#manage-notice-feedback",
          `通知已发送，覆盖 ${result.deliveredCount ?? 0} 名志愿者`,
          "success",
        );
        noticeForm.reset();
        qs('input[name="activityId"]', noticeForm).value = String(activityId);
      } catch (error) {
        showFeedback("#manage-notice-feedback", error.message, "error");
      } finally {
        if (submitButton) {
          submitButton.disabled = false;
          submitButton.textContent = "发送通知";
        }
      }
    });
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideFeedback("#manage-feedback");
    const formData = new FormData(event.currentTarget);
    const activityId = Number(formData.get("activityId"));
    const submitButton = qs('button[type="submit"]', form);
    if (submitButton) {
      submitButton.disabled = true;
      submitButton.textContent = "保存中...";
    }

    try {
      let coverImage = String(formData.get("coverImageCurrent") || "");
      const file = coverInput.files?.[0];
      if (file) {
        coverImage = await readImageAsDataUrl(file);
      }

      const updated = await apiFetch(`/activities/${activityId}`, {
        method: "PUT",
        body: {
          title: formData.get("title"),
          category: formData.get("category"),
          location: formData.get("location"),
          startAt: formData.get("startAt"),
          endAt: formData.get("endAt"),
          capacity: Number(formData.get("capacity")),
          contactMobile: formData.get("contactMobile"),
          description: formData.get("description"),
          coverImage,
        },
      });

      showFeedback("#manage-feedback", "活动信息已更新", "success");
      qs('input[name="coverImageCurrent"]', form).value = updated.coverImage || coverImage;
      coverPreview.src = getCoverSrc(updated);
      coverPreview.classList.add("is-ready");
      coverInput.value = "";

      await loadList();
      currentId = activityId;
      renderListItems(cache);
    } catch (error) {
      showFeedback("#manage-feedback", error.message, "error");
    } finally {
      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = "保存更新";
      }
    }
  });

  await loadList();

  const urlId = Number(new URLSearchParams(window.location.search).get("id"));
  if (urlId) {
    await loadDetailIntoForm(urlId);
    return;
  }

  if (cache.length > 0) {
    await loadDetailIntoForm(cache[0].id);
  }
}

async function bootstrap() {
  try {
    const page = document.body?.dataset?.page;
    if (!page) {
      return;
    }

    if (page === "login" || page === "register" || page === "admin-login") {
      updateNav();
      if (page === "login") {
        initLoginPage();
      } else if (page === "register") {
        initRegisterPage();
      } else {
        initAdminLoginPage();
      }

      const hasValidSession = await validateSession();
      if (hasValidSession) {
        const session = getValidSessionShape();
        if (session?.user?.role === "admin") {
          window.location.href = "/admin-dashboard.html";
        } else {
          window.location.href = "/index.html";
        }
      }
      return;
    }

    const hasValidSession = await validateSession();
    if (!hasValidSession) {
      window.location.href = page === "admin-dashboard" ? "/admin-login.html" : "/login.html";
      return;
    }

    updateNav();

    if (page === "home") {
      await initHomePage();
      return;
    }
    if (page === "activities") {
      await initActivitiesPage();
      return;
    }
    if (page === "activity-detail") {
      await initActivityDetailPage();
      return;
    }
    if (page === "dashboard") {
      await initDashboardPage();
      return;
    }
    if (page === "profile") {
      await initProfilePage();
      return;
    }
    if (page === "admin-dashboard") {
      await initAdminDashboardPage();
      return;
    }
    if (page === "publish") {
      await initPublishPage();
      return;
    }
    if (page === "manage-activities") {
      await initManageActivitiesPage();
    }
  } catch (error) {
    console.error(error);
    const pageRoot = qs(".page");
    if (pageRoot) {
      const raw = error.message || "";
      let friendlyMessage = "页面加载失败，请刷新重试";
      if (raw.includes("401") || raw.toLowerCase().includes("unauthorized") || raw.includes("please login")) {
        friendlyMessage = "登录已过期，请重新登录";
      } else if (raw.includes("403") || raw.toLowerCase().includes("forbidden")) {
        friendlyMessage = "您没有权限访问此页面";
      } else if (raw.includes("503") || raw.includes("不可用") || raw.includes("unavailable")) {
        friendlyMessage = "服务暂时不可用，请稍后重试";
      } else if (raw.includes("404") || raw.toLowerCase().includes("not found")) {
        friendlyMessage = "请求的内容不存在";
      }
      pageRoot.appendChild(createEmptyState(friendlyMessage));
    }
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", () => {
    bootstrap();
  });
} else {
  bootstrap();
}


