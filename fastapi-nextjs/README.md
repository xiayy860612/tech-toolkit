# Demo Constitution

## Core Principles

### I. Frontend-Backend Separation

System architecture MUST maintain strict separation between frontend and backend:

- Frontend and backend MUST be developed as independent codebases
- Frontend MUST consume backend exclusively through well-defined RESTful APIs
- Backend MUST have no direct dependencies on frontend implementation
- Communication MUST occur only via network protocols (HTTP/HTTPS)
- No shared state between frontend and backend except via API contracts
- Frontend and backend MAY be deployed, versioned, and scaled independently

**Rationale**: Separation enables independent development, testing, deployment, and scaling of frontend and backend. It reduces coupling and allows technology flexibility for each layer.

### II. Responsive Web First

Frontend MUST be a responsive web application that adapts to all device sizes:

- Application MUST be web-based (HTML/CSS/JavaScript)
- UI MUST be responsive and adapt seamlessly to mobile, tablet, and desktop viewports
- Progressive enhancement approach: core functionality MUST work on all devices
- Touch interactions MUST be first-class citizens (not afterthoughts)
- Performance MUST be acceptable on mobile networks (3G+)
- Testing MUST include validation on multiple viewport sizes

**Rationale**: Users access educational tools from diverse devices. A responsive web approach ensures universal accessibility without platform-specific development overhead.

### III. Test-Driven Development (NON-NEGOTIABLE)

TDD is MANDATORY for all feature development:

- Tests MUST be written BEFORE implementation code
- Tests MUST fail initially (red phase)
- Implementation is written ONLY to make tests pass (green phase)
- Refactoring occurs ONLY after tests pass (refactor phase)
- Red-Green-Refactor cycle MUST be strictly enforced
- No production code WITHOUT corresponding tests
- Unit test coverage MUST be at least 90%
- Test coverage MUST be measured and enforced in CI/CD

**Rationale**: TDD ensures code correctness, provides living documentation, enables safe refactoring, and catches bugs early. The discipline of writing tests first drives better design.

### IV. Code Quality Standards

All code MUST meet established quality thresholds:

- Code MUST pass static analysis (linting) before commit
- Code MUST follow language-specific best practices
- Functions MUST be small and single-purpose
- Complex logic MUST be extracted into well-named functions
- Code MUST be self-documenting with clear naming
- Comments ONLY for "why", never for "what"
- Maximum cyclomatic complexity MUST be enforced
- Code duplication MUST be minimized

**Rationale**: High-quality code is maintainable, readable, and less error-prone. Enforced standards prevent technical debt accumulation.

### V. Unified Code Style

Code style MUST be consistent across the entire codebase:

- Automated formatting MUST be configured (Prettier, Black, rustfmt, etc.)
- Style MUST be enforced via CI/CD gates
- No style debates - adopt language-community standards
- Format-on-save MUST be used by all developers
- Pre-commit hooks MUST validate style compliance
- Style configuration MUST be version-controlled

**Rationale**: Consistent style reduces cognitive load, minimizes merge conflicts, and eliminates subjective code review discussions about formatting.

## Architecture Standards

### Technology Stack

- **Backend**: Python + FastAPI + SQLAlchemy
- **Database**: SQLite
- **Frontend**: Next.js (App Router)
  - **Framework**: Next.js 16+ with App Router (NOT Pages Router)
  - **React**: React 19+
  - **Language**: TypeScript 5+
  - **Styling**: Tailwind CSS 4+
  - **UI Components**: ShadCN UI (Radix UI primitives + Tailwind)
  - **State Management**: Zustand (for global state)
  - **Forms**: React Hook Form + Zod validation
  - **Data Fetching**: Next.js fetch with native cache (SWR/React Query for complex scenarios)
- **API Documentation**: OpenAPI/Swagger specification REQUIRED for all endpoints (FastAPI auto-generates)
- **Authentication**: JWT (JSON Web Token) based Bearer Token authentication
  - All authenticated API requests MUST include `Authorization: Bearer <jwt_token>` header
  - JWT tokens MUST contain user identity claims (user_id, username, role, exp)
  - Token validation MUST occur on every protected endpoint
  - Frontend MUST store tokens securely (httpOnly cookies or secure storage)
  - Token refresh mechanism MUST be implemented for long-lived sessions (may be deferred for MVP/trusted environments with explicit justification)
