#!/usr/bin/env node
// Friendly E2E entry point: `pnpm e2e --tag 1.0.0 --tag happy-path --chrome --headed`
import { spawnSync } from 'node:child_process';
import { buildPlaywrightInvocation } from './e2e-args.mjs';

let plan;
try {
  plan = buildPlaywrightInvocation(process.argv.slice(2));
} catch (error) {
  process.stderr.write(`${error.message}\n`);
  process.exit(2);
}

const result = spawnSync('pnpm', ['exec', 'playwright', ...plan.args], {
  stdio: 'inherit',
  env: { ...process.env, ...plan.env },
});
process.exit(result.status ?? 1);
