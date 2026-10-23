import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ErrorPage from "@/app/error";
import NotFound from "@/app/not-found";

describe("not-found", () => {
  it("提示页面不存在并给出回到用户管理的链接", () => {
    render(<NotFound />);
    expect(screen.getByRole("heading", { name: "页面不存在" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "回到用户管理" })).toHaveAttribute("href", "/users");
  });
});

describe("error", () => {
  it("显示通用错误并可重试；不展示内部错误信息", async () => {
    const reset = jest.fn();
    const spy = jest.spyOn(console, "error").mockImplementation(() => undefined);
    render(<ErrorPage error={new Error("内部细节 secret-token")} reset={reset} />);
    expect(screen.getByRole("heading", { name: "页面出了点问题" })).toBeInTheDocument();
    expect(screen.queryByText(/secret-token/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(reset).toHaveBeenCalledTimes(1);
    spy.mockRestore();
  });
});
