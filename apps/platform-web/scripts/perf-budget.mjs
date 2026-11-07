/**
 * Frontend performance budget (spec FR-029) and the pure helpers around it.
 * Core Web Vitals "good" values, Lighthouse green ranges, plus a per-route JS budget.
 */
export const BUDGET = {
  lcpMs: 2500,
  cls: 0.1,
  fcpMs: 1800,
  ttfbMs: 800,
  tbtMs: 200,
  speedIndexMs: 3400,
  performanceScore: 90,
  firstLoadJsKb: 170,
};

/** List pages: first screen within 2 s with 500 rows (SC-008); measured as LCP. */
export const LIST_FIRST_SCREEN_MS = 2000;

/** INP target for the measured interactions (opening menus, navigating). */
export const INTERACTION_BUDGET_MS = 200;

/** Metrics where bigger is better. Everything else is a maximum. */
const MINIMUMS = new Set(['performanceScore']);

export function median(values) {
  if (values.length === 0) throw new Error('median needs at least one value');
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

/**
 * Compares measured metrics with the budget. A metric that was not measured fails.
 * The Lighthouse performance score is only a target for the mobile profile (FR-029).
 */
export function evaluate(metrics, { profile }) {
  return Object.entries(BUDGET)
    .filter(([metric]) => metric !== 'performanceScore' || profile === 'mobile')
    .map(([metric, target]) => {
      const kind = MINIMUMS.has(metric) ? 'min' : 'max';
      const value = metrics[metric] ?? null;
      const ok = value !== null && (kind === 'min' ? value >= target : value <= target);
      return { metric, value, target, kind, ok };
    });
}

const audit = (lhr, id) => lhr.audits?.[id]?.numericValue;

/** Maps a Lighthouse result (lhr) to the budget metrics. Unreported metrics are left out. */
export function extractMetrics(lhr) {
  const candidates = {
    lcpMs: audit(lhr, 'largest-contentful-paint'),
    cls: audit(lhr, 'cumulative-layout-shift'),
    fcpMs: audit(lhr, 'first-contentful-paint'),
    ttfbMs: audit(lhr, 'server-response-time'),
    tbtMs: audit(lhr, 'total-blocking-time'),
    speedIndexMs: audit(lhr, 'speed-index'),
    performanceScore:
      typeof lhr.categories?.performance?.score === 'number'
        ? Math.round(lhr.categories.performance.score * 100)
        : undefined,
  };
  const metrics = Object.fromEntries(
    Object.entries(candidates).filter(([, value]) => typeof value === 'number'),
  );
  const requests = lhr.audits?.['network-requests']?.details?.items ?? [];
  const scriptBytes = requests
    .filter((request) => request.resourceType === 'Script')
    .reduce((sum, request) => sum + (request.transferSize ?? 0), 0);
  return { ...metrics, firstLoadJsKb: scriptBytes / 1024 };
}

/** Removes secrets (e.g. a session cookie value) from text before it is logged or saved. */
export function redact(text, secrets) {
  return secrets
    .filter((secret) => typeof secret === 'string' && secret.length > 0)
    .reduce((out, secret) => out.split(secret).join('[REDACTED]'), text);
}
