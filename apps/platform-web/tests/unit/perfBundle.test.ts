import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routeFirstLoadJsKb } from '../../scripts/perf-bundle.mjs';

let dist: string;

function chunk(name: string, content: string) {
  const path = join(dist, name);
  mkdirSync(join(path, '..'), { recursive: true });
  writeFileSync(path, content);
  return gzipSync(content).length;
}

beforeEach(() => {
  dist = mkdtempSync(join(tmpdir(), 'perf-bundle-'));
});

afterEach(() => {
  rmSync(dist, { recursive: true, force: true });
});

describe('routeFirstLoadJsKb', () => {
  it('sums the gzip size of the route JS files, once per file', () => {
    const a = chunk('static/chunks/a.js', 'a'.repeat(5000));
    const b = chunk('static/chunks/b.js', 'console.log(1);'.repeat(3000));
    writeFileSync(
      join(dist, 'app-build-manifest.json'),
      JSON.stringify({
        pages: {
          '/login/page': ['static/chunks/a.js', 'static/chunks/b.js', 'static/chunks/a.js'],
        },
      }),
    );
    expect(routeFirstLoadJsKb(dist)['/login/page']).toBeCloseTo((a + b) / 1024, 5);
  });

  it('ignores files that are not JavaScript', () => {
    const a = chunk('static/chunks/a.js', 'x'.repeat(4000));
    chunk('static/css/app.css', 'body{}'.repeat(2000));
    writeFileSync(
      join(dist, 'app-build-manifest.json'),
      JSON.stringify({ pages: { '/p': ['static/chunks/a.js', 'static/css/app.css'] } }),
    );
    expect(routeFirstLoadJsKb(dist)['/p']).toBeCloseTo(a / 1024, 5);
  });

  it('reports every route in the manifest', () => {
    chunk('static/chunks/a.js', 'a');
    writeFileSync(
      join(dist, 'app-build-manifest.json'),
      JSON.stringify({ pages: { '/one': ['static/chunks/a.js'], '/two': [] } }),
    );
    expect(Object.keys(routeFirstLoadJsKb(dist))).toEqual(['/one', '/two']);
    expect(routeFirstLoadJsKb(dist)['/two']).toBe(0);
  });

  it('adds the chunks of every ancestor layout to a page, each file once', () => {
    const shared = chunk('static/chunks/shared.js', 's'.repeat(4000));
    const root = chunk('static/chunks/root.js', 'r'.repeat(3000));
    const group = chunk('static/chunks/group.js', 'g'.repeat(5000));
    const page = chunk('static/chunks/page.js', 'p'.repeat(2000));
    writeFileSync(
      join(dist, 'app-build-manifest.json'),
      JSON.stringify({
        pages: {
          '/layout': ['static/chunks/shared.js', 'static/chunks/root.js'],
          '/(group)/layout': ['static/chunks/shared.js', 'static/chunks/group.js'],
          '/(group)/tenants/page': ['static/chunks/shared.js', 'static/chunks/page.js'],
          '/other/page': ['static/chunks/page.js'],
        },
      }),
    );
    const sizes = routeFirstLoadJsKb(dist);
    expect(sizes['/(group)/tenants/page']).toBeCloseTo((shared + root + group + page) / 1024, 5);
    // a route outside the group does not get the group layout, only the root layout
    expect(sizes['/other/page']).toBeCloseTo((shared + root + page) / 1024, 5);
  });

  it('fails loudly when a listed chunk is missing', () => {
    writeFileSync(
      join(dist, 'app-build-manifest.json'),
      JSON.stringify({ pages: { '/p': ['static/chunks/missing.js'] } }),
    );
    expect(() => routeFirstLoadJsKb(dist)).toThrow(/missing\.js/);
  });
});
