# achieve-specs — 用户需求描述的归档

这里只放**用户需求描述**，**不涉及技术细节**（技术方案、领域模型、接口、数据存储、实现与验证等一律在 [`../system-design/`](../system-design/README.md)）。目录按**需求的主体**命名（如 `admin-center`），不按技术方案里的服务命名。

归档规则见根 [`AGENTS.md`](../../AGENTS.md)「Project Context」：`specs/<功能>/` 完成后，**只有 `spec.md`** 迁入本目录；其余内容按 app 拆分到 `system-design/`。

| 需求 | 内容 |
|---|---|
| [`admin-center/spec.md`](./admin-center/spec.md) | Admin Center 用户管理与会话管理（User Manager / Session Manager）的需求规格。UAM 只是它的技术方案选择，见 [`system-design/uam/`](../system-design/uam/README.md) |
