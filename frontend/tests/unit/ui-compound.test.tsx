import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from "@/components/ui/dialog";
import { Pagination } from "@/components/ui/pagination";
import { StickyActionBar } from "@/components/ui/sticky-action-bar";
import { Switch } from "@/components/ui/switch";
import { Table, TBody, Td, Th, THead, Tr } from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";

describe("Table", () => {
  it("renders a semantic table", () => {
    render(
      <Table>
        <THead>
          <Tr>
            <Th>姓名</Th>
          </Tr>
        </THead>
        <TBody>
          <Tr>
            <Td>张三</Td>
          </Tr>
        </TBody>
      </Table>,
    );
    expect(screen.getByRole("columnheader", { name: "姓名" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "张三" })).toBeInTheDocument();
  });
});

describe("Pagination", () => {
  it("shows the total and page size", () => {
    render(<Pagination page={0} size={20} total={45} onPageChange={() => {}} />);
    expect(screen.getByText("共 45 条，每页 20 条")).toBeInTheDocument();
  });

  it("goes to previous/next/specific pages and marks the current page", async () => {
    const onPageChange = vi.fn();
    render(<Pagination page={1} size={20} total={95} onPageChange={onPageChange} />);
    expect(screen.getByRole("button", { name: "第 2 页" })).toHaveAttribute("aria-current", "page");
    await userEvent.click(screen.getByRole("button", { name: "下一页" }));
    expect(onPageChange).toHaveBeenLastCalledWith(2);
    await userEvent.click(screen.getByRole("button", { name: "上一页" }));
    expect(onPageChange).toHaveBeenLastCalledWith(0);
    await userEvent.click(screen.getByRole("button", { name: "第 5 页" }));
    expect(onPageChange).toHaveBeenLastCalledWith(4);
  });

  it("disables previous on the first page and next on the last", () => {
    const { rerender } = render(
      <Pagination page={0} size={20} total={30} onPageChange={() => {}} />,
    );
    expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
    rerender(<Pagination page={1} size={20} total={30} onPageChange={() => {}} />);
    expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
  });

  it("renders nothing interactive for an empty list but still reports the total", () => {
    render(<Pagination page={0} size={20} total={0} onPageChange={() => {}} />);
    expect(screen.getByText("共 0 条，每页 20 条")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "第 1 页" })).toBeNull();
  });

  it("collapses long ranges with ellipses", () => {
    render(<Pagination page={10} size={10} total={1000} onPageChange={() => {}} />);
    expect(screen.getAllByText("…").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "第 100 页" })).toBeInTheDocument();
  });
});

describe("Dialog", () => {
  it("is labelled and closable", async () => {
    const onOpenChange = vi.fn();
    render(
      <Dialog open onOpenChange={onOpenChange}>
        <DialogContent>
          <DialogTitle>确认操作</DialogTitle>
          <DialogDescription>描述文字</DialogDescription>
          <DialogFooter>
            <button>好</button>
          </DialogFooter>
        </DialogContent>
      </Dialog>,
    );
    const dialog = screen.getByRole("dialog", { name: "确认操作" });
    expect(within(dialog).getByText("描述文字")).toBeInTheDocument();
    await userEvent.keyboard("{Escape}");
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});

describe("Switch", () => {
  it("toggles and exposes role=switch", async () => {
    const onCheckedChange = vi.fn();
    render(<Switch aria-label="启用" checked={true} onCheckedChange={onCheckedChange} />);
    const sw = screen.getByRole("switch", { name: "启用" });
    expect(sw).toBeChecked();
    await userEvent.click(sw);
    expect(onCheckedChange).toHaveBeenCalledWith(false);
  });
});

describe("Tabs", () => {
  it("selects tabs", async () => {
    const onValueChange = vi.fn();
    render(
      <Tabs value="all" onValueChange={onValueChange}>
        <TabsList>
          <TabsTrigger value="all">全部</TabsTrigger>
          <TabsTrigger value="ACTIVE">启用</TabsTrigger>
        </TabsList>
      </Tabs>,
    );
    expect(screen.getByRole("tab", { name: "全部" })).toHaveAttribute("aria-selected", "true");
    await userEvent.click(screen.getByRole("tab", { name: "启用" }));
    expect(onValueChange).toHaveBeenCalledWith("ACTIVE");
  });
});

describe("StickyActionBar", () => {
  it("shows the validation summary and actions", () => {
    render(
      <StickyActionBar summary="有 2 项校验未通过">
        <button>保存</button>
      </StickyActionBar>,
    );
    expect(screen.getByText("有 2 项校验未通过")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存" })).toBeInTheDocument();
  });

  it("omits the summary when there is none", () => {
    render(
      <StickyActionBar>
        <button>保存</button>
      </StickyActionBar>,
    );
    expect(screen.queryByRole("status")).toBeNull();
  });
});
