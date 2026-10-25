import AxeBuilder from "@axe-core/playwright";
import { type Browser, type BrowserContext, type Locator, type Page } from "@playwright/test";
import { expect, login } from "./fixtures";

/**
 * US9（响应式、无障碍、触控）三个规格共用的辅助：多上下文管理、经界面造用户、版面断言、axe、触控目标与间距度量、键盘辅助。
 * 全部是黑盒断言（只看浏览器里实际渲染的结果），不依赖具体的 class 名。
 *
 * 名字约定：本组规格造的用户名都以「响应式」开头并带本次运行与视口的后缀，且刻意不含其它规格造数用户的子串
 * （多账号用户、无账号用户、已停用用户、分页用户、在线者、离线者、建档员、停权对象、强退对象、改密者、终结验证、甲号、乙号、丙号）。
 */

/** 本次运行的标识；与视口宽度一起做名字后缀，重复执行与六个视口项目互不冲突。 */
export const runId = Date.now().toString(36);

export const PASSWORD = "E2e-Resp-Pass-1";

export const widthOf = (page: Page) => page.viewportSize()?.width ?? 1280;
/** 手机：侧边栏是抽屉，列表是卡片（< 768）。 */
export const isPhone = (page: Page) => widthOf(page) < 768;
/** 平板：侧边栏固定为图标条（768 – 1023）。 */
export const isTablet = (page: Page) => widthOf(page) >= 768 && widthOf(page) < 1024;
export const isDesktop = (page: Page) => widthOf(page) >= 1024;

/** 账号名 ≤ 32 个字符且只含 `[A-Za-z0-9_.-]`。 */
export function namesFor(page: Page, tag: string): { userName: string; accountName: string } {
  const suffix = `${runId}${widthOf(page)}${tag}`;
  return { userName: `响应式${suffix}`, accountName: `e2e.r9.${suffix}` };
}

/** 新浏览器上下文不继承项目配置：baseURL、视口、来源地址头（x-forwarded-for）都显式带上，每个上下文用不同的来源地址。 */
export class Contexts {
  private opened: BrowserContext[] = [];

  async open(browser: Browser, baseURL: string | undefined, like: Page): Promise<Page> {
    const ip = `198.51.100.${20 + Math.floor(Math.random() * 200)}`;
    const context = await browser.newContext({
      baseURL,
      viewport: like.viewportSize() ?? undefined,
      extraHTTPHeaders: { "x-forwarded-for": ip },
    });
    this.opened.push(context);
    return context.newPage();
  }

  /** 在新上下文里登录并确认进入了受保护页面（一个账号只有一个会话：每个账号只在一个上下文里登录）。 */
  async signIn(browser: Browser, baseURL: string | undefined, like: Page, account: string, password = PASSWORD): Promise<Page> {
    const page = await this.open(browser, baseURL, like);
    await login(page, account, password);
    await expect(page).toHaveURL(/\/users$/);
    return page;
  }

  async closeAll(): Promise<void> {
    await Promise.all(this.opened.splice(0).map((context) => context.close()));
  }
}

/** 以已登录的运维人员身份经「新建用户」界面（US4）建一个用户及其账号，返回用户标识。 */
export async function createUserViaUi(
  page: Page,
  v: { userName: string; accountName: string; password?: string },
): Promise<string> {
  await page.goto("/users/new");
  await page.getByLabel("用户名", { exact: true }).fill(v.userName);
  await page.getByLabel("账号名", { exact: true }).fill(v.accountName);
  await page.getByLabel("初始密码", { exact: true }).fill(v.password ?? PASSWORD);
  await page.getByRole("button", { name: /^创建/ }).click();
  await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}\?created=1$/);
  return new URL(page.url()).pathname.split("/").pop()!;
}

