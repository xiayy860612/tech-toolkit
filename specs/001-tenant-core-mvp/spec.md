# Feature Specification: Tenant Core MVP

**Feature Branch**: `claude/tenant-core-mvp-6b03bc`

**Created**: 2026-11-07

**Status**: Draft

**Input**: User description: "根据 docs/system-design/01-overview.md 架构设计，MVP 要完成 tenant core 的功能：1. 使用 cli 创建租户 2. 为租户添加支持用户名/密码登录方式 3. 为租户创建第一个使用用户名/密码登录的用户 4. 为租户订阅第一个模块，platform 5. 使用 platform web 进行用户名密码登录 6. 登录后可以看到的左侧导航菜单：租户管理（查看已创建的租户）、模块管理（看到已订阅的模块）、用户管理（看到所有创建的用户）"

## Clarifications

### Session 2026-11-07

- Q: 三个管理页应让登录 User 看到什么范围的数据？ → A: 所有登录 User 都看到该 cell 内全部 Tenant、订阅和 User，MVP 不区分所属 Tenant；后期通过 Module 的 RBAC 控制可见范围。
- Q: 首次登录是否强制修改 CLI 设置的初始密码？ → A: 纳入 MVP，改密完成前不可进入主界面（遵循 D23）。
- Q: User 与登录凭据的关系？ → A: 区分“User”与“Account”：username/password属于 Account，Account 关联且仅关联一个 User；一个 User 可有多个 Account（MVP 每个 User 只建一个 username/password Account）。
- Q: 创建 User 时，User 本身是否需要独立于 username 的 Display Name？ → A: 需要，Display Name 必填；User 页显示 Display Name 与关联 Account 的 username。
- Q: MVP 不部署网关时，UAM 如何识别请求的调用者？ → A: UAM 同时支持两种请求凭据：Opaque Token（登录后签发的 session id，经请求头携带，查表校验），以及由其换发的短有效期 Internal JWT（经标准认证头携带，本地验签）；并提供换发能力；请求头的具体名称见契约。platform web 目前直接使用 Opaque Token，后期引入网关时无需改动 UAM。
- Q: 访问未订阅 platform 的 Tenant 登录时如何避免被探测？ → A: 仅在 username 与密码验证通过后才提示“无访问权限”，username 或密码不正确时一律是统一的登录失败提示。
- Q: 界面如何做到友好并自适应？ → A: 宽屏（≥768px）左侧导航 + 表格；窄屏（<768px）顶部栏 + 折叠菜单，列表在 <640px 以卡片展示；界面文案全部中文（列头、状态、时间按浏览器本地时区、无账户显示“—”）。
- Q: 界面骨架如何对齐主流管理后台？ → A: 增加全局顶栏（左侧产品名，右侧用户菜单），“退出登录”收进用户菜单；各页统一页头（标题 + 右侧操作位，MVP 只读无操作）；侧边栏只放三个导航项（2026-11-07，用户决定做，待确认本方案）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 运维通过 CLI Bootstrap Tenant（Priority: P1）

运维人员在受控环境中用命令行工具，按顺序完成一个新 Tenant 的 Bootstrap：创建 Tenant → 为该 Tenant 启用“username/password”Login Method → 创建第一个 User → 为该 User 创建 username/password Account → 为该 Tenant 订阅 `platform` Module。每一步都有明确的成功或失败反馈，重复执行不会产生重复数据。

**Why this priority**: 没有 Tenant、Login Method、首个 User 和 Module Subscription，后续任何 Web 登录与查看都无从谈起；这是整个 MVP 的数据起点。

**Independent Test**: 在全新环境中依次执行五条 CLI 操作，随后用只读核对命令查询（FR-022，不依赖 Web），可确认 Tenant、Login Method、User、Account、订阅均存在且状态正确。

**Acceptance Scenarios**:

1. **Given** 全新环境且不存在 Tenant `acme`，**When** 运维执行“创建 Tenant”并提供 Tenant Slug 与名称，**Then** Tenant 被创建，状态为可用，并提示成功。
2. **Given** Tenant `acme` 已存在，**When** 运维为其启用 username/password Login Method，**Then** 该 Tenant 的登录配置中包含 username/password，且可再次执行而无副作用（幂等）。
3. **Given** Tenant `acme` 已存在，**When** 运维创建首个 User（Display Name，如“张三”），**Then** User 被创建并关联到该 Tenant，得到系统生成的 User ID，此时 User 还不能登录。
4. **Given** Tenant `acme` 已启用 username/password 登录且 User 已存在，**When** 运维为该 User 创建 Account（username + 交互输入的初始密码），**Then** Account 被创建并关联到该 User，密码不以明文保存，也不出现在命令行参数、日志或输出中。
5. **Given** Tenant `acme` 已存在，**When** 运维为其订阅 `platform` Module，**Then** Tenant 的订阅列表中出现 `platform`，状态为已订阅。
6. **Given** Tenant `acme` 已存在，**When** 运维再次执行相同的创建 Tenant 命令，**Then** 不产生重复 Tenant，命令给出“已存在”的明确提示（或幂等成功），不报内部错误。
7. **Given** Tenant 未启用 username/password 登录，**When** 运维尝试为其 User 创建 username/password Account，**Then** 操作被拒绝并说明需先启用该 Login Method。

