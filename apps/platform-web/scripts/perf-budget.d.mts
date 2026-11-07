export interface Metrics {
  lcpMs?: number;
  cls?: number;
  fcpMs?: number;
  ttfbMs?: number;
  tbtMs?: number;
  speedIndexMs?: number;
  performanceScore?: number;
  firstLoadJsKb?: number;
  listFirstScreenMs?: number;
}
export interface BudgetResult {
  metric: keyof Metrics;
  value: number | null;
  target: number;
  kind: 'min' | 'max';
  ok: boolean;
}
export const BUDGET: Required<Omit<Metrics, 'listFirstScreenMs'>>;
export const INTERACTION_BUDGET_MS: number;
export const LIST_FIRST_SCREEN_MS: number;
export function median(values: number[]): number;
export function evaluate(metrics: Metrics, options: { profile: string }): BudgetResult[];
export function extractMetrics(lhr: unknown): Metrics;
export function redact(text: string, secrets: string[]): string;
