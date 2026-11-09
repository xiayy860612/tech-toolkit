import { describe, expect, it } from 'vitest';
import {
  BUDGET,
  INTERACTION_BUDGET_MS,
  LIST_FIRST_SCREEN_MS,
  evaluate,
  extractMetrics,
  median,
  redact,
} from '../../scripts/perf-budget.mjs';
import { renderMarkdown } from '../../scripts/perf-report.mjs';

const good = {
  lcpMs: 1800,
  cls: 0.02,
  fcpMs: 1200,
  ttfbMs: 300,
  tbtMs: 90,
  speedIndexMs: 2100,
  performanceScore: 96,
  firstLoadJsKb: 120,
};

describe('BUDGET (spec FR-029)', () => {
  it('matches the agreed targets', () => {
    expect(BUDGET).toEqual({
      lcpMs: 2500,
      cls: 0.1,
      fcpMs: 1800,
      ttfbMs: 800,
      tbtMs: 200,
      speedIndexMs: 3400,
      performanceScore: 90,
      firstLoadJsKb: 170,
    });
    expect(INTERACTION_BUDGET_MS).toBe(200);
    expect(LIST_FIRST_SCREEN_MS).toBe(2000);
  });
});

describe('median', () => {
  it('takes the middle of an odd number of runs', () => {
    expect(median([5, 1, 3])).toBe(3);
  });

  it('averages the two middle values of an even number of runs', () => {
    expect(median([4, 1, 3, 2])).toBe(2.5);
  });

  it('does not reorder its input', () => {
    const values = [3, 1, 2];
    median(values);
    expect(values).toEqual([3, 1, 2]);
  });

  it('rejects an empty list', () => {
    expect(() => median([])).toThrow(/at least one/);
  });
});

describe('evaluate', () => {
  it('passes when every metric is inside its budget (boundaries inclusive)', () => {
    const results = evaluate({ ...good, lcpMs: 2500, performanceScore: 90 }, { profile: 'mobile' });
    expect(results.every((result) => result.ok)).toBe(true);
    expect(results).toHaveLength(8);
  });

  it('flags a maximum that is exceeded', () => {
    const results = evaluate({ ...good, lcpMs: 2501 }, { profile: 'mobile' });
    const lcp = results.find((result) => result.metric === 'lcpMs');
    expect(lcp).toMatchObject({ ok: false, value: 2501, target: 2500, kind: 'max' });
  });

  it('flags a minimum that is not reached (performance score)', () => {
    const results = evaluate({ ...good, performanceScore: 89 }, { profile: 'mobile' });
    expect(results.find((result) => result.metric === 'performanceScore')).toMatchObject({
      ok: false,
      kind: 'min',
    });
  });

  it('only scores the Lighthouse performance score on the mobile profile', () => {
    const results = evaluate({ ...good, performanceScore: 10 }, { profile: 'desktop' });
    expect(results.map((result) => result.metric)).not.toContain('performanceScore');
    expect(results.every((result) => result.ok)).toBe(true);
  });

  it('treats a missing metric as a failure instead of skipping it', () => {
    const { tbtMs: _omitted, ...rest } = good;
    void _omitted;
    const results = evaluate(rest, { profile: 'mobile' });
    expect(results.find((result) => result.metric === 'tbtMs')).toMatchObject({
      ok: false,
      value: null,
    });
  });
});

