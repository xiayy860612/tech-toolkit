import type { BudgetResult, Metrics } from './perf-budget.mjs';
export interface PageReport {
  path: string;
  profile: string;
  runs: Metrics[];
  median: Metrics;
  results: BudgetResult[];
}
export interface InteractionResult {
  name: string;
  medianMs: number;
  targetMs: number;
  ok: boolean;
}
export interface PerfReport {
  generatedAt: string;
  runs: number;
  pages: PageReport[];
  interactions: InteractionResult[];
}
export function renderMarkdown(report: PerfReport): string;
