# Contract: `tenant-core-cli`

Bootstrap 命令行，仅在受控环境运行，直连数据库（连接配置来自环境变量，见 quickstart）。所有写命令幂等并写 Audit（actor = `cli:<os-user>@<host>`）。

## 通用约定

- 调用方式：`tenant-core-cli <命令> [选项]`（可执行产物，数据库连接来自环境变量）。

- 退出码：`0` 成功（含 NOOP 幂等）；`1` 通用/校验错误；`2` 前置条件缺失（Tenant 不存在、Login Method 未启用、Module 不存在）；`3` 冲突（同标识不同属性）。
- 输出：人类可读文本到 stdout，错误到 stderr；全局 `--output json` 输出单行 JSON `{"result":"created|exists|enabled|already_enabled|subscribed|already_subscribed","...":...}`（`user create` 另含 `tenantUserId`）。
- 任何输出、日志与 Audit 中都不得出现密码、哈希、Token。
- 密码只能经 TTY 交互（隐藏输入 + 二次确认）或 `--password-stdin`（读 stdin 一行）；**无** `--password` 参数。

## 命令

| 命令 | 参数 | 行为 | 结果码 |
|---|---|---|---|
| `tenant create` | `--slug <slug>` `--name <name>` | 创建 Tenant，状态 ACTIVE | 0 created/exists；3 slug 已存在但 name 不同；1 slug 非法 |
| `login-method enable` | `--tenant <slug>` `--type password` | 为 Tenant 启用 Login Method | 0 enabled/already_enabled；2 Tenant 不存在；1 类型不支持 |
| `user create` | `--tenant <slug>` `--display-name <Display Name>` | 创建 User（此时尚不能登录），输出 `tenantUserId`（`tu_…`）；同 Tenant 同 Display Name 视为同一 User | 0 created/exists（exists 时返回已有 `tenantUserId`）；2 Tenant 不存在；1 Display Name 为空或过长 |
| `account create` | `--tenant <slug>` `--user <tenantUserId>` `--username <username>` `--type password`（默认） [`--password-stdin`] | 为已有 User 创建 username/password Account + Credential，`must_change_password=true` | 0 created/exists（已存在不改密码）；2 Tenant 或 User 不存在（含 User 属于其他 Tenant）、或 Tenant 未启用该 Login Method；3 username 已被同 Tenant 其他 User 的 Account 占用；1 username 非法/密码不合规/两次输入不一致 |
| `subscription add` | `--tenant <slug>` `--module platform` | 订阅 Module | 0 subscribed/already_subscribed；2 Tenant 或 Module 不存在 |
| `tenant show`（只读） | `--tenant <slug>` | 输出 Tenant、已启用 Login Method、User（Display Name + 关联 username）、订阅，用于核对 | 0；2 Tenant 不存在 |

## 密码规则

12–128 字符；不得等于 username；不满足时报错并列出规则。

## 契约测试要点（先红）

1. 重复执行每个写命令：第二次退出码 0，数据行数不变。
2. 对 stdout/stderr/日志/Audit 检索初始密码，命中为 0。
3. 无 `--password` 选项：传入即报“未知选项”。
4. 前置条件缺失时退出码 2 且无部分写入。
