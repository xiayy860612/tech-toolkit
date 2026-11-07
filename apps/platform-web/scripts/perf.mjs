#!/usr/bin/env node
/**
 * `pnpm perf` — frontend performance check (spec FR-029, SC-011).
 *
 *   pnpm perf [--runs 5] [--page /tenants]... [--profile mobile|desktop]
 *
 * Needs the full stack (Postgres, Redis, tenant-core-api, platform-web production build) and:
 *   E2E_BASE_URL     platform-web URL (default http://localhost:3000)
 *   TENANT_CORE_CLI  Bootstrap CLI (+ its database environment), as for the E2E suite
 *   PERF_PSQL        command that reads SQL on stdin as the database owner of a THROW-AWAY test
 *                    database, e.g. "docker exec -i <postgres-container> psql -U <owner> -d <db>"
 * Passwords are generated per run and never printed; the session cookie stays inside Chrome.
 * Output: perf-reports/report.json and perf-reports/report.md. Exit code 1 if any target is missed.
 */
import { execFileSync, spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { launch } from 'chrome-launcher';
import lighthouse from 'lighthouse';
import desktopConfig from 'lighthouse/core/config/desktop-config.js';
import puppeteer from 'puppeteer-core';
import { LIST_FIRST_SCREEN_MS, evaluate, extractMetrics, median, redact } from './perf-budget.mjs';
import { routeFirstLoadJsKb } from './perf-bundle.mjs';
import { buildUserSeedSql } from './perf-seed.mjs';
import { renderMarkdown } from './perf-report.mjs';

const BASE_URL = (process.env.E2E_BASE_URL ?? 'http://localhost:3000').replace(/\/$/, '');
const LIST_PAGES = ['/tenants', '/modules', '/users'];
const ROUTES = {
  '/login': '/login/page',
  '/change-password': '/change-password/page',
  '/tenants': '/(console)/tenants/page',
  '/modules': '/(console)/modules/page',
  '/users': '/(console)/users/page',
};
const USER_COUNT = 500;

function parseArgs(argv) {
  const options = { runs: 5, pages: [], profiles: ['mobile', 'desktop'] };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--runs') options.runs = Number(argv[(i += 1)]);
    else if (argv[i] === '--page') options.pages.push(argv[(i += 1)]);
    else if (argv[i] === '--profile') options.profiles = [argv[(i += 1)]];
    else throw new Error(`unknown argument ${argv[i]}`);
  }
  if (!Number.isInteger(options.runs) || options.runs < 1) throw new Error('--runs must be >= 1');
  for (const page of options.pages) {
    if (!(page in ROUTES)) throw new Error(`unknown page ${page}`);
  }
  return options;
}

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required environment variable ${name}`);
  return value;
}

function splitCommand(name) {
  const [command, ...args] = required(name).split(/\s+/);
  return { command, args };
}

function cli(args, input) {
  const { command, args: fixed } = splitCommand('TENANT_CORE_CLI');
  execFileSync(command, [...fixed, '--output', 'json', ...args], {
    input,
    stdio: ['pipe', 'pipe', 'inherit'],
  });
}

function createUserWithAccount(tenant, displayName, username, password) {
  const out = execFileSync(
    splitCommand('TENANT_CORE_CLI').command,
    [
      ...splitCommand('TENANT_CORE_CLI').args,
      '--output',
      'json',
      'user',
      'create',
      '--tenant',
      tenant,
      '--display-name',
      displayName,
    ],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] },
  );
  const { tenantUserId } = JSON.parse(out.trim().split('\n').at(-1));
  cli(
    [
      'account',
      'create',
      '--tenant',
      tenant,
      '--user',
      tenantUserId,
      '--username',
      username,
      '--password-stdin',
    ],
    `${password}\n`,
  );
}

function seed(tenantSlug) {
  const { command, args } = splitCommand('PERF_PSQL');
  const result = spawnSync(command, [...args, '-v', 'ON_ERROR_STOP=1'], {
    input: buildUserSeedSql({ tenantSlug, count: USER_COUNT }),
    encoding: 'utf8',
  });
  if (result.status !== 0) throw new Error(`seeding failed: ${result.stderr}`);
}

async function login(browser, username, password, tenantSlug) {
  const page = await browser.newPage();
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle0' });
  await page.type('input[name="tenantSlug"]', tenantSlug);
  await page.type('input[name="username"]', username);
  await page.type('input[name="password"]', password);
  await Promise.all([
    page.waitForFunction(() => !location.pathname.startsWith('/login'), { timeout: 30_000 }),
    page.click('button[type="submit"]'),
  ]);
  await page.close();
}

/** Keeps the login cookie but drops the HTTP cache, so every run is a cold first load. */
async function clearCache(browser) {
  const page = await browser.newPage();
  const session = await page.createCDPSession();
  await session.send('Network.clearBrowserCache');
  await page.close();
}

async function clearCookies(browser) {
  const page = await browser.newPage();
  const session = await page.createCDPSession();
  await session.send('Network.clearBrowserCookies');
  await page.close();
}

async function measure(browser, path, profile, runs, port) {
  const samples = [];
  for (let i = 0; i < runs; i += 1) {
    await clearCache(browser);
    const flags = {
      port,
      output: 'json',
      logLevel: 'error',
      onlyCategories: ['performance'],
      disableStorageReset: true,
    };
    const result = await lighthouse(
      `${BASE_URL}${path}`,
      flags,
      profile === 'desktop' ? desktopConfig : undefined,
    );
    const landed = new URL(result.lhr.finalDisplayedUrl).pathname;
    if (landed !== path) {
      throw new Error(`measured ${landed} instead of ${path} (not logged in, or redirected?)`);
    }
    if (process.env.PERF_DEBUG) {
      const kinds = (result.lhr.audits['network-requests']?.details?.items ?? []).map(
        (r) => r.resourceType,
      );
      console.log(
        `  [debug] ${path} resource types:`,
        [...new Set(kinds)].join(','),
        'count',
        kinds.length,
      );
    }
    samples.push(extractMetrics(result.lhr));
    process.stdout.write(`  ${profile} ${path} run ${i + 1}/${runs}\n`);
  }
  return samples;
}

function summarize(path, profile, runs, bundleKb) {
  const keys = new Set(runs.flatMap((run) => Object.keys(run)));
  const med = Object.fromEntries(
    [...keys].map((key) => [
      key,
      median(runs.map((run) => run[key]).filter((v) => v !== undefined)),
    ]),
  );
  // Lighthouse counts transferred JS; the build manifest gives the gzip size of the route's chunks.
  med.firstLoadJsKb = Math.max(med.firstLoadJsKb ?? 0, bundleKb ?? 0);
  const results = evaluate(med, { profile });
  if (LIST_PAGES.includes(path) && med.lcpMs !== undefined) {
    results.push({
      metric: 'listFirstScreenMs',
      value: med.lcpMs,
      target: LIST_FIRST_SCREEN_MS,
      kind: 'max',
      ok: med.lcpMs <= LIST_FIRST_SCREEN_MS,
    });
  }
  return { path, profile, runs, median: med, results };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const pages = options.pages.length > 0 ? options.pages : Object.keys(ROUTES);
  const slug = `perf-${Date.now().toString(36)}`;
  const secret = () => `Perf-${randomBytes(9).toString('hex')}`;
  const listUser = { username: 'perfadmin', initial: secret(), changed: secret() };
  const changeUser = { username: 'perfchange', password: secret() };
  const secrets = [listUser.initial, listUser.changed, changeUser.password];

  mkdirSync('perf-reports', { recursive: true });

  console.log(`1/4 interactions and layout shift (Playwright) on Tenant ${slug}`);
  const playwright = spawnSync(
    'pnpm',
    ['exec', 'playwright', 'test', '--config', 'playwright.perf.config.ts'],
    {
      stdio: 'inherit',
      env: {
        ...process.env,
        E2E_BASE_URL: BASE_URL,
        E2E_TENANT_SLUG: slug,
        E2E_USERNAME: listUser.username,
        E2E_INITIAL_PASSWORD: listUser.initial,
        E2E_NEW_PASSWORD: listUser.changed,
        PERF_RUNS: String(options.runs),
      },
    },
  );
  const interactionData = JSON.parse(readFileSync('perf-reports/interactions.json', 'utf8'));

  console.log(
    `2/4 test data: ${USER_COUNT} synthetic Users, one Account that must change its password`,
  );
  createUserWithAccount(slug, 'Perf Change User', changeUser.username, changeUser.password);
  seed(slug);

  console.log('3/4 Lighthouse (Chrome)');
  const chrome = await launch({ chromeFlags: ['--headless=new', '--disable-gpu'] });
  const report = {
    generatedAt: new Date().toISOString(),
    runs: options.runs,
    pages: [],
    interactions: interactionData.interactions,
  };
  let sizes = {};
  try {
    sizes = routeFirstLoadJsKb('.next');
  } catch (error) {
    console.warn(
      `  build manifest not read (${error.message}); using Lighthouse transfer size only`,
    );
  }
  try {
    const browser = await puppeteer.connect({ browserURL: `http://127.0.0.1:${chrome.port}` });
    const add = async (path) => {
      for (const profile of options.profiles) {
        const runs = await measure(browser, path, profile, options.runs, chrome.port);
        report.pages.push(summarize(path, profile, runs, sizes[ROUTES[path]]));
      }
    };
    if (pages.includes('/login')) await add('/login');
    await login(browser, listUser.username, listUser.changed, slug);
    for (const path of LIST_PAGES.filter((p) => pages.includes(p))) await add(path);
    if (pages.includes('/change-password')) {
      await clearCookies(browser);
      await login(browser, changeUser.username, changeUser.password, slug);
      await add('/change-password');
    }
    await browser.disconnect();
  } finally {
    await chrome.kill();
  }

  console.log('4/4 report');
  const markdown = redact(renderMarkdown(report), secrets);
  writeFileSync('perf-reports/report.json', redact(JSON.stringify(report, null, 2), secrets));
  writeFileSync('perf-reports/report.md', markdown);
  const failed =
    report.pages.flatMap((p) => p.results).filter((r) => !r.ok).length +
    report.interactions.filter((i) => !i.ok).length;
  console.log(
    `report: perf-reports/report.md — ${failed === 0 ? '全部达标' : `${failed} 项未达标`}`,
  );
  process.exit(failed === 0 && playwright.status === 0 ? 0 : 1);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(2);
});
