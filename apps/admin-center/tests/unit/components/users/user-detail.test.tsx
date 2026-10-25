import { render, screen, within } from "@testing-library/react";
import { UserDetail, UserNotFound } from "@/components/users/user-detail";
import { makeAccount, makeDetail, USER_IDS } from "../../../support/users-fixtures";

jest.mock("@/app/(shell)/users/[userId]/actions", () => ({ disableUser: jest.fn(), enableUser: jest.fn() }));

describe("UserDetail", () => {
  it("h1 是用户名；基本信息：用户名、状态、创建时间、用户标识（缩写，不显示完整 UUID）", () => {
    const { container } = render(<UserDetail user={makeDetail()} />);
    expect(screen.getByRole("heading", { level: 1, name: "李四" })).toBeInTheDocument();

    const info = screen.getByRole("region", { name: "基本信息" });
    const terms = within(info).getAllByRole("term").map((t) => t.textContent);
    expect(terms).toEqual(["用户名", "状态", "创建时间", "用户标识"]);
    expect(within(info).getByText("正常")).toBeInTheDocument();
    expect(info.querySelector("time")).toHaveAttribute("datetime", "2026-09-20T01:02:00Z");
    expect(within(info).getByText("22222222")).toBeInTheDocument();
    expect(container.textContent).not.toContain(USER_IDS.li);
  });

  it("显示该用户的全部账号（账号数与传入一致）", () => {
    render(<UserDetail user={makeDetail()} />);
    expect(screen.getByRole("heading", { level: 2, name: "关联账号（2）" })).toBeInTheDocument();
    const rows = within(screen.getByRole("table", { name: "关联账号" })).getAllByRole("row").slice(1);
    expect(rows.map((r) => within(r).getAllByRole("cell")[0].textContent)).toEqual(["lisi", "lisi.mobile"]);
  });

  it("无账号：账号区显示空态「该用户暂无关联账号」", () => {
    render(<UserDetail user={makeDetail({ accounts: [] })} />);
    expect(screen.getByText("该用户暂无关联账号")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "关联账号（0）" })).toBeInTheDocument();
  });

  it("已停用的用户：状态徽标「已停用」并有提示", () => {
    render(<UserDetail user={makeDetail({ status: "DISABLED", accounts: [makeAccount()] })} />);
    const note = screen.getByRole("status");
    expect(note).toHaveTextContent("该用户已停用：其名下所有账号都无法登录，也没有在线会话。");
    expect(within(screen.getByRole("region", { name: "基本信息" })).getByText("已停用")).toBeInTheDocument();
  });

  it("正常用户没有停用提示", () => {
    render(<UserDetail user={makeDetail()} />);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("用户名按文本渲染", () => {
    const { container } = render(<UserDetail user={makeDetail({ name: "<script>alert(1)</script>" })} />);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("<script>alert(1)</script>");
    expect(container.querySelector("script")).toBeNull();
  });

  it("created：显示一次性提示「用户已创建」（status）", () => {
    render(<UserDetail user={makeDetail()} created />);
    expect(screen.getByRole("status")).toHaveTextContent("用户已创建");
  });

  it("本期不出现范围外的入口（编辑 / 删除）", () => {
    render(<UserDetail user={makeDetail()} />);
    for (const name of [/编辑/, /删除/]) {
      expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
    }
  });

  it("「状态管理」区块：正常用户有「停用用户」入口、已停用用户有「启用用户」入口", () => {
    const active = render(<UserDetail user={makeDetail()} />);
    expect(screen.getByRole("heading", { level: 2, name: "状态管理" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "停用用户" })).toBeEnabled();
    active.unmount();

    render(<UserDetail user={makeDetail({ status: "DISABLED" })} />);
    expect(screen.getByRole("button", { name: "启用用户" })).toBeEnabled();
  });

  it("isSelf：标注「你」，「停用用户」入口禁用并说明原因", () => {
    render(<UserDetail user={makeDetail()} isSelf />);
    expect(screen.getByRole("heading", { level: 1 }).closest("header")).toHaveTextContent("你");
    expect(screen.getByRole("button", { name: "停用用户" })).toBeDisabled();
    expect(screen.getByText("不能停用当前登录的用户。")).toBeVisible();
  });

  it("不是自己：不标注「你」，入口可用", () => {
    render(<UserDetail user={makeDetail()} />);
    expect(screen.getByRole("heading", { level: 1 }).closest("header")).not.toHaveTextContent("你");
  });
});

describe("UserNotFound", () => {
  it("「用户不存在」并给出回到用户列表的链接", () => {
    render(<UserNotFound />);
    expect(screen.getByText("用户不存在")).toBeInTheDocument();
    expect(screen.getByText("该用户可能已被移除，或链接有误。")).toBeInTheDocument();
    const back = screen.getByRole("link", { name: "返回用户列表" });
    expect(back).toHaveAttribute("href", "/users");
    expect(back).toHaveClass("h-11");
  });
});