---

### User Story 2 - User 在 platform web 用 username/password 登录（Priority: P1）

Tenant User（CLI 创建的首个 User）打开 platform web，输入 Tenant Slug、username 和密码登录。成功后以该 Account 所关联的 User 身份进入管理界面；失败时看到不泄露细节的提示，且可以重试。可以退出登录。

**Why this priority**: 这是 User 进入系统的唯一入口，与 Story 1 共同构成最小可用闭环。

**Independent Test**: 使用 Story 1 创建的 Account 在浏览器完成登录、刷新页面保持登录、退出后无法再访问受保护页面。

**Acceptance Scenarios**:

1. **Given** User 已由 CLI 创建且所属 Tenant 已订阅 `platform`，**When** 输入正确的 Tenant Slug、username 与密码，**Then** 登录成功并进入带左侧导航的主界面。
2. **Given** 登录页，**When** 输入错误密码或不存在的 username，**Then** 显示统一的“用户名或密码错误”提示，不区分具体原因，且不登录。
3. **Given** 未登录，**When** 直接访问任一受保护页面，**Then** 被引导至登录页，登录后回到原目标页。
4. **Given** 已登录，**When** 点击退出，**Then** Session 终止，再次访问受保护页面须重新登录。
5. **Given** User 所属 Tenant 未订阅 `platform`，**When** 其用正确的 Tenant Slug、username 和密码登录 platform web，**Then** 登录被拒绝并提示无访问权限；username 或密码不正确时仍是统一的“用户名或密码错误”，不暴露订阅状态。
6. **Given** 同一 Account 连续多次输入错误密码，**When** 超过允许次数，**Then** 该 Account 被暂时限制登录；此后登录失败的提示与普通密码错误一致，仅在文案中附带“如多次失败请稍后再试”，不暴露 Account 是否已被锁定。

---

### User Story 3 - 登录后通过左侧导航查看 Tenant、Module、User（Priority: P2）

登录后，左侧导航提供三个入口：**租户管理**（列出已创建的 Tenant）、**模块管理**（列出已订阅的 Module）、**用户管理**（列出所有已创建的 User）。MVP 阶段三个页面均为只读列表。

**Why this priority**: 让 User 直观验证 CLI 写入的数据，是 MVP 的可见成果；依赖前两个故事先行。

**Independent Test**: 以 Story 1 数据登录后，依次点击三个导航项，确认各列表内容与 CLI 创建的数据一致。

**Acceptance Scenarios**:

1. **Given** 已登录，**When** 查看左侧导航，**Then** 恰好显示“租户管理”“模块管理”“用户管理”三项，并标识当前所在项。
2. **Given** 已创建 Tenant `acme`，**When** 进入租户管理，**Then** 列表包含 `acme`，至少展示 Tenant Slug、名称、状态、创建时间。
3. **Given** `acme` 已订阅 `platform`，**When** 进入模块管理，**Then** 列表包含 `platform`，展示 Module 名称、所属 Tenant、订阅状态、订阅时间。
4. **Given** 已创建 User“张三”，且其 username 为 `admin`，**When** 进入用户管理，**Then** 列表包含“张三”，并展示 Display Name、关联 username `admin`、所属 Tenant、状态、创建时间，且不展示任何密码或 Credential 信息；尚无 Account 的 User 也出现在列表中，关联 username 处为空。
5. **Given** 对应列表中没有数据，**When** 进入该页面，**Then** 显示明确的空状态而非报错。

---

### Edge Cases

