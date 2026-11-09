import { describe, expect, it } from 'vitest';
import { buildPlaywrightInvocation } from '../../scripts/e2e-args.mjs';

describe('buildPlaywrightInvocation', () => {
  it('runs everything when no option is given', () => {
    expect(buildPlaywrightInvocation([])).toEqual({ args: ['test'], env: {} });
  });

  it('turns one --tag into an escaped --grep (a leading @ is optional)', () => {
    expect(buildPlaywrightInvocation(['--tag', '1.0.0']).args).toEqual([
      'test',
      '--grep',
      '(?=.*@1\\.0\\.0)',
    ]);
    expect(buildPlaywrightInvocation(['--tag=@happy-path']).args).toEqual([
      'test',
      '--grep',
      '(?=.*@happy-path)',
    ]);
  });

  it('intersects repeated --tag options', () => {
    expect(buildPlaywrightInvocation(['--tag', '1.0.0', '--tag', 'happy-path']).args).toEqual([
      'test',
      '--grep',
      '(?=.*@1\\.0\\.0)(?=.*@happy-path)',
    ]);
  });

  it('--chrome selects the installed Google Chrome', () => {
    expect(buildPlaywrightInvocation(['--chrome']).env).toEqual({ E2E_BROWSER_CHANNEL: 'chrome' });
  });

  it('passes every other argument through to Playwright', () => {
    expect(
      buildPlaywrightInvocation(['--headed', '--tag', 'happy-path', 'tests/e2e/login.spec.ts'])
        .args,
    ).toEqual(['test', '--grep', '(?=.*@happy-path)', '--headed', 'tests/e2e/login.spec.ts']);
  });

  it('rejects a --tag without a value', () => {
    expect(() => buildPlaywrightInvocation(['--tag'])).toThrow('--tag needs a value');
    expect(() => buildPlaywrightInvocation(['--tag', '--headed'])).toThrow('--tag needs a value');
  });
});
