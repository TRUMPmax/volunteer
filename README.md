# 社区志愿者服务平台

这是一个根据《基于微服务的社区志愿者服务平台》开题报告重构后的演示工程，目标是让项目结构、模块边界、页面表达和技术栈都更贴近毕业设计方案。

当前版本采用的实现方案：

- 前端门户：原生 Web 页面，可用本地静态服务直接启动
- 网关：Spring Cloud Gateway
- 业务服务：Spring Boot + JDBC
- 数据库：MySQL 8
- 本地开发：PowerShell 启动脚本

## 对照开题报告的模块落点

- 用户管理模块：`services/user-service`
- 活动申请管理模块：`services/activity-service`
- 志愿活动管理模块：`services/activity-service`
- 活动报名与服务记录管理模块：`services/volunteer-service`
- 内容互动与公告管理模块：`services/community-service`
- 积分商城与兑换管理模块：`services/volunteer-service`
- 统计分析模块：`gateway`

## 项目结构

```text
volunteer/
├─ apps/
│  └─ web/
├─ gateway/
├─ infrastructure/
│  └─ mysql/
├─ services/
│  ├─ user-service/
│  ├─ volunteer-service/
│  ├─ activity-service/
│  └─ community-service/
├─ docs/
│  └─ architecture.md
├─ docker-compose.yml
└─ scripts/
   └─ local/
```

## 本地开发启动方式

在项目根目录执行：

```bash
copy .env.local.example .env.local
.\scripts\local\init-mysql.ps1
.\scripts\local\start-local.ps1
```

先把项目根目录的 `.env.local` 改成你自己的 MySQL 信息：

```env
LOCAL_MYSQL_HOST=127.0.0.1
LOCAL_MYSQL_PORT=3306
LOCAL_MYSQL_USER=your_mysql_user
LOCAL_MYSQL_PASSWORD=your_mysql_password
```

启动后访问：

- 门户首页：`http://127.0.0.1:5500`
- 网关接口：`http://localhost:8000/api/portal`
- 用户服务健康检查：`http://localhost:8001/actuator/health`
- 志愿者服务健康检查：`http://localhost:8002/actuator/health`
- 活动服务健康检查：`http://localhost:8003/actuator/health`
- 社区内容服务健康检查：`http://localhost:8004/actuator/health`
- MySQL：`localhost:3306`

本地脚本会优先读取项目根目录的 `.env.local`。

- 已配置 `.env.local`：直接启动，不会再反复输入
- 没配置 `.env.local`：脚本才会退回到交互输入
- 如果你想临时覆盖，也可以显式传参：

```bash
.\scripts\local\init-mysql.ps1 -User your_user -Password your_password
.\scripts\local\start-local.ps1 -MySqlUser your_user -MySqlPassword your_password
```

停止本地服务：

```bash
.\scripts\local\stop-local.ps1
```

## MySQL 是怎么连接的

每个服务都通过 Spring Boot 的 `spring.datasource` 连接本地 MySQL，只是连接的数据库名不同：

- `user-service` -> `volunteer_user_db`
- `volunteer-service` -> `volunteer_volunteer_db`
- `activity-service` -> `volunteer_activity_db`
- `community-service` -> `volunteer_community_db`

连接参数来自这些环境变量：

- `LOCAL_MYSQL_HOST`
- `LOCAL_MYSQL_PORT`
- `LOCAL_MYSQL_USER`
- `LOCAL_MYSQL_PASSWORD`

对应配置文件可以看：

- `services/*/src/main/resources/application.yml`

如果你以后想改成自己的账号密码，直接改环境变量或启动参数就可以，不需要改 Java 代码。

## Docker

仓库里还保留了 `docker-compose.yml`，只是作为可选方案，不再是默认开发方式。

## 当前版本实现了什么

- 用 MySQL 初始化脚本提供演示数据，避免首页只剩静态文案
- 用网关聚合各服务快照，前端只请求一个门户接口
- 把首页重做为更正式、克制的答辩式展示页面
- 按开题报告中的核心模块组织数据和页面结构

## 后续可以继续补的方向

- 登录认证、JWT、RBAC 权限控制
- 真正的活动申请审批流和状态流转
- 积分规则配置、订单发货流程、地址管理
- 评论审核、收藏、论坛发帖等交互接口
- Spring Cloud 注册配置中心、消息队列、缓存与监控体系

更详细的说明见 [docs/architecture.md](./docs/architecture.md)。
