# Implementation Plan: auth

**Branch**: `feat/001-uam-oauth2-sas` | **Date**: 2026-11-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-uam-oauth2-sas/spec.md`

## Summary

在仓库根下新建 `auth/` 项目，它就是 auth 这一个 Spring Boot 服务：基于 Spring Authorization Server 的 OAuth 2.0 / OIDC 授权服务器（授权码 + PKCE 全流程、SSO、刷新轮换与重用检测、退出与 back-channel 通知），加用户凭据与锁定策略、client 管理 API（管理员专用）、审计。

**MVP 不含 auth-web（Portal BFF）**，留给后续特性；它加入时只是一个普通 client，auth 无需改动。

关键设计取舍见 [research.md](research.md)：会话统一在 auth（SSO 会话 + 刷新令牌绑定会话 + 自实现的 back-channel 退出通知）、管理 API 用 `auth.admin` scope 加实时管理员校验。

## 设计与实现的参考基准

实现遵循主流的标准与官方做法，**不自创协议行为**。遇到框架没有现成支持的部分（见 research.md D8、D9），也按下列标准实现，并在代码注释里写明所依据的条款。

| 方面 | 参考基准 |
|---|---|
| 授权服务器配置 | Spring Authorization Server / Spring Security 7 官方 reference 与官方 samples（demo authorization server）；优先用框架提供的扩展点，不重写其端点 |
| 协议安全 | RFC 9700（OAuth 2.0 Security Best Current Practice）：授权码 + PKCE（S256）、精确匹配回调、刷新令牌轮换与重用检测、不用隐式与密码模式 |
| 访问令牌 | RFC 9068（JWT Profile for OAuth 2.0 Access Tokens）：`typ: at+jwt`、`client_id`、`aud` |
| OIDC | OIDC Core、RP-Initiated Logout、Back-Channel Logout 1.0、Discovery |
| BFF 模式 | IETF 草案 *OAuth 2.0 for Browser-Based Applications* 的 BFF 章节：令牌只在服务端，浏览器只持会话 cookie |
| 密码与会话 | OWASP ASVS（V2/V3）、OWASP Password Storage 与 Authentication Cheat Sheet、NIST SP 800-63B（以长度和弱密码黑名单为主，不强制复杂度与定期改密） |
| 错误响应 | RFC 6749 标准错误；管理 API 用 RFC 9457 problem details |
| 对照实现 | 行为存疑时对照 Keycloak 等成熟 IdP（锁定、退出传播、密钥轮换的处理方式）；有差异须在 research.md 写明原因 |

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1.x（Spring Security 7，SAS 已并入 Security 7）、Spring Session JDBC、BouncyCastle（argon2id）、Flyway、Thymeleaf（登录页、改密页、错误页）

**Storage**: PostgreSQL 18（Flyway）；不引入 Redis

**Testing**: JUnit 6 + Testcontainers 2（PostgreSQL）、MockMvc / TestRestTemplate 走完整授权码流程、ArchUnit、JaCoCo、Spotless

**Target Platform**: Linux 容器（Kubernetes），服务无状态，可水平扩展

**Project Type**: web-service（auth），单模块 Maven 项目

**Performance Goals**: 500 并发登录时 95% 在 2 秒内完成（SC-004）；退出后 10 秒内各应用登录状态失效（SC-009）

**Constraints**: 令牌不含个人资料；私钥不入库不入镜像不入日志；所有重定向地址精确匹配；单一用户池（不含租户）

**Scale/Scope**: 3 个已知 client（App1/2/3）加运维工具；用户规模为内部平台量级（万级以内）

## Constitution Check

仓库无 speckit constitution 文件内容，门禁以根 [`AGENTS.md`](../../AGENTS.md) 为准（团队约定，见记忆“宪章即 AGENTS.md”）。

| 门禁 | 结论 | 说明 |
|---|---|---|
| 范围与最小改动 | 通过 | 只新增 `auth/` 与 `specs/001-uam-oauth2-sas/`，不动其他目录 |
| 规格先于实现 | 通过 | spec 经 clarify 确认；plan 阶段发现的 spec 冲突（刷新令牌有效期）已先改 spec，见下 |
| 测试先行 | 通过（需执行时遵守） | tasks 必须把每个行为的失败测试排在实现之前 |
| 覆盖率 ≥ 90%（行） | 通过（计划） | 每个模块 JaCoCo；排除仅限 `*Application` 入口，其余须在 PR 说明 |
| 契约优先 | 通过 | [contracts/](contracts/) 先于实现；管理 API 用 OpenAPI |
| 安全与秘密 | 通过（计划） | 密钥只来自环境变量或挂载文件；日志与审计无密码、令牌 |
| 子项目文档约定 | **待办** | `auth/` 需 `AGENTS.md` + `CLAUDE.md`（内容 `@AGENTS.md`），列技术栈、命令、覆盖率口径、环境变量；列入 tasks 脚手架阶段 |
| 设计原则（SOLID / YAGNI） | 通过 | 不建共享库、独立 CLI、Redis，见 research.md D2、D3 |
| 参考主流设计与实现 | 通过 | 见上节“设计与实现的参考基准”，tasks 与评审按它核对 |

**Phase 1 设计后复核**：无新增违规。

### 本阶段对 spec 的修订（需你确认）

- `Assumptions` 中“刷新令牌默认数天”与 FR-014a（任何应用的登录状态不得长于 auth 会话）冲突，已改为“刷新令牌绑定 SSO 会话，实际寿命不超过 SSO 绝对期”。

## Project Structure

### Documentation (this feature)

```text
specs/001-uam-oauth2-sas/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── oauth2-endpoints.md     # OAuth2 / OIDC 端点约束与自定义行为
│   ├── admin-api.yaml          # 管理 API（OpenAPI 3.1）
│   └── app-integration.md      # App BFF 接入约定
├── checklists/requirements.md
└── tasks.md                    # 由 /speckit-tasks 生成
```

### Source Code (repository root)

```text
auth/
├── AGENTS.md / CLAUDE.md
├── pom.xml                      # 依赖版本、JaCoCo、Spotless
├── .env.example
├── docker-compose.yml           # 本地 PostgreSQL
├── src/main/java/…/
│   ├── account/                 # 用户、密码策略、锁定、改密
│   ├── client/                  # client 登记与扩展字段
│   ├── oauth/                   # SAS 配置、令牌定制、重用检测、会话绑定
│   ├── session/                 # SSO 会话、绝对期过滤器、auth_sso_client
│   ├── logout/                  # 退出、back-channel 投递与重试
│   ├── admin/                   # 管理 API（控制器与鉴权）
│   ├── audit/                   # 审计事件
│   ├── bootstrap/               # 首个管理员的一次性命令
│   ├── cleanup/                 # 过期数据的定时清理
│   └── config/                  # 数据源等基础配置
├── src/main/resources/
│   ├── db/migration/            # Flyway
│   └── templates/               # 登录页、改密页、错误页
└── src/test/…                   # *Test 单元、*IT 集成与契约
```

**Structure Decision**: `auth/` 是单个 Maven 项目、单个 Spring Boot 应用，不分子模块；将来加 auth-web 时再拆为多模块。package 之间不循环依赖，由 ArchUnit 约束（沿用 `backend` 的做法）。

## 命名约定

沿用 `main` 上 backend 的统一语言：类、表、字段、API、测试名使用同一英文术语。本特性的术语：User、username、Credential、Client、SSO Session（`sid`）、Authorization、Refresh Token、Back-channel Logout、Signing Key、Audit Event。新增业务概念先登记于此。

## 风险与待确认

| 风险 / 事项 | 影响 | 处理 |
|---|---|---|
| back-channel 通知依赖送达，SC-009 的 10 秒在送达失败时退化为最多 10 分钟（访问令牌有效期） | 退出后短时间内某个应用仍可用 | 已有刷新令牌删除作为兜底；如要更严可缩短访问令牌有效期，请评审时决定 |
| Boot 4 下 SAS starter 的实际坐标与版本 | 仅影响 POM | tasks 脚手架第一项核实 |
| 进程内限流在多实例下按实例计算 | 总量放宽为实例数倍 | 已登记为已知限制，网关限流为后续 |
| auth-web（Portal BFF）后移 | MVP 各 App BFF 直接向 auth 授权，没有统一入口 | 后续特性加入；它只是普通 client，auth 不需改动（research.md D10、D15 保留设计备查） |
| 与 `main` 上多租户 UAM（tenant-core）并存 | 概念重叠 | 本期为单一用户池；两者的合并或迁移另立特性 |