- 创建 Tenant 时标识已存在、为空或包含非法字符：拒绝并说明原因。
- 创建 Account 时 username 已存在（同一 Tenant 内）：拒绝并说明；不同 Tenant 可重名。
- 为不存在的 User（或其他 Tenant 的 User）创建 Account：拒绝并说明 User 不存在。
- 同一 User 重复创建同一类型 Account（MVP 每 User 仅一个 username/password Account）：幂等提示已存在，不覆盖密码。
- 创建 User 时同 Tenant 已有同名 Display Name：视为同一 User，返回已有 User（MVP 的 CLI 幂等规则）。
- User 存在但尚无 Account：可在 User 页看到该 User，但无法登录。
- 初始密码不满足强度要求：拒绝并说明规则；两次交互输入不一致：拒绝。
- 订阅不存在的 Module 或重复订阅同一 Module：给出明确提示，不产生重复订阅。
- 对不存在的 Tenant 执行启用 Login Method、创建 User、订阅 Module：拒绝并提示 Tenant 不存在。
- Tenant 被暂停或 User 被禁用后尝试登录：登录被拒绝（MVP 不提供暂停/禁用入口，但数据层状态须被登录流程遵守）。
- 首次登录强制改密：按架构基线（D23），CLI 创建的初始密码须在首次登录后修改。
- Session 过期后访问页面：被引导重新登录，不出现裸错误页。
- 列表数据量增长（如数百条）：列表仍可正常浏览（分页）。

## Requirements *(mandatory)*

### Functional Requirements

**CLI Bootstrap**

- **FR-001**: 系统 MUST 提供命令行工具，支持创建 Tenant（Tenant Slug、名称）。
- **FR-002**: CLI MUST 支持为指定 Tenant 启用“username/password”Login Method。
- **FR-003**: CLI MUST 支持为指定 Tenant 创建 User（Display Name 必填），并返回系统生成的 User ID。
- **FR-003a**: CLI MUST 支持为指定 Tenant 内的已有 User 创建 username/password Account（username + 初始密码），Account 关联且仅关联该 User；初始密码 MUST 通过交互输入或标准输入提供，MUST NOT 作为命令行参数，MUST NOT 出现在日志与输出中。
- **FR-004**: CLI MUST 支持为指定 Tenant 订阅 Module，MVP 可订阅的 Module 为 `platform`。
- **FR-005**: 所有 CLI 操作 MUST 幂等：重复执行不产生重复数据，并给出明确的结果提示。
- **FR-006**: 每次 CLI 操作 MUST 记录 Audit（谁、何时、做了什么、对哪个 Tenant），Audit 中不含密码。
- **FR-007**: CLI MUST 对无效输入、前置条件缺失（如 Tenant 不存在、Login Method 未启用）给出可读的错误信息与非零退出状态。
- **FR-022**: CLI MUST 提供只读核对命令，按 Tenant 输出其已启用的 Login Method、User（Display Name 及关联 username）和订阅，供运维核对；该命令不修改数据，不输出任何 Credential。

**登录与 Session**

- **FR-008**: platform web MUST 提供登录页，User 输入 Tenant Slug、username 和密码（username 仅在 Tenant 内唯一，故需 Tenant Slug），登录成功后以该 Account 所关联的 User 身份进入主界面。
- **FR-009**: 密码 MUST 以不可逆方式保存，系统任何位置均不得存储或回显明文密码。
- **FR-010**: 登录失败 MUST 返回统一提示，不泄露 Account 是否存在。
- **FR-011**: 系统 MUST 对同一 Account 的连续失败登录进行限制（暂时锁定或限频），防止暴力破解。
- **FR-012**: 仅当 Account 所属 Tenant 已启用 username/password 登录、Tenant 与 Account 所关联 User 的状态可用、且 Tenant 已订阅 `platform` 时，方可登录 platform web；“未订阅”的提示只在 username 与密码验证通过后给出，username 或密码不正确时一律返回统一的登录失败提示。
- **FR-013**: 受保护页面 MUST 要求已登录；未登录访问 MUST 引导至登录页；User MUST 能主动退出登录，退出后 Session 立即失效。
- **FR-014**: 由 CLI 创建的 Account MUST 在首次登录后被要求修改初始密码，改密完成前不可进入主界面。
- **FR-015**: 登录成功与失败事件 MUST 记录 Audit，Audit 中不含密码。
- **FR-023**: UAM MUST 同时支持两种请求凭据：Opaque Token（session id，经请求头携带）与由其换发的短有效期 Internal JWT（经标准认证头携带，头名见 [contracts/tenant-core-api.yaml](contracts/tenant-core-api.yaml)）；MUST 提供“Opaque Token 换发 Internal JWT”的能力与 Internal JWT 的公钥发布。退出或吊销后不能再换发；已换发的 Internal JWT 在其短有效期内仍有效（可接受的吊销延迟）。登录、退出、改密、换发只接受 Opaque Token；两种凭据同时出现、都缺失或放错位置，一律视为未认证。

