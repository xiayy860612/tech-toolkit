# system-design — 架构与实现的现状归档

归档规则见根 [`AGENTS.md`](../../AGENTS.md)「Project Context」：`specs/<功能>/` 完成后，除 `spec.md` 外的内容（`plan.md`、`research.md`、`data-model.md`、`quickstart.md`、`tasks.md`、检查清单、路由契约、架构图、界面原型）按 app 拆分到本目录的对应子目录；跨 app 的内容放 `system/`。需求侧见 [`../achieve-specs/`](../achieve-specs/README.md)。

| 目录 | 范围 | 内容 |
|---|---|---|
| [`system/`](./system/README.md) | 跨服务 | C4 L1/L2、运行时视图、部署视图、来源地址信任模型、跨服务安全、扩展性；`plan.md`、`research.md`、`quickstart.md`、`tasks.md`、`checklists/`、[`technical-requirements.md`](./system/technical-requirements.md)（从需求规格移出的技术性条款）、[`traceability.md`](./system/traceability.md)（需求对照、验证记录、已知限制）；`diagrams/`（c4-l1/l2、seq-*） |
| [`uam/`](./uam/README.md) | `apps/uam` | L3 组件、领域模型、数据存储（PostgreSQL / Redis / Lua）、安全、可观测性、CI 门禁；`plan.md`、`research.md`、`data-model.md`、[`domain-and-api.md`](./uam/domain-and-api.md)（领域模型、接口与权限、业务流程）；`diagrams/`（c4-l3、domain-*） |
| [`admin-center/`](./admin-center/README.md) | `apps/admin-center` | L3 组件、安全、可观测性、CI 门禁；`plan.md`、`research.md`、`web-routes.md`（页面 / 路由 / cookie / 文案契约）、[`session-states.md`](./admin-center/session-states.md)（会话三态）；[`prototype/`](./admin-center/prototype/README.md)（界面设计原型）；`diagrams/`（c4-l3） |

图的 PlantUML 源文件与 SVG 同目录提交；改图后重新渲染：`java -jar plantuml.jar -tsvg -charset UTF-8 docs/system-design/*/diagrams/*.puml`。
