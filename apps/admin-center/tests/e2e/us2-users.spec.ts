import AxeBuilder from "@axe-core/playwright";
import { type Locator, type Page } from "@playwright/test";
import { actualAccountCount, actualUserCount, expect, login, seedUsers, test, USERS_SEED } from "./support/fixtures";

/**
 * US2：在 User Manager 中浏览用户并查看其全部账号（quickstart S4；FR-017 ~ FR-020；SC-002、SC-003）。
 * 前提：栈已按 devops/local/scripts/bootstrap.sh 起好，且已 init-admin（见 support/fixtures.ts）。
 * 造数：创建用户的 API 属于 US4，这里用 SQL + 部署期命令 add-account（见 seedUsers），固定标识、可重复执行。
 * 手机（< 768）上列表与账号表都以卡片呈现，所以定位器按视口宽度切换。
 */

const PAGE_SIZE = 20;
const { multi, none, disabled, missing } = USERS_SEED;

const isNarrow = (page: Page) => (page.viewportSize()?.width ?? 1280) < 768;

/** 列表里的每个用户：桌面是表格行，手机是卡片。 */
const userItems = (page: Page): Locator =>
  isNarrow(page)
    ? page.getByRole("list", { name: "用户卡片" }).getByRole("listitem")
    : page.getByRole("table", { name: "用户列表" }).locator("tbody tr");

const userItem = (page: Page, name: string) => userItems(page).filter({ hasText: name });

/** 某个用户条目里的「关联账号数」。 */
const accountCountOf = (item: Locator, page: Page) =>
  isNarrow(page) ? item.locator("dd").nth(1) : item.getByRole("cell").nth(2);

/** 详情页里的账号条目与账号名。 */
const accountItems = (page: Page): Locator =>
  isNarrow(page)
    ? page.getByRole("list", { name: "关联账号卡片" }).getByRole("listitem")
    : page.getByRole("table", { name: "关联账号" }).locator("tbody tr");

const accountNames = (page: Page) =>
  isNarrow(page)
    ? accountItems(page).locator("p").allTextContents()
    : accountItems(page).locator("td:first-child").allTextContents();

const pager = (page: Page) => page.getByRole("navigation", { name: "分页" });

async function search(page: Page, text: string) {
  await page.getByRole("searchbox", { name: "搜索用户名或账号名" }).fill(text);
  await page.getByRole("button", { name: "搜索" }).click();
}

const noHorizontalScroll = (page: Page) =>
  page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);

const seriousViolations = async (page: Page) =>
  (await new AxeBuilder({ page }).analyze()).violations.filter((v) => v.impact === "serious" || v.impact === "critical");

test.beforeAll(() => seedUsers());

test.beforeEach(async ({ page }) => {
  await login(page);
  await expect(page).toHaveURL(/\/users$/);
});

test.describe("S4：用户列表", () => {
  test("h1「用户管理」；每行含用户名、状态、关联账号数、创建时间；每页 20 条；总数与页码正确", async ({ page }) => {
    await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
    if (!isNarrow(page)) {
      await expect(page.getByRole("table", { name: "用户列表" }).getByRole("columnheader")).toHaveText([
        "用户名",
        "状态",
        "关联账号数",
        "创建时间",
      ]);
    }
    const total = actualUserCount();
    expect(total).toBeGreaterThan(PAGE_SIZE); // 造数保证多于一页
    await expect(userItems(page)).toHaveCount(PAGE_SIZE);
    await expect(pager(page)).toContainText(`共 ${total} 位用户 · 第 1 / ${Math.ceil(total / PAGE_SIZE)} 页`);

    // 每个条目都有用户名链接、状态文字、日期
    const first = userItems(page).first();
    await expect(first.getByRole("link")).toHaveAttribute("href", /^\/users\/[0-9a-f-]{36}$/);
    await expect(first).toContainText(/正常|已停用/);
    await expect(first.locator("time")).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  });

  test("没有整页横向滚动；axe 无严重违规；触控目标不小于 44px", async ({ page }) => {
    expect(await noHorizontalScroll(page)).toBeLessThanOrEqual(0);
    expect(await seriousViolations(page)).toEqual([]);
    for (const control of [
      page.getByRole("searchbox", { name: "搜索用户名或账号名" }),
      page.getByRole("button", { name: "搜索" }),
      userItems(page).first().getByRole("link"),
      pager(page).getByRole("link", { name: "下一页" }),
    ]) {
      const box = await control.boundingBox();
      expect(box!.height).toBeGreaterThanOrEqual(43.5);
    }
  });

  test("状态徽标：已停用与正常都用文字表达", async ({ page }) => {
    await search(page, disabled.name);
    await expect(userItem(page, disabled.name)).toContainText("已停用");
    await search(page, multi.name);
    await expect(userItem(page, multi.name)).toContainText("正常");
  });
});

