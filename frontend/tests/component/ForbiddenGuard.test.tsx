import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import ForbiddenPage from "@/app/(console)/forbidden/page";
import { decideGuard } from "@/server/guard";

const admin = {
  userId: 1,
  tenantId: 0,
  displayName: "管理员",
  role: "TENANT_ADMIN" as const,
  mustChangePassword: false,
};
const member = { ...admin, role: "MEMBER" as const };

describe("decideGuard (role comes from /auth/me)", () => {
  it("sends a MEMBER to /forbidden and gives them the minimal shell", () => {
    expect(decideGuard(member, "/users")).toEqual({ redirect: "/forbidden", variant: "minimal" });
    expect(decideGuard(member, "/sessions")).toEqual({
      redirect: "/forbidden",
      variant: "minimal",
    });
  });

  it("lets a MEMBER stay on /forbidden and /change-password", () => {
    expect(decideGuard(member, "/forbidden")).toEqual({ redirect: null, variant: "minimal" });
    expect(decideGuard(member, "/change-password")).toEqual({ redirect: null, variant: "minimal" });
  });

  it("gives an admin the full shell and sends them away from /forbidden", () => {
    expect(decideGuard(admin, "/users")).toEqual({ redirect: null, variant: "full" });
    expect(decideGuard(admin, "/change-password")).toEqual({ redirect: null, variant: "full" });
    expect(decideGuard(admin, "/forbidden")).toEqual({ redirect: "/users", variant: "full" });
  });

  it("forces a password change before anything else, for every role", () => {
    for (const me of [admin, member]) {
      const must = { ...me, mustChangePassword: true };
      expect(decideGuard(must, "/users")).toEqual({
        redirect: "/change-password",
        variant: "locked",
      });
      expect(decideGuard(must, "/forbidden")).toEqual({
        redirect: "/change-password",
        variant: "locked",
      });
      expect(decideGuard(must, "/change-password")).toEqual({ redirect: null, variant: "locked" });
    }
  });
});

describe("ForbiddenPage", () => {
  it("explains the lack of permission", () => {
    render(<ForbiddenPage />);
    expect(screen.getByRole("heading", { name: "无权访问" })).toBeInTheDocument();
    expect(screen.getByText(/租户管理员/)).toBeInTheDocument();
  });
});
