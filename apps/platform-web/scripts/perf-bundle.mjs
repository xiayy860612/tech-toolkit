import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

/** `/(group)/tenants/page` -> ['/layout', '/(group)/layout', '/(group)/tenants/layout'] */
function ancestorLayouts(route) {
  const segments = route.split('/').filter(Boolean).slice(0, -1);
  return ['', ...segments.map((_, i) => `/${segments.slice(0, i + 1).join('/')}`)].map(
    (prefix) => `${prefix}/layout`,
  );
}

/**
 * First-load JavaScript per route, in KB of gzip size, from Next's `.next/app-build-manifest.json`.
 * A route loads its own chunks plus those of every ancestor layout (Next lists a layout's chunks
 * only on the layout entry), each file counted once. Non-JS files are ignored. Complements
 * Lighthouse's transfer size.
 */
export function routeFirstLoadJsKb(distDir) {
  const { pages } = JSON.parse(readFileSync(join(distDir, 'app-build-manifest.json'), 'utf8'));
  const sizes = {};
  for (const [route, files] of Object.entries(pages)) {
    const layoutFiles = route.endsWith('/layout')
      ? []
      : ancestorLayouts(route).flatMap((layout) => pages[layout] ?? []);
    const scripts = [...new Set([...files, ...layoutFiles])].filter((file) => file.endsWith('.js'));
    const bytes = scripts.reduce(
      (sum, file) => sum + gzipSync(readFileSync(join(distDir, file))).length,
      0,
    );
    sizes[route] = bytes / 1024;
  }
  return sizes;
}