test.describe("S4：搜索", () => {
  test("账号名片段、大小写任意、包含匹配：找到多账号用户，账号数为 3", async ({ page }) => {
    for (const text of ["E2EMULTI.ALPHA", "e2emulti", "Multi.Be", "  e2eMULTI.gamma  "]) {
      await search(page, text);
      await expect(page).toHaveURL(/\/users\?q=/);
      await expect(userItems(page)).toHaveCount(1);
      const item = userItem(page, multi.name);
      await expect(item).toBeVisible();
      await expect(accountCountOf(item, page)).toHaveText("3");
      // 搜索词回填到搜索框（去首尾空白）
      await expect(page.getByRole("searchbox")).toHaveValue(text.trim());
    }
  });

  test("按用户名搜索；结果总数与页码正确，分页链接保留搜索词", async ({ page }) => {
    await search(page, USERS_SEED.pagePrefix);
    await expect(userItems(page)).toHaveCount(PAGE_SIZE);
    await expect(pager(page)).toContainText(
      `共 ${USERS_SEED.pageCount} 位用户 · 第 1 / ${Math.ceil(USERS_SEED.pageCount / PAGE_SIZE)} 页`,
    );
    await pager(page).getByRole("link", { name: "下一页" }).click();
    await expect(page).toHaveURL(new RegExp(`q=${encodeURIComponent(USERS_SEED.pagePrefix)}&page=2$`));
    await expect(userItems(page)).toHaveCount(USERS_SEED.pageCount - PAGE_SIZE);
    await expect(page.getByRole("searchbox")).toHaveValue(USERS_SEED.pagePrefix);
  });

  test("无结果：显示搜索词与「清除搜索」，清除后回到完整列表", async ({ page }) => {
    await search(page, "no-such-user-xyz");
    await expect(page.getByText("没有找到匹配「no-such-user-xyz」的用户")).toBeVisible();
    await page.getByRole("link", { name: "清除搜索" }).click();
    await expect(page).toHaveURL(/\/users$/);
    await expect(userItems(page)).toHaveCount(PAGE_SIZE);
  });

  test("只有空白的搜索词视为不搜索；搜索词里的 HTML 按文本显示", async ({ page }) => {
    await search(page, "   ");
    await expect(userItems(page)).toHaveCount(PAGE_SIZE);
    await search(page, "<b>x</b>");
    await expect(page.getByText("没有找到匹配「<b>x</b>」的用户")).toBeVisible();
  });
});

test.describe("S4：翻页", () => {
  test("下一页 / 上一页；第一页与最后一页的按钮禁用；越界页码带到最后一页", async ({ page }) => {
    const total = actualUserCount();
    const pages = Math.ceil(total / PAGE_SIZE);
    await expect(pager(page).getByText("上一页")).toHaveAttribute("aria-disabled", "true");

    await pager(page).getByRole("link", { name: "下一页" }).click();
    await expect(page).toHaveURL(/\/users\?page=2$/);
    await expect(pager(page)).toContainText(`第 2 / ${pages} 页`);
    await expect(userItems(page)).toHaveCount(pages === 2 ? total - PAGE_SIZE : PAGE_SIZE);

    await pager(page).getByRole("link", { name: "上一页" }).click();
    await expect(page).toHaveURL(/\/users$/);
    await expect(pager(page)).toContainText(`第 1 / ${pages} 页`);

    await page.goto("/users?page=99999");
    await expect(page).toHaveURL(new RegExp(`/users\\?page=${pages}$`));
    await expect(pager(page).getByText("下一页")).toHaveAttribute("aria-disabled", "true");
    await expect(userItems(page)).toHaveCount(total - (pages - 1) * PAGE_SIZE);

    await page.goto("/users?page=abc"); // 非法页码回到第 1 页
    await expect(pager(page)).toContainText(`第 1 / ${pages} 页`);
  });
});