- **Testing**:
  - Backend: pytest with pytest-cov for coverage
  - Frontend: Jest with @testing-library/react @testing-library/jest-dom

### Code Organization

**Unit tests MUST be separated from source code** (test files in dedicated `tests/` directory mirroring source structure).

```text
backend/
├── src/
│   ├── api/          # API endpoints and routes
│   │   └── routes.py
│   ├── services/     # Business logic
│   │   └── homework.py
│   └── models/       # Data models
│       └── submission.py
└── tests/            # All tests (mirrors src/ structure)
    ├── api/
    │   └── test_routes.py
    ├── services/
    │   └── test_homework.py
    ├── models/
    │   └── test_submission.py
    └── integration/  # Integration/e2e tests

frontend/
├── app/                           # Next.js App Router (filesystem-based routing)
│   ├── (auth)/                    # Route group for auth pages (not part of URL)
│   │   ├── login/
│   │   │   └── page.tsx          # Login page (/login)
│   │   └── layout.tsx            # Auth-specific layout
│   ├── (dashboard)/               # Route group for dashboard pages
│   │   ├── homework/
│   │   │   ├── page.tsx          # Homework list (/homework)
│   │   │   └── [id]/
│   │   │       └── page.tsx      # Homework detail (/homework/123)
│   │   └── layout.tsx            # Dashboard layout with nav
│   ├── api/                       # API routes (optional backend-for-frontend)
│   │   └── proxy/
│   │       └── route.ts          # API proxy route
│   ├── layout.tsx                # Root layout (html, body, global providers)
│   ├── page.tsx                  # Home page (/)
│   ├── globals.css               # Global styles + Tailwind directives
│   └── loading.tsx               # Global loading UI
├── src/
│   ├── components/               # Reusable UI components
│   │   ├── ui/                   # ShadCN UI components (auto-imported)
│   │   │   ├── button.tsx
│   │   │   ├── input.tsx
│   │   │   └── ...
│   │   ├── auth/                 # Domain-specific components
│   │   │   └── LoginForm.tsx
│   │   ├── homework/              # Domain-specific components
│   │   │   └── HomeworkCard.tsx
│   │   └── shared/               # Shared components
│   │       └── Header.tsx
│   ├── lib/                      # Utility libraries
│   │   ├── utils.ts              # ShadCN utils (cn function)
│   │   ├── store.ts              # Zustand stores
│   │   └── api-client.ts         # API client wrapper
│   ├── hooks/                    # Custom React hooks
│   │   ├── useAuth.ts
│   │   └── useHomework.ts
│   ├── services/                 # API service layer
│   │   ├── auth.service.ts
│   │   └── homework.service.ts
│   ├── types/                    # TypeScript types/schemas
│   │   ├── auth.ts
│   │   └── homework.ts
│   └── styles/                   # Additional styles (if needed)
│       └── markdown.css
├── public/                       # Static assets
│   ├── images/
│   └── favicon.ico
├── tests/                        # All tests (mirrors src/ structure)
│   ├── components/
│   │   ├── ui/
│   │   │   └── button.test.tsx
│   │   └── auth/
│   │       └── LoginForm.test.tsx
│   ├── hooks/
│   │   └── useAuth.test.ts
│   ├── services/
│   │   └── auth.service.test.ts
│   └── integration/
│       └── login-flow.test.tsx
├── components.json               # ShadCN UI configuration
├── next.config.ts                # Next.js configuration
├── tailwind.config.ts            # Tailwind CSS configuration
├── tsconfig.json                 # TypeScript configuration
├── package.json                  # Dependencies and scripts
└── jest.config.js                # Jest test configuration
```

### Frontend: Next.js App Router Structure

Frontend MUST use Next.js App Router (NOT Pages Router):