**导航与只读管理页**

- **FR-016**: 登录后的主界面 MUST 包含左侧导航，且仅含“租户管理”“模块管理”“用户管理”三项，并突出当前项。
- **FR-017**: “租户管理”页 MUST 列出系统中已创建的 Tenant，展示标识、名称、状态、创建时间。
- **FR-018**: “模块管理”页 MUST 列出已订阅的 Module，展示 Module、所属 Tenant、订阅状态、订阅时间。
- **FR-019**: “用户管理”页 MUST 列出所有已创建的 User（含尚无 Account 的 User），展示 Display Name、关联 Account 的 username、所属 Tenant、状态、创建时间，MUST NOT 展示任何 Credential 信息。
- **FR-020**: 三个列表页 MUST 支持空状态展示，并在数据量较大时分页。
- **FR-021**: MVP 阶段三个页面 MUST 为只读；页面上不提供新增、编辑、删除操作。
- **FR-024**: 界面 MUST 自适应屏幕宽度：宽度 ≥ 768px 时左侧导航加表格；< 768px 时导航收起为顶部栏，点击菜单按钮展开三个导航项；< 640px 时三个列表以卡片展示（每条记录一张卡片，字段名与值成对显示）。任一宽度下页面都不出现横向滚动。
- **FR-025**: 界面文案 MUST 统一为中文：列头为“显示名、用户名、租户、状态、创建时间”等；状态显示为“正常 / 已暂停 / 已停用 / 已订阅”；时间按浏览器本地时区显示；没有 Account 的 User 其用户名显示“—”；表单错误只显示中文提示（不拼接后端英文原文）；登录页与改密页居中显示，输入框和按钮的点击区域不小于 44px。
- **FR-026**: 所有页面（登录、改密除外）MUST 有全局顶栏：左侧为产品名“Platform”（窄屏时同一行最左是菜单按钮），右侧为用户菜单。用户菜单的触发按钮显示当前登录 User 的 Display Name，展开后显示 Display Name、username 与所属 Tenant 的 Tenant Slug，并提供“退出登录”（仍是 POST，仅此一处入口，侧边栏与折叠菜单中不再重复）。任何宽度下用户菜单都可达，触发区域不小于 44px。
- **FR-027**: 三个列表页 MUST 使用统一页头：左侧页面标题，右侧预留操作位。MVP 只读，操作位为空且不渲染任何写操作控件；页面内容区限制最大宽度（大屏下表格不无限拉宽）。
- **FR-028**: 侧边栏（≥ 768px）MUST 只含三个导航项，当前项高亮；侧边栏底部不再放“退出登录”。

### Key Entities

- **Tenant**: 使用 Platform 的客户组织。属性：Tenant Slug、name、status、创建时间。
- **Login Method**: Tenant 可使用的登录途径；MVP 仅 username/password（`PASSWORD`）。属于一个 Tenant。
- **User**: Tenant 内的“一个人”，是业务身份。属性：User ID、Display Name、所属 Tenant、状态、创建时间。一个 User 可有多个 Account（MVP 只建一个）。
- **Account**: 登录凭据的载体，隶属于 Tenant，关联且仅关联一个 User。MVP 仅“username/password”类型：username（Tenant 内唯一）、密码（仅保存不可逆摘要，永不对外展示）、是否需首次改密、登录失败/锁定状态。后期的其他 Login Method（OIDC、微信等）同样作为 Account 挂到 User 上。
- **Module Subscription**: Tenant 对某 Module 的授权记录。属性：Module、所属 Tenant、状态、订阅时间；MVP 仅 `platform`。
- **Session**: User 登录后的有效访问状态，退出或过期后失效。
- **Audit Event**: CLI 操作与登录事件的留痕。

### Ubiquitous Language

专业术语与业务术语在 spec、plan、tasks、契约中一律使用下表的英文词；**代码（类名、方法名、变量、表名、字段、API 路径与字段、CLI 命令与参数、测试名）必须使用同一个词**，不另造同义词、不用拼音或其他译法。新增业务概念时先在此表登记，再写代码。各术语对应的代码命名见 [plan.md](plan.md) 的“命名约定”。

**Module 只指可订阅的产品模块**（如 `platform`）。代码层面的划分一律不叫 Module，具体称呼见 plan 的“命名约定”。文档中指决策人的“用户”不写成 User，直接写“已确认 / 已决定”。

