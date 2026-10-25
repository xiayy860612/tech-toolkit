import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { UserMenu } from "@/components/shell/user-menu";

describe("UserMenu", () => {
  it("按钮显示用户名，菜单含「修改密码」「退出登录」", async () => {
    const user = userEvent.setup();
    render(<UserMenu userName="张三" />);
    const trigger = screen.getByRole("button", { name: /张三/ });
    expect(trigger).toHaveAttribute("aria-haspopup", "menu");

    await user.click(trigger);
    const password = await screen.findByRole("menuitem", { name: "修改密码" });
    expect(password).toHaveAttribute("href", "/account/password");
    expect(screen.getByRole("menuitem", { name: "退出登录" })).toBeInTheDocument();
  });

  it("「退出登录」是 POST /logout 表单（写操作，走同源校验）", async () => {
    const user = userEvent.setup();
    render(<UserMenu userName="张三" />);
    await user.click(screen.getByRole("button", { name: /张三/ }));
    const logout = await screen.findByRole("menuitem", { name: "退出登录" });
    const form = logout.closest("form");
    expect(form).toHaveAttribute("method", "post");
    expect(form).toHaveAttribute("action", "/logout");
    expect(logout).toHaveAttribute("type", "submit");
  });

  it("键盘可打开，Esc 关闭并回到触发按钮", async () => {
    const user = userEvent.setup();
    render(<UserMenu userName="张三" />);
    const trigger = screen.getByRole("button", { name: /张三/ });
    trigger.focus();
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("menu")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("头像用用户名首字，且对读屏隐藏；名字为空时给出占位", () => {
    const { rerender } = render(<UserMenu userName="张三" />);
    expect(screen.getByText("张")).toHaveAttribute("aria-hidden", "true");
    rerender(<UserMenu userName="" />);
    expect(screen.getByText("?")).toBeInTheDocument();
  });
});
