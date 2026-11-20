import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

describe("Button", () => {
  it("renders a real button and handles clicks", async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>保存</Button>);
    await userEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("defaults to type=button and supports variants and disabled", () => {
    render(
      <>
        <Button variant="secondary">次要</Button>
        <Button variant="danger" disabled>
          删除
        </Button>
      </>,
    );
    expect(screen.getByRole("button", { name: "次要" })).toHaveAttribute("type", "button");
    expect(screen.getByRole("button", { name: "删除" })).toBeDisabled();
  });

  it("shows a busy state without losing its label", () => {
    render(<Button loading>提交</Button>);
    const b = screen.getByRole("button", { name: /提交/ });
    expect(b).toBeDisabled();
    expect(b).toHaveAttribute("aria-busy", "true");
  });
});

describe("Input and Field", () => {
  it("label is associated with the input", () => {
    render(<Field label="用户名">{(p) => <Input {...p} />}</Field>);
    expect(screen.getByLabelText("用户名")).toBeInTheDocument();
  });

  it("wires aria-invalid and aria-describedby to the error text", () => {
    render(
      <Field label="用户名" error="用户名格式不正确">
        {(p) => <Input {...p} />}
      </Field>,
    );
    const input = screen.getByLabelText("用户名");
    expect(input).toHaveAttribute("aria-invalid", "true");
    const err = screen.getByText("用户名格式不正确");
    expect(input.getAttribute("aria-describedby")).toContain(err.id);
  });

  it("has no aria-invalid without an error and shows hint + required marker", () => {
    render(
      <Field label="姓名" hint="最多 50 字" required>
        {(p) => <Input {...p} />}
      </Field>,
    );
    const input = screen.getByLabelText(/姓名/);
    expect(input).not.toHaveAttribute("aria-invalid", "true");
    expect(screen.getByText("最多 50 字")).toBeInTheDocument();
    expect(input).toBeRequired();
  });
});

describe("Badge", () => {
  it.each(["success", "warning", "danger", "info"] as const)("renders %s tone", (tone) => {
    render(<Badge tone={tone}>文字</Badge>);
    expect(screen.getByText("文字")).toHaveAttribute("data-tone", tone);
  });
});

describe("Card and cn", () => {
  it("renders a titled card", () => {
    render(
      <Card>
        <CardHeader>
          <CardTitle>基本信息</CardTitle>
        </CardHeader>
        内容
      </Card>,
    );
    expect(screen.getByRole("heading", { name: "基本信息" })).toBeInTheDocument();
    expect(screen.getByText("内容")).toBeInTheDocument();
  });

  it("cn merges conflicting tailwind classes", () => {
    expect(cn("px-2", "px-4", false && "hidden")).toBe("px-4");
  });
});