describe('extractMetrics', () => {
  const lhr = {
    categories: { performance: { score: 0.93 } },
    audits: {
      'largest-contentful-paint': { numericValue: 1900.4 },
      'cumulative-layout-shift': { numericValue: 0.011 },
      'first-contentful-paint': { numericValue: 1100 },
      'server-response-time': { numericValue: 240 },
      'total-blocking-time': { numericValue: 50 },
      'speed-index': { numericValue: 2000 },
      'network-requests': {
        details: {
          items: [
            { resourceType: 'Script', transferSize: 51200 },
            { resourceType: 'Script', transferSize: 51200 },
            { resourceType: 'Stylesheet', transferSize: 99999 },
            { resourceType: 'Document', transferSize: 99999 },
          ],
        },
      },
    },
  };

  it('maps Lighthouse audits to the budget metrics', () => {
    expect(extractMetrics(lhr)).toEqual({
      lcpMs: 1900.4,
      cls: 0.011,
      fcpMs: 1100,
      ttfbMs: 240,
      tbtMs: 50,
      speedIndexMs: 2000,
      performanceScore: 93,
      firstLoadJsKb: 100,
    });
  });

  it('leaves out metrics Lighthouse did not report', () => {
    const metrics = extractMetrics({ categories: {}, audits: {} });
    expect(metrics).toEqual({ firstLoadJsKb: 0 });
  });
});

describe('redact', () => {
  it('replaces every occurrence of each secret', () => {
    expect(redact('a=SECRET1; b=SECRET1 c=S2', ['SECRET1', 'S2'])).toBe(
      'a=[REDACTED]; b=[REDACTED] c=[REDACTED]',
    );
  });

  it('ignores empty secrets', () => {
    expect(redact('text', ['', undefined as unknown as string])).toBe('text');
  });
});

describe('renderMarkdown', () => {
  const report = {
    generatedAt: '2026-11-07T10:00:00.000Z',
    runs: 5,
    pages: [
      {
        path: '/tenants',
        profile: 'mobile',
        runs: [good, good, { ...good, lcpMs: 3000 }, good, good],
        median: good,
        results: evaluate(good, { profile: 'mobile' }),
      },
      {
        path: '/users',
        profile: 'desktop',
        runs: [{ ...good, lcpMs: 4000 }],
        median: { ...good, lcpMs: 4000 },
        results: evaluate({ ...good, lcpMs: 4000 }, { profile: 'desktop' }),
      },
    ],
    interactions: [
      { name: '打开用户菜单', medianMs: 80, targetMs: 200, ok: true },
      { name: '点击导航项', medianMs: 260, targetMs: 200, ok: false },
    ],
  };

  it('has a section per page and profile with the median, target and verdict', () => {
    const markdown = renderMarkdown(report);
    expect(markdown).toContain('/tenants');
    expect(markdown).toContain('mobile');
    expect(markdown).toMatch(/LCP[^\n]*1800[^\n]*2500[^\n]*✅/);
    expect(markdown).toMatch(/LCP[^\n]*4000[^\n]*2500[^\n]*❌/);
  });

  it('labels the list first-screen result (SC-008)', () => {
    const withList = {
      ...report,
      pages: [
        {
          ...report.pages[0],
          results: [
            ...report.pages[0].results,
            { metric: 'listFirstScreenMs', value: 2400, target: 2000, kind: 'max', ok: false },
          ],
        },
      ],
    };
    expect(renderMarkdown(withList as never)).toMatch(/列表首屏[^\n]*2400[^\n]*2000[^\n]*❌/);
  });

  it('lists the individual runs next to the median', () => {
    expect(renderMarkdown(report)).toContain('3000');
  });

  it('explains that 16 ms is the Event Timing floor', () => {
    expect(renderMarkdown(report)).toMatch(/Event Timing[^\n]*16 ms/);
  });

  it('reports interactions and an overall verdict', () => {
    const markdown = renderMarkdown(report);
    expect(markdown).toMatch(/打开用户菜单[^\n]*80[^\n]*✅/);
    expect(markdown).toMatch(/点击导航项[^\n]*260[^\n]*❌/);
    expect(markdown).toContain('未达标');
  });

  it('says all targets are met when nothing failed', () => {
    const passing = {
      ...report,
      pages: [report.pages[0]].map((page) => ({ ...page, runs: [good] })),
      interactions: [report.interactions[0]],
    };
    expect(renderMarkdown(passing)).toContain('全部达标');
  });
});