| Term | 含义 |
|---|---|
| Tenant | 使用 Platform 的客户组织 |
| Tenant Slug | Tenant 的可读唯一标识，创建后不可变 |
| User | Tenant 内的一个人（业务身份）；对外以 User ID 标识 |
| Display Name | User 的显示名 |
| Account | 登录凭据载体，关联且仅关联一个 User |
| username | Account 的登录名，Tenant 内唯一 |
| Login Method | Tenant 可用的登录途径（MVP 仅 username/password） |
| Credential | Account 的密码凭证（仅存摘要）；不指 Opaque Token 或 Internal JWT |
| Module | 可订阅的产品模块（MVP 仅 `platform`） |
| Module Subscription | Tenant 对 Module 的订阅记录 |
| Opaque Token | 登录后由 UAM 签发的不透明 session id，经请求头携带；库中只存哈希 |
| Internal JWT | 由 Opaque Token 换发的短有效期内部令牌，经标准认证头携带 |
| Token Exchange | 用 Opaque Token 换发 Internal JWT |
| Session | 登录会话；Opaque Token 即其 session id，另有一个非机密的会话标识写入 Internal JWT；浏览器只持 cookie |
| Audit Event | 操作与登录事件的留痕 |
| Bootstrap | 受控环境下用 CLI 完成的初始化 |
| Platform | 平台侧；platform web 为其 Web 入口 |
| UAM | User Account Management，认证组件：User、Account、Login Method、Credential、Opaque Token、Internal JWT 的归属 |

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 运维在全新环境中，从创建 Tenant 到完成首个 User、其 Account 与 `platform` 订阅，5 个步骤可在 5 分钟内完成，且无需手工编辑数据。
- **SC-002**: User 使用 CLI 创建的 Account，从打开登录页到看到主界面（含首次改密）可在 1 分钟内完成。
- **SC-003**: 登录后，三个导航页的列表内容与 CLI 写入的数据 100% 一致（无遗漏、无多余）。
- **SC-004**: 对同一命令重复执行 10 次，数据结果与执行 1 次完全一致（0 条重复数据）。
- **SC-005**: 在任何输出、日志、页面、Audit 中检索 CLI 输入的初始密码，命中数为 0。
- **SC-006**: 错误密码与不存在 Account 两种登录失败的提示文案、响应表现不可区分。
- **SC-007**: 未登录状态下访问全部三个受保护页面，100% 被引导至登录页。
- **SC-008**: 三个列表页在 500 条数据下，User 在 2 秒内看到首屏内容。
- **SC-009**: 在 360px、768px、1280px 三种宽度下，登录、改密和三个列表页都可正常使用且页面无横向滚动；窄屏可用菜单到达三个导航项和退出登录。
- **SC-010**: 在 360px、768px、1280px 下，登录后顶栏始终可见；点击用户菜单能看到 Display Name、username、Tenant Slug 和“退出登录”，点击后回到登录页；退出登录入口在整个界面中只有这一处。

## Assumptions

- **单 cell 范围**：MVP 只部署一套 cell（对应架构中 Platform 侧的 Tenant Core 与 platform web），不涉及多 cell、跨 cell 事件与网关路由；后续再扩展。
- **可见范围**：三个管理页展示该 cell 内的全部 Tenant、订阅与 User（只读），MVP 不做按 Tenant 过滤或细粒度角色授权，所有可成功登录的 User 均可看到这三项（已确认）；后期通过 Module 的 RBAC 控制可见范围，不在本期。
- **Tenant Slug**：Tenant Slug 为人类可读的唯一短标识（如 `acme`），创建后不可变。
- **Platform Service 暂未接入**：Module Subscription 由 CLI 直接写入；与 Tenant 注册表的对账方式（见架构待定项）不在本期范围。
- **仅 username/password**：OIDC、broker、微信等其他 Login Method、找回密码、注册自助均不在本期范围。
- **没有写操作 UI**：Tenant、Module、User 的新增/修改/禁用/删除均不在 Web 界面提供，仅通过 CLI（且仅限本 spec 列出的五项写操作：建 Tenant、启用 Login Method、建 User、建 Account、订阅 Module，以及一个只读核对命令）。
- **运行环境**：CLI 在受控环境执行，并有权限访问系统数据存储；User 使用现代桌面浏览器访问 platform web。
- **密码策略采用行业通行默认**：最小长度与复杂度、失败次数阈值与锁定时长的具体数值在规划阶段确定。
- **依据文档**：架构边界、术语与 Bootstrap 约束遵循 `docs/system-design/01-overview.md`、`02-authentication.md`（含 6.5 CLI Bootstrap）及决策 D23；`docs/achieve-specs/` 目前尚无现状文档，以上述设计基线为准。
