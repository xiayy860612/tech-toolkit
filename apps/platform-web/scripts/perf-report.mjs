const LABELS = {
  lcpMs: ['LCP', 'ms'],
  cls: ['CLS', ''],
  fcpMs: ['FCP', 'ms'],
  ttfbMs: ['TTFB', 'ms'],
  tbtMs: ['TBT', 'ms'],
  speedIndexMs: ['Speed Index', 'ms'],
  performanceScore: ['Lighthouse 评分', ''],
  firstLoadJsKb: ['首次加载 JS', 'KB'],
  listFirstScreenMs: ['列表首屏（LCP）', 'ms'],
};

const round = (value) => (value === null ? '—' : Number(Number(value).toFixed(3)));
const mark = (ok) => (ok ? '✅' : '❌');

function pageSection(page) {
  const lines = [
    `### ${page.path}（${page.profile}）`,
    '',
    '| 指标 | 中位数 | 目标 | 结果 |',
    '|---|---|---|---|',
  ];
  for (const result of page.results) {
    const [label, unit] = LABELS[result.metric];
    const comparator = result.kind === 'min' ? '≥' : '≤';
    lines.push(
      `| ${label} | ${round(result.value)} ${unit} | ${comparator} ${result.target} ${unit} | ${mark(result.ok)} |`,
    );
  }
  lines.push('', '各次结果：', '');
  page.runs.forEach((run, index) => {
    const parts = Object.entries(LABELS)
      .filter(([key]) => run[key] !== undefined)
      .map(([key, [label]]) => `${label} ${round(run[key])}`);
    lines.push(`- 第 ${index + 1} 次：${parts.join('，')}`);
  });
  return lines.join('\n');
}

/** Markdown report: per page and profile the median against the target, every run, interactions. */
export function renderMarkdown(report) {
  const pagesOk = report.pages.every((page) => page.results.every((result) => result.ok));
  const interactionsOk = report.interactions.every((item) => item.ok);
  const lines = [
    '# 前端性能报告',
    '',
    `生成时间：${report.generatedAt}；每项测 ${report.runs} 次，以中位数判定（spec FR-029）。`,
    '',
    `**结论：${pagesOk && interactionsOk ? '全部达标' : '存在未达标项'}**`,
    '',
    '## 页面加载',
    '',
    ...report.pages.map(pageSection).flatMap((section) => [section, '']),
    '## 交互响应（INP 近似）',
    '',
    '| 交互 | 中位数 | 目标 | 结果 |',
    '|---|---|---|---|',
    ...report.interactions.map(
      (item) =>
        `| ${item.name} | ${round(item.medianMs)} ms | ≤ ${item.targetMs} ms | ${mark(item.ok)} |`,
    ),
    '',
    '说明：Event Timing 只上报 ≥ 16 ms 的事件，所以 16 ms 表示“低于该下限”，不是实测值。',
    '',
  ];
  return lines.join('\n');
}
