# tech-toolkit

技术工具与实验项目的单仓库。协作约定（含 AI 代理）见根目录 [`AGENTS.md`](./AGENTS.md)；本文只做项目入口，不含命令。

## 项目：Admin Center 用户管理与会话管理

由两个可独立横向扩展的服务组成：

| 应用 | 目录 | 是什么 | 入口文档 |
|---|---|---|---|
| UAM（User Account Management） | [`apps/uam`](./apps/uam) | 用户、账号、会话的唯一权威；RESTful 领域服务（Java 21 / Spring Boot），**只识别调用它的服务（服务密钥），不基于用户会话** | [`apps/uam/AGENTS.md`](./apps/uam/AGENTS.md) |
| Admin Center | [`apps/admin-center`](./apps/admin-center) | 面向运维人员的接入层 Web Server（Next.js）：登录、User Manager、Session Manager；浏览器访问基于会话（cookie），自适应桌面 / 平板 / 手机 | [`apps/admin-center/AGENTS.md`](./apps/admin-center/AGENTS.md) |

UAM 的接口契约：[`contracts/uam/openapi.yaml`](./contracts/uam/openapi.yaml)。本地依赖、多实例编排与脚本在 [`infra/local/`](./infra/local)，压测与结果在 [`infra/load/`](./infra/load)。

**怎么跑起来**：安装、构建、测试、本地运行、覆盖率、环境变量与排障，一律以各应用的 `AGENTS.md` 为准（命令只写在那里）。

## 文档在哪

| 位置 | 内容 |
|---|---|
| [`docs/achieve-specs/`](./docs/achieve-specs) | **已落地**需求、领域与行为的最新真实状态（统一语言、领域模型、核心流程、需求对照与验证记录、已知限制）。本项目：[`admin-center-uam.md`](./docs/achieve-specs/admin-center-uam.md) |
| [`docs/system-design/`](./docs/system-design) | **已落地**架构与实现的最新真实状态（C4 视图、运行时与部署视图、数据存储、安全、扩展性、可观测性、质量门禁；图在 [`diagrams/`](./docs/system-design/diagrams)）。本项目：[`admin-center-uam.md`](./docs/system-design/admin-center-uam.md) |
| [`specs/`](./specs) | 新功能的**过程**文档（调研、澄清、方案、任务拆分）；功能完成后归档到上面两处。本项目：[`specs/002-admin-center-uam/`](./specs/002-admin-center-uam) |
| [`docs/best-practice/`](./docs/best-practice) | 通用实践，如 [测试类型与适用场景](./docs/best-practice/testing.md) |

界面设计原型（静态、可点击）：[`specs/002-admin-center-uam/prototype/`](./specs/002-admin-center-uam/prototype/README.md)。
