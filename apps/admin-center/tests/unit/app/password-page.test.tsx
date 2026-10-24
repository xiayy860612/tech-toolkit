import { render, screen, within } from "@testing-library/react";
import PasswordPage from "@/app/(shell)/account/password/page";
import { getSession } from "@/lib/session/get-session";

jest.mock("@/app/(shell)/account/password/actions", () => ({ changePassword: jest.fn() }));
jest.mock("@/lib/session/get-session", () => ({ getSession: jest.fn() }));

afterEach(() => jest.resetAllMocks());

describe("/account/password 修改密码页", () => {
  it("h1「修改密码」、说明里点名当前账号、面包屑「修改密码」、三个字段", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "authenticated", session: { account: { loginName: "zhangsan" } } as never });
    render(await PasswordPage());
    expect(screen.getByRole("heading", { level: 1, name: "修改密码" })).toBeInTheDocument();
    expect(screen.getByText("zhangsan", { selector: "code" })).toBeInTheDocument();
    expect(screen.getByText(/修改后当前会话保持有效/)).toBeInTheDocument();
    const crumbs = screen.getByRole("navigation", { name: "面包屑" });
    expect(within(crumbs).getByText("修改密码")).toHaveAttribute("aria-current", "page");
    expect(screen.getByLabelText("当前密码")).toBeInTheDocument();
    expect(screen.getByLabelText("新密码")).toBeInTheDocument();
    expect(screen.getByLabelText("确认新密码")).toBeInTheDocument();
  });

  it("无法确认会话（外壳已提示服务暂不可用）：页面与表单照常渲染，只是不点名账号", async () => {
    jest.mocked(getSession).mockResolvedValue({ status: "unverified" });
    render(await PasswordPage());
    expect(screen.getByRole("heading", { level: 1, name: "修改密码" })).toBeInTheDocument();
    expect(screen.getByText("修改当前登录账号的密码。修改后当前会话保持有效。")).toBeInTheDocument();
    expect(screen.queryByText("zhangsan")).not.toBeInTheDocument();
  });
});
