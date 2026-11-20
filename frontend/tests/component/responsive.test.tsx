import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/users",
}));

import { AppShell } from "@/components/AppShell";
import { StickyActionBar } from "@/components/ui/sticky-action-bar";
import { Card, CardHeader } from "@/components/ui/card";
import { Table, TBody, Td, Th, THead, Tr } from "@/components/ui/table";
import UsersPage from "@/app/(console)/users/page";

/** jsdom 没有布局，这里只守护响应式关键容器的类名（FR-031）。 */
describe("responsive containers", () => {
  it("tables scroll inside their own container instead of the page", () => {
    const { container } = render(
      <Table>
        <THead>
          <Tr>
            <Th>a</Th>
          </Tr>
        </THead>
        <TBody>
          <Tr>
            <Td>b</Td>
          </Tr>
        </TBody>
      </Table>,
    );
    expect(container.firstElementChild).toHaveClass("overflow-x-auto");
    // 单元格不换行：窄屏下表格在自己的容器里横向滚动，而不是把文字挤成一字一行
    expect(screen.getByText("a")).toHaveClass("whitespace-nowrap");
    expect(screen.getByText("b")).toHaveClass("whitespace-nowrap");
    expect(container.querySelector("table")).toHaveClass("min-w-max");
  });

  it("the content area has a narrow-screen gutter and can shrink", () => {
    render(
      <AppShell me={{ displayName: "管理员" }} variant="full">
        <p>内容</p>
      </AppShell>,
    );
    const main = screen.getByText("内容").closest("main")!;
    expect(main).toHaveClass("px-4", "sm:px-8", "min-w-0");
  });

  it("card headers, page headers and the sticky action bar wrap on narrow screens", () => {
    render(
      <Card>
        <CardHeader>标题</CardHeader>
      </Card>,
    );
    expect(screen.getByText("标题")).toHaveClass("flex-wrap");

    const bar = render(
      <StickyActionBar>
        <button>ok</button>
      </StickyActionBar>,
    );
    expect(bar.container.firstElementChild).toHaveClass("flex-wrap", "-mx-4", "sm:-mx-8");

    const page = render(<UsersPage />);
    expect(page.getByRole("heading", { name: "用户管理" }).parentElement).toHaveClass("flex-wrap");
  });
});