/** 等某个元素（含子树）上的进入 / 退出动画结束：zoom-in 动画期间尺寸被缩放到 95%，量尺寸与点击都要等它结束。 */
export async function settle(locator: Locator): Promise<void> {
  await locator.evaluate((el) => Promise.all(el.getAnimations({ subtree: true }).map((animation) => animation.finished)).then(() => undefined));
}

// ---------------------------------------------------------------------------------------------------------------------
// 版面
// ---------------------------------------------------------------------------------------------------------------------

/** 无整页横向滚动：`scrollWidth ≤ innerWidth`（并且不超过不含滚动条的 `clientWidth`）。 */
export async function expectNoHorizontalScroll(page: Page, where: string): Promise<void> {
  const m = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    bodyScrollWidth: document.body.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
    innerWidth: window.innerWidth,
  }));
  expect(m.scrollWidth, `${where}：整页横向滚动（scrollWidth ${m.scrollWidth} > innerWidth ${m.innerWidth}）`).toBeLessThanOrEqual(m.innerWidth);
  expect(m.scrollWidth, `${where}：整页横向滚动（scrollWidth ${m.scrollWidth} > clientWidth ${m.clientWidth}）`).toBeLessThanOrEqual(m.clientWidth);
  expect(m.bodyScrollWidth, `${where}：body 横向溢出（${m.bodyScrollWidth} > ${m.clientWidth}）`).toBeLessThanOrEqual(m.clientWidth);
}

/**
 * 版面问题清单（空 = 没有）：任何可见元素伸出视口左右边；任何元素的内容比自己的盒子宽（文字撑破 / 被裁掉）。
 * 有意的省略号截断（`text-overflow: ellipsis`）不算；1px 以内的元素（sr-only、焦点哨兵）不算。
 */
export async function layoutProblems(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const viewport = document.documentElement.clientWidth;
    const problems: string[] = [];
    const describe = (el: Element) => {
      const text = (el.getAttribute("aria-label") ?? el.textContent ?? "").trim().replace(/\s+/g, " ");
      return `<${el.tagName.toLowerCase()}${el.getAttribute("data-slot") ? ` data-slot=${el.getAttribute("data-slot")}` : ""}> 「${text.slice(0, 30)}」`;
    };
    for (const el of document.body.querySelectorAll("*")) {
      if (el instanceof SVGElement || !(el instanceof HTMLElement)) continue;
      if (!el.checkVisibility()) continue;
      // sr-only（视觉隐藏）的元素及其子树不算
      if (el.closest("[class*='sr-only']") || getComputedStyle(el).clip === "rect(0px, 0px, 0px, 0px)") continue;
      const rect = el.getBoundingClientRect();
      if (rect.width <= 1 || rect.height <= 1) continue;
      if (rect.left < -0.5 || rect.right > viewport + 0.5) {
        problems.push(`${describe(el)} 伸出视口：${Math.round(rect.left)} ~ ${Math.round(rect.right)}（视口 ${viewport}）`);
      }
      const style = getComputedStyle(el);
      // 输入框里的长值在框内滚动，不算撑破
      const isField = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement;
      if (!isField && style.display !== "inline" && el.scrollWidth > el.clientWidth + 1 && style.textOverflow !== "ellipsis") {
        problems.push(`${describe(el)} 内容撑破盒子：scrollWidth ${el.scrollWidth} > clientWidth ${el.clientWidth}`);
      }
    }
    return problems;
  });
}

/** 一页的版面总断言：无整页横向滚动、没有伸出视口或撑破盒子的元素。 */
export async function expectLayoutOk(page: Page, where: string): Promise<void> {
  await expectNoHorizontalScroll(page, where);
  expect(await layoutProblems(page), `${where}：版面问题`).toEqual([]);
}