test.describe("S4：用户详情", () => {
  test("多账号用户：列表里点进详情，列出全部账号（无遗漏、无重复），数量与 UAM 实际一致（SC-003）", async ({ page }) => {
    await search(page, multi.name);
    const item = userItem(page, multi.name);
    const listedCount = Number(await accountCountOf(item, page).innerText());
    await item.getByRole("link", { name: multi.name }).click();

    await expect(page).toHaveURL(new RegExp(`/users/${multi.id}$`));
    await expect(page.getByRole("heading", { level: 1, name: multi.name })).toBeVisible();
    const actual = actualAccountCount(multi.id);
    expect(actual).toBe(multi.accounts.length);
    await expect(page.getByRole("heading", { level: 2, name: `关联账号（${actual}）` })).toBeVisible();
    await expect(accountItems(page)).toHaveCount(actual);
    expect(listedCount).toBe(actual); // 列表与详情一致

    const names = await accountNames(page);
    expect(names.slice().sort()).toEqual(multi.accounts.slice().sort());
    expect(new Set(names).size).toBe(names.length);
    // 每个账号：种类、状态、创建时间
    for (let i = 0; i < actual; i++) {
      const row = accountItems(page).nth(i);
      await expect(row).toContainText("账号名 / 密码");
      await expect(row).toContainText("正常");
      await expect(row.locator("time")).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    }
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText(multi.name);
    // 页面里不出现完整的用户 UUID 之外的凭据字样
    await expect(page.locator("body")).not.toContainText(/password|密码哈希|argon/i);
    expect(await noHorizontalScroll(page)).toBeLessThanOrEqual(0);
    expect(await seriousViolations(page)).toEqual([]);
  });

  test("无账号用户：显示「该用户暂无关联账号」，账号数 0", async ({ page }) => {
    await page.goto(`/users/${none.id}`);
    await expect(page.getByRole("heading", { level: 1, name: none.name })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "关联账号（0）" })).toBeVisible();
    await expect(page.getByText("该用户暂无关联账号")).toBeVisible();
    expect(actualAccountCount(none.id)).toBe(0);
  });

  test("已停用用户：状态「已停用」并提示；仍列出其账号", async ({ page }) => {
    await page.goto(`/users/${disabled.id}`);
    await expect(page.getByRole("status").filter({ hasText: "该用户已停用" })).toBeVisible();
    await expect(page.getByRole("region", { name: "基本信息" })).toContainText("已停用");
    await expect(accountItems(page)).toHaveCount(actualAccountCount(disabled.id));
    expect(await accountNames(page)).toEqual([disabled.account]);
  });

  test("不存在的用户标识与格式非法的标识：显示「用户不存在」，可返回列表", async ({ page }) => {
    for (const id of [missing, "not-a-uuid"]) {
      await page.goto(`/users/${id}`);
      await expect(page.getByText("用户不存在", { exact: true }).first()).toBeVisible();
      await expect(page.getByText("该用户可能已被移除，或链接有误。")).toBeVisible();
    }
    await page.getByRole("link", { name: "返回用户列表" }).click();
    await expect(page).toHaveURL(/\/users$/);
    await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();
  });
});

test.describe("SC-002：从任一页面到用户详情不超过 2 次点击 / 触控", () => {
  test("从另一个用户的详情页：面包屑「用户管理」（1）→ 列表里的用户（2）", async ({ page }) => {
    await page.goto(`/users/${none.id}`);
    let clicks = 0;
    await page.getByRole("navigation", { name: "面包屑" }).getByRole("link", { name: "用户管理" }).click();
    clicks++;
    await expect(page.getByRole("heading", { level: 1, name: "用户管理" })).toBeVisible();

    const target = userItems(page).last(); // 第一页上的任一用户
    const name = (await target.getByRole("link").innerText()).trim();
    await target.getByRole("link").click();
    clicks++;
    await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}$/);
    await expect(page.getByRole("heading", { level: 1, name })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: /^关联账号（\d+）$/ })).toBeVisible();
    expect(clicks).toBeLessThanOrEqual(2);
  });

  test("桌面 / 平板的侧边栏：「用户管理」（1）→ 列表里的用户（2）", async ({ page }) => {
    test.skip(isNarrow(page), "手机上侧边栏是抽屉（先开抽屉再点导航），这条路径需要 3 次触控；手机走面包屑路径");
    await page.goto(`/users/${disabled.id}`);
    await page.getByRole("navigation", { name: "主导航" }).getByRole("link", { name: "用户管理" }).click();
    await expect(page).toHaveURL(/\/users$/);
    await userItems(page).first().getByRole("link").click();
    await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}$/);
    await expect(page.getByRole("heading", { level: 2, name: /^关联账号（\d+）$/ })).toBeVisible();
  });

  test("在列表页：搜索后 1 次点击进入详情", async ({ page }) => {
    await search(page, multi.accounts[0]);
    await userItem(page, multi.name).getByRole("link").click();
    await expect(page).toHaveURL(new RegExp(`/users/${multi.id}$`));
  });
});