- **Routes**: Filesystem-based routing under `app/` directory
- **Route Groups**: Use parentheses `(name)` for logical grouping without URL segments
- **Dynamic Routes**: Use square brackets `[id]` for dynamic segments
- **Layouts**: Nested `layout.tsx` files for shared UI across routes
- **Loading States**: `loading.tsx` for route-level loading UI
- **Error Handling**: `error.tsx` for route-level error boundaries
- **Server Components**: Default to Server Components, use `"use client"` only when needed
- **Data Fetching**: Use async Server Components for data fetching when possible

**Route Group Conventions**:

- `(auth)` - Authentication-related pages (login, register, forgot-password)
- `(dashboard)` - Protected pages requiring authentication
- `(public)` - Public pages accessible without authentication

### Frontend: Tailwind CSS Guidelines

Tailwind CSS MUST be used for all styling:

- **Utility-First**: Use Tailwind utility classes for all styling
- **Component Variants**: Use `cva` (class-variance-authority) for component variants
- **Responsive Design**: Mobile-first approach with Tailwind breakpoints (`sm:`, `md:`, `lg:`, `xl:`)
- **Dark Mode**: Support dark mode using `dark:` prefix
- **Custom Colors**: Extend Tailwind theme in `tailwind.config.ts` for brand colors
- **Arbitrary Values**: Use arbitrary values (`w-[123px]`) sparingly; prefer theme extension

**Common Tailwind Patterns**:

```tsx
// Spacing: m-{size}, p-{size}, gap-{size}
// Flexbox: flex, flex-col, items-center, justify-between
// Grid: grid, grid-cols-{n}, gap-{size}
// Typography: text-{size}, font-{weight}, leading-{tight/normal}
// Colors: bg-{color}-{shade}, text-{color}-{shade}, border-{color}-{shade}
// Responsive: sm:{prop}, md:{prop}, lg:{prop}
// Dark mode: dark:bg-{color}, dark:text-{color}
```

### Frontend: ShadCN UI Component Usage

ShadCN UI MUST be used for all common UI components:

- **Installation**: Components are copied to `src/components/ui/`, not installed as packages
- **Component First**: Always check if ShadCN has a component before building custom
- **Customization**: ShadCN components can be customized after installation
- **Styling**: All ShadCN components use Tailwind CSS and follow the same styling patterns
- **Icons**: Use `lucide-react` for icons (ShadCN default)

**Available Components** (add via `npx shadcn@latest add <component>`):

- Form: `form`, `input`, `label`, `textarea`, `select`, `checkbox`, `radio`, `switch`
- Feedback: `alert`, `toast`, `dialog`, `popover`, `tooltip`, `alert-dialog`
- Layout: `card`, `separator`, `tabs`, `collapsible`, `accordion`
- Navigation: `button`, `link`, `breadcrumb`, `pagination`
- Data Display: `table`, `badge`, `avatar`, `skeleton`, `progress`
- Commands: `command` (cmd+k menu)

**ShadCN Component Conventions**:

- Use the `cn()` utility from `lib/utils.ts` for conditional classes
- Extend components using `React.forwardRef` for ref forwarding
- Follow ShadCN patterns when creating new similar components
- Use `cva` for component variants (size, variant, color)

## Development Workflow

### Feature Development Process

1. **Write Tests First**: All test files created and verified to fail
2. **Implement**: Write minimal code to pass tests
3. **Refactor**: Improve code while keeping tests green
4. **Review**: Code review MUST verify TDD compliance
5. **Merge**: Only after all tests pass and style checks succeed

### Quality Gates

- **Pre-commit**: Linting, formatting, type checking
- **Pre-push**: All tests must pass
- **Pre-merge**: Code review approval, test coverage threshold met

## Governance

### Amendment Procedure

- Constitution changes require documented rationale
- Version bump MUST follow semantic versioning
- All dependent templates MUST be updated

### Compliance

- All pull requests MUST verify constitution compliance
- Violations MUST be explicitly justified and documented
- Complexity MUST be justified in implementation plan

### Versioning

- **MAJOR**: Principle removal or backward-incompatible changes
- **MINOR**: New principle or section addition
- **PATCH**: Clarifications and non-semantic refinements

**Version**: 1.2.6 | **Ratified**: 2025-01-21 | **Last Amended**: 2025-01-21