/** 对话框（等动画结束后）完整落在视口内：矮屏上要靠对话框自身限高并滚动，确认 / 取消才仍可达。 */
export async function expectDialogReachable(page: Page, dialog: Locator, where: string): Promise<void> {
  await settle(dialog);
  const m = await dialog.evaluate((el) => {
    const rect = el.getBoundingClientRect();
    return {
      top: rect.top,
      bottom: rect.bottom,
      left: rect.left,
      right: rect.right,
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
    };
  });
  expect(m.left, `${where}：对话框左边超出视口`).toBeGreaterThanOrEqual(-0.5);
  expect(m.right, `${where}：对话框右边超出视口`).toBeLessThanOrEqual(m.innerWidth + 0.5);
  expect(m.top, `${where}：对话框顶部超出视口（无法滚动到）`).toBeGreaterThanOrEqual(-0.5);
  expect(m.bottom, `${where}：对话框底部超出视口（无法滚动到）`).toBeLessThanOrEqual(m.innerHeight + 0.5);
}

// ---------------------------------------------------------------------------------------------------------------------
// 外壳：侧边栏随视口变化
// ---------------------------------------------------------------------------------------------------------------------

const sidebar = (page: Page) => page.locator('[data-slot="sidebar"]');
export const mainNav = (page: Page) => page.getByRole("navigation", { name: "主导航" });

/**
 * 外壳的三种形态：桌面（≥ 1024）侧边栏展开、有收起开关；平板（768 – 1023）固定为图标条、没有开关；
 * 手机（< 768）侧边栏不在页面里，顶栏有菜单按钮，抽屉关着。
 */
export async function expectShellMode(page: Page, where: string): Promise<void> {
  if (isPhone(page)) {
    await expect(page.getByRole("button", { name: "打开导航菜单" }), `${where}：手机应有菜单按钮`).toBeVisible();
    await expect(page.getByRole("dialog"), `${where}：手机的抽屉应是关着的`).toHaveCount(0);
    await expect(mainNav(page), `${where}：手机上抽屉关着时导航不应在页面里`).toHaveCount(0);
    return;
  }
  await expect(mainNav(page), `${where}：导航应可见`).toBeVisible();
  const link = mainNav(page).getByRole("link", { name: "用户管理" });
  const linkWidth = (await link.boundingBox())!.width;
  if (isTablet(page)) {
    await expect(sidebar(page), `${where}：平板应是图标条`).toHaveAttribute("data-state", "collapsed");
    expect(linkWidth, `${where}：平板的导航项应是图标方块`).toBeLessThan(64);
    await expect(page.getByRole("button", { name: /侧边栏|导航菜单/ }), `${where}：平板（图标条固定）不应有开关`).toHaveCount(0);
  } else {
    await expect(sidebar(page), `${where}：桌面应是展开的侧边栏`).toHaveAttribute("data-state", "expanded");
    expect(linkWidth, `${where}：桌面的导航项应带文字（宽）`).toBeGreaterThan(160);
    await expect(page.getByRole("button", { name: "收起侧边栏" }), `${where}：桌面应有收起开关`).toBeVisible();
  }
}

/** 打开导航：手机点菜单按钮打开抽屉（等动画结束），其它视口导航本来就在。返回导航元素。 */
export async function openNav(page: Page): Promise<Locator> {
  if (isPhone(page)) {
    await page.getByRole("button", { name: "打开导航菜单" }).click();
    const drawer = page.getByRole("dialog");
    await expect(drawer).toBeVisible();
    await settle(drawer);
  }
  await expect(mainNav(page)).toBeVisible();
  return mainNav(page);
}

/** 经侧边栏（手机经抽屉）进入某个导航项。 */
export async function gotoViaNav(page: Page, label: "用户管理" | "会话管理"): Promise<void> {
  const nav = await openNav(page);
  await nav.getByRole("link", { name: label }).click();
  await expect(page).toHaveURL(label === "用户管理" ? /\/users(\?.*)?$/ : /\/sessions(\?.*)?$/);
  if (isPhone(page)) await expect(page.getByRole("dialog")).toHaveCount(0);
}

