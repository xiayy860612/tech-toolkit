import { expect, test, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { BUDGET, INTERACTION_BUDGET_MS, median } from '../../scripts/perf-budget.mjs';
import { bootstrapTenant, e2eConfig, loginToConsole, openUserMenu } from '../e2e/fixtures';

// Spec FR-029: INP-style interaction timing (<= 200 ms) and layout shift (<= 0.1).
// Requires the full stack, see tests/e2e/fixtures.ts. Results go to perf-reports/interactions.json.
const config = e2eConfig();
const RUNS = Number(process.env.PERF_RUNS ?? 5);
const DESKTOP = { width: 1280, height: 800 };
const PHONE = { width: 360, height: 740 };
/** The Event Timing API only reports events slower than this, so "no entry" means "below it". */
const EVENT_TIMING_FLOOR_MS = 16;

interface PerfWindow {
  __perf: { events: { duration: number }[]; shifts: number };
}
// The bundled DOM typings predate these Event Timing fields.
type InteractionEntry = PerformanceEntry & { interactionId?: number };
type EventObserverInit = PerformanceObserverInit & { durationThreshold: number };

const interactions: { name: string; medianMs: number; targetMs: number; ok: boolean }[] = [];
const layoutShifts: { page: string; viewport: string; value: number }[] = [];

async function observe(page: Page) {
  await page.addInitScript((floor) => {
    const store: PerfWindow['__perf'] = { events: [], shifts: 0 };
    (window as unknown as PerfWindow).__perf = store;
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        if ((entry as InteractionEntry).interactionId) {
          store.events.push({ duration: entry.duration });
        }
      }
    }).observe({ type: 'event', durationThreshold: floor, buffered: true } as EventObserverInit);
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        const shift = entry as PerformanceEntry & { value: number; hadRecentInput: boolean };
        if (!shift.hadRecentInput) store.shifts += shift.value;
      }
    }).observe({ type: 'layout-shift', buffered: true });
  }, EVENT_TIMING_FLOOR_MS);
}

/** Longest event duration (input to next paint) caused by `action`; the floor if none was slow enough. */
async function timeInteraction(page: Page, action: () => Promise<void>): Promise<number> {
  await page.evaluate(() => {
    (window as unknown as PerfWindow).__perf.events = [];
  });
  await action();
  await page.waitForTimeout(400);
  const durations = await page.evaluate(() =>
    (window as unknown as PerfWindow).__perf.events.map((event) => event.duration),
  );
  return durations.length > 0 ? Math.max(...durations) : EVENT_TIMING_FLOOR_MS;
}

async function record(name: string, run: (index: number) => Promise<number>) {
  const samples: number[] = [];
  for (let index = 0; index < RUNS; index += 1) samples.push(await run(index));
  const medianMs = median(samples);
  interactions.push({
    name,
    medianMs,
    targetMs: INTERACTION_BUDGET_MS,
    ok: medianMs <= INTERACTION_BUDGET_MS,
  });
  expect(medianMs, `${name}: ${samples.join(', ')} ms`).toBeLessThanOrEqual(INTERACTION_BUDGET_MS);
}

test.beforeAll(() => {
  bootstrapTenant(config);
});

test.afterAll(() => {
  mkdirSync('perf-reports', { recursive: true });
  writeFileSync(
    'perf-reports/interactions.json',
    JSON.stringify({ interactions, layoutShifts }, null, 2),
  );
});

test('desktop interactions respond within the INP budget', async ({ page }) => {
  await page.setViewportSize(DESKTOP);
  await observe(page);
  await loginToConsole(page, config);

  await record('打开用户菜单', async () => {
    const duration = await timeInteraction(page, () => openUserMenu(page));
    await page.keyboard.press('Escape');
    return duration;
  });

  const links = ['用户管理', '模块管理', '租户管理'];
  await record('点击导航项', async (index) => {
    const label = links[index % links.length];
    return timeInteraction(page, async () => {
      await page
        .getByRole('navigation', { name: '主导航' })
        .getByRole('link', { name: label })
        .click();
      await expect(page.getByRole('heading', { level: 1, name: label })).toBeVisible();
    });
  });
});

test('phone menu opens and closes within the INP budget', async ({ page }) => {
  await page.setViewportSize(PHONE);
  await observe(page);
  await loginToConsole(page, config);

  await record('打开 Sheet 菜单', async () =>
    timeInteraction(page, async () => {
      await page.getByRole('button', { name: '打开菜单' }).click();
      await expect(page.getByRole('dialog')).toBeVisible();
    }),
  );
  await record('关闭 Sheet 菜单', async () => {
    const duration = await timeInteraction(page, async () => {
      await page.getByRole('dialog').getByRole('button', { name: '关闭' }).click();
      await expect(page.getByRole('dialog')).toBeHidden();
    });
    await page.getByRole('button', { name: '打开菜单' }).click();
    return duration;
  });
});

for (const [viewport, size] of [
  ['desktop', DESKTOP],
  ['phone', PHONE],
] as const) {
  test(`layout does not shift while the list pages load (${viewport})`, async ({ page }) => {
    await page.setViewportSize(size);
    await observe(page);
    await loginToConsole(page, config);
    for (const path of ['/tenants', '/modules', '/users']) {
      await page.goto(path);
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(800);
      const value = await page.evaluate(() => (window as unknown as PerfWindow).__perf.shifts);
      layoutShifts.push({ page: path, viewport, value });
      expect(value, `${path} (${viewport})`).toBeLessThanOrEqual(BUDGET.cls);
    }
  });
}
