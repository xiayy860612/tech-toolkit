# Kids Homework Review - Backend

基于 FastAPI 的家庭作业审阅系统后端服务。

## 技术栈

- **Python**: 3.11+
- **框架**: FastAPI 0.115+
- **服务器**: Uvicorn
- **数据库**: SQLite (异步)
- **ORM**: SQLAlchemy 2.0+ (异步)
- **数据库迁移**: Alembic
- **认证**: JWT + Bcrypt
- **验证**: Pydantic 2.10+
- **测试**: pytest + pytest-asyncio

## 项目结构

```text
backend/
├── src/                    # 源代码
│   ├── api/               # API 路由
│   │   ├── auth.py        # 认证端点
│   │   └── dependencies.py # FastAPI 依赖注入
│   ├── core/              # 核心配置
│   │   ├── config.py      # 应用配置
│   │   ├── database.py    # 数据库会话管理
│   │   └── security.py    # 安全工具（密码哈希、JWT）
│   ├── models/            # 数据模型
│   │   └── user.py        # 用户模型
│   ├── services/          # 业务逻辑层
│   │   └── auth.py        # 认证服务
│   └── main.py            # FastAPI 应用入口
├── tests/                 # 测试目录
│   ├── api/               # API 测试
│   ├── integration/       # 集成测试
│   ├── models/            # 模型测试
│   └── services/          # 服务测试
├── alembic/               # 数据库迁移
│   └── versions/          # 迁移脚本
├── run.py                 # 启动脚本
├── pyproject.toml         # 项目配置
└── requirements.txt       # 依赖列表
```

## 快速开始

### 1. 安装依赖

使用 [uv](https://github.com/astral-sh/uv) 管理虚拟环境和依赖：

```bash
# 安装 uv（如果尚未安装）
curl -LsSf https://astral.sh/uv/install.sh | sh

# 同步依赖（自动创建虚拟环境）
uv sync

# 或使用 uv pip 安装
uv pip install -r requirements.txt
```

### 2. 激活虚拟环境

```bash
# uv 自动管理虚拟环境，使用 uv run 执行命令：
uv run python run.py

# 或激活 uv 创建的虚拟环境
# macOS/Linux:
source .venv/bin/activate
# Windows:
.venv\Scripts\activate
```

### 2. 配置环境变量

```bash
# 复制环境变量示例文件
cp .env.example .env

# 编辑 .env 文件，配置必要的变量
# SECRET_KEY: 请在生产环境中使用强密钥
```

### 3. 初始化数据库

```bash
# 运行数据库迁移
alembic upgrade head

# 这将创建：
# - users 表
# - 预设用户admin
```

### 4. 启动服务

```bash
# 使用 uv 运行（推荐）
uv run python run.py --reload    # 开发模式（自动重载）
uv run python run.py              # 生产模式
uv run python run.py --port 8080  # 自定义端口

# 或直接运行（虚拟环境已激活）
python run.py --reload
```

服务启动后访问:
- API 文档: http://127.0.0.1:8000/docs
- 健康检查: http://127.0.0.1:8000/health

## 预设用户

系统预置了 1 个测试用户，密码均为 `password123`:

| 用户名 | 角色 | 显示名称 |
|--------|------|----------|
| admin | admin | 管理员 |

## API 端点

### 认证

- `POST /api/auth/login` - 用户登录
- `GET /api/auth/me` - 获取当前用户信息
- `POST /api/auth/logout` - 登出

### 其他

- `GET /` - 根路径
- `GET /health` - 健康检查

## 开发指南

### 运行测试

```bash
# 使用 uv 运行测试
uv run pytest

# 运行测试并显示覆盖率
uv run pytest --cov=src --cov-report=html

# 运行特定测试文件
uv run pytest tests/api/test_auth.py

# 显示详细输出
uv run pytest -v
```

### 代码检查

```bash
# 使用 ruff 检查代码
uv run ruff check .

# 自动修复问题
uv run ruff check . --fix

# 类型检查
uv run mypy src/
```

### 数据库迁移

```bash
# 创建新迁移
alembic revision --autogenerate -m "描述"

# 应用迁移
alembic upgrade head

# 回滚迁移
alembic downgrade -1

# 查看迁移历史
alembic history
```

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| DATABASE_URL | 数据库连接 | sqlite+aiosqlite:///./homework_review.db |
| SECRET_KEY | JWT 签名密钥 | - (必须配置) |
| ALGORITHM | JWT 算法 | HS256 |
| ACCESS_TOKEN_EXPIRE_MINUTES | Token 有效期（分钟） | 1440 |
| FRONTEND_URL | 前端 URL（CORS） | http://localhost:3000 |
| COOKIE_SECURE | Cookie 安全设置 | False |

## 架构说明

### 分层架构

```text
┌─────────────────────────────────────┐
│         API Layer (FastAPI)         │  ← 路由定义、请求处理
├─────────────────────────────────────┤
│       Service Layer (Business)      │  ← 业务逻辑
├─────────────────────────────────────┤
│         Data Layer (Models)         │  ← 数据模型、ORM
├─────────────────────────────────────┤
│      Database (SQLite + Alembic)    │  ← 数据存储
└─────────────────────────────────────┘
```

### 认证流程

1. 用户通过 `/api/auth/login` 提交用户名和密码
2. 服务验证凭据
3. 生成 JWT token
4. 通过 httpOnly cookie 返回 token
5. 后续请求自动从 cookie 读取 token
6. 验证 token 并获取当前用户

## 许可证

MIT