/** 打开顶栏的用户菜单（等动画结束），返回菜单元素。 */
export async function openUserMenu(page: Page, userName: string): Promise<Locator> {
  await page.getByRole("button", { name: new RegExp(userName.slice(0, 20).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")) }).click();
  const menu = page.getByRole("menu");
  await expect(menu).toBeVisible();
  await settle(menu);
  return menu;
}

// ---------------------------------------------------------------------------------------------------------------------
// axe
// ---------------------------------------------------------------------------------------------------------------------

/** axe：serious / critical 必须为 0；失败信息带规则、节点与摘要，便于定位。 */
export async function expectNoSeriousViolations(page: Page, where: string): Promise<void> {
  const { violations } = await new AxeBuilder({ page }).analyze();
  const serious = violations
    .filter((v) => v.impact === "serious" || v.impact === "critical")
    .map((v) => ({ id: v.id, impact: v.impact, help: v.help, nodes: v.nodes.slice(0, 3).map((n) => ({ target: n.target, summary: n.failureSummary })) }));
  expect(serious, `${where}：axe serious / critical 违规`).toEqual([]);
}

// ---------------------------------------------------------------------------------------------------------------------
// 触控目标与间距
// ---------------------------------------------------------------------------------------------------------------------

/** 可交互元素（可见、不在被 aria-hidden 的背景里）。 */
const INTERACTIVE =
  'a[href], button, input:not([type="hidden"]), select, textarea, summary, [role="button"], [role="menuitem"], [role="link"]:not([aria-disabled="true"]), [role="checkbox"], [role="tab"], [tabindex]:not([tabindex="-1"])';

/**
 * 触控目标不足 44 × 44 CSS 像素的可交互元素清单（空 = 都达标）。`root` 缺省为整页（对话框 / 菜单 / 抽屉打开时传它们的选择器；
 * 背景已被 aria-hidden，不会计入）。
 * 「有效目标」= 在元素自己的盒子外 44px 范围内做命中测试，取真正命中该元素的区域（含 `::after` 拉伸的整卡可点击区域）；
 * 盒子本身已 ≥ 44 × 44 的直接通过。禁用的按钮（pointer-events: none）只按自身盒子算。
 */
export async function touchTargetProblems(page: Page, root = "body"): Promise<string[]> {
  return page.evaluate(
    ({ selector, scope }) => {
      const MIN = 44 - 0.5;
      const problems: string[] = [];
      const container = document.querySelector(scope);
      if (!container) return [`找不到范围 ${scope}`];
      for (const el of container.querySelectorAll<HTMLElement>(selector)) {
        if (!el.checkVisibility() || el.closest('[aria-hidden="true"], [inert], [data-radix-focus-guard]')) continue;
        if (el.hasAttribute("data-radix-focus-guard")) continue;
        let rect = el.getBoundingClientRect();
        if (rect.width <= 1 || rect.height <= 1) continue; // sr-only（如未获得焦点的「跳到主要内容」，单独测）
        let width = rect.width;
        let height = rect.height;
        if (width < MIN || height < MIN) {
          el.scrollIntoView({ block: "center", inline: "center" });
          rect = el.getBoundingClientRect();
          let minX = Infinity;
          let maxX = -Infinity;
          let minY = Infinity;
          let maxY = -Infinity;
          const step = 2;
          for (let y = rect.top - 44; y <= rect.bottom + 44; y += step) {
            for (let x = rect.left - 44; x <= rect.right + 44; x += step) {
              if (x < 0 || y < 0 || x >= window.innerWidth || y >= window.innerHeight) continue;
              const hit = document.elementFromPoint(x, y);
              if (hit && el.contains(hit)) {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
              }
            }
          }
          width = Math.max(rect.width, maxX >= minX ? maxX - minX + step : 0);
          height = Math.max(rect.height, maxY >= minY ? maxY - minY + step : 0);
        }
        if (width < MIN || height < MIN) {
          const name = (el.getAttribute("aria-label") ?? el.textContent ?? el.getAttribute("name") ?? "").trim().replace(/\s+/g, " ").slice(0, 30);
          problems.push(`<${el.tagName.toLowerCase()}> 「${name}」 有效触控目标 ${Math.round(width)} × ${Math.round(height)}`);
        }
      }
      return problems;
    },
    { selector: INTERACTIVE, scope: root },
  );
}

/** 目标按钮与最近的其它可交互元素的最小间距（CSS 像素，矩形到矩形的距离；没有邻居时为 Infinity）。 */
export async function minGapToNeighbours(page: Page, target: Locator): Promise<number> {
  const handle = await target.elementHandle();
  if (!handle) throw new Error("找不到目标按钮");
  return handle.evaluate((el, selector) => {
    const a = el.getBoundingClientRect();
    let min = Infinity;
    for (const other of document.querySelectorAll<HTMLElement>(selector)) {
      if (other === el || el.contains(other) || other.contains(el)) continue;
      if (!other.checkVisibility() || other.closest('[aria-hidden="true"], [inert], [data-radix-focus-guard]')) continue;
      const b = other.getBoundingClientRect();
      if (b.width <= 1 || b.height <= 1) continue;
      const dx = Math.max(0, a.left - b.right, b.left - a.right);
      const dy = Math.max(0, a.top - b.bottom, b.top - a.bottom);
      min = Math.min(min, Math.hypot(dx, dy));
    }
    return min;
  }, INTERACTIVE);
}

// ---------------------------------------------------------------------------------------------------------------------
// 键盘
// ---------------------------------------------------------------------------------------------------------------------

/** 按 Tab（`reverse` 为 Shift+Tab）直到目标获得焦点；超过 `max` 次仍未到则失败。返回按了几次。 */
export async function tabTo(page: Page, target: Locator, { max = 40, reverse = false } = {}): Promise<number> {
  for (let presses = 0; presses <= max; presses++) {
    const focused = await target.evaluate((el) => el === document.activeElement).catch(() => false);
    if (focused) return presses;
    await page.keyboard.press(reverse ? "Shift+Tab" : "Tab");
  }
  throw new Error(`按了 ${max} 次 ${reverse ? "Shift+Tab" : "Tab"} 仍未到达目标`);
}

/** 当前焦点是否在选择器指定的容器内。 */
export const focusInside = (page: Page, containerSelector: string) =>
  page.evaluate((selector) => !!document.activeElement?.closest(selector), containerSelector);

/** 当前焦点元素是否有清晰可见的焦点指示（轮廓或环形阴影，且不是透明的）。先等焦点环的过渡动画（transition）结束再量。 */
export const hasVisibleFocusIndicator = (page: Page) =>
  page.evaluate(async () => {
    const el = document.activeElement;
    if (!el || el === document.body) return false;
    await Promise.all(el.getAnimations().map((animation) => animation.finished.catch(() => undefined)));
    const style = getComputedStyle(el);
    const outline = style.outlineStyle !== "none" && parseFloat(style.outlineWidth) > 0;
    // box-shadow 是多层：Tailwind 的 ring 只有一层有非零尺寸与非透明颜色
    const layers = style.boxShadow.match(/(?:rgba?|oklab|oklch|color)\([^)]*\)\s+-?[\d.]+px\s+-?[\d.]+px\s+[\d.]+px\s+[\d.]+px/g) ?? [];
    const ring = layers.some((layer) => {
      const color = layer.slice(0, layer.indexOf(")") + 1);
      const sizes = (layer.slice(layer.indexOf(")") + 1).match(/-?[\d.]+px/g) ?? []).map(parseFloat);
      return sizes.some((n) => n !== 0) && !/^rgba\(0, 0, 0, 0\)/.test(color) && !/\/ 0\)/.test(color);
    });
    return outline || ring;
  });
