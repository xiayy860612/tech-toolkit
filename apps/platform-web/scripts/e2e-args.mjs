/**
 * Turns `pnpm e2e` arguments into Playwright arguments.
 *   --tag <name>   select tests carrying @<name>; repeat to require all tags (intersection)
 *   --chrome       run the installed Google Chrome instead of bundled Chromium
 * Everything else is passed to `playwright test` unchanged. No --tag runs every test.
 */
export function buildPlaywrightInvocation(argv) {
  const tags = [];
  const passthrough = [];
  const env = {};

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--tag' || arg.startsWith('--tag=')) {
      const inline = arg.startsWith('--tag=');
      const value = inline ? arg.slice('--tag='.length) : argv[i + 1];
      if (!value || value.startsWith('--')) {
        throw new Error('--tag needs a value, e.g. --tag 1.0.0');
      }
      tags.push(value.replace(/^@/, ''));
      if (!inline) {
        i += 1;
      }
    } else if (arg === '--chrome') {
      env.E2E_BROWSER_CHANNEL = 'chrome';
    } else {
      passthrough.push(arg);
    }
  }

  const args = ['test'];
  if (tags.length > 0) {
    const escaped = tags.map((tag) => `@${tag.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`);
    args.push('--grep', escaped.map((tag) => `(?=.*${tag})`).join(''));
  }
  return { args: [...args, ...passthrough], env };
}
